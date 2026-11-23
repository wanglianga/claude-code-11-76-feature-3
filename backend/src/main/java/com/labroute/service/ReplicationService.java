package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.labroute.model.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watermark accounting, incremental replication, buffered drain and shadow comparison.
 *
 * Watermarks are per (tenant, stream). Streams are:
 *   TABLE:sample / TABLE:instrument_result / TABLE:report_revision  (updated_seq positions)
 *   OBJECT:default                                                    (object_store updated_seq)
 *   MESSAGE:intake                                                    (intake_message seq)
 *
 * A position is only marked VERIFIED when the shadow comparator confirms the target
 * space contains everything the source has up to that position. Read cutover refuses
 * to move while any watermark still lags.
 */
@Service
public class ReplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReplicationService.class);

    private final JdbcTemplate jdbc;
    private final CryptoSupport crypto;
    private final SpaceWriter writer;
    private final EvidenceService evidence;

    public ReplicationService(JdbcTemplate jdbc, CryptoSupport crypto, SpaceWriter writer, EvidenceService evidence) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.writer = writer;
        this.evidence = evidence;
    }

    // ---------- initial copy ----------

    @Transactional
    public void seedNewFromOld(Tenant tenant) {
        copyTable(tenant, "sample",
                "sample_id, chain_version, parent_sample_id, source_code, patient_ref_masked, " +
                        "collected_at, status, payload, origin_message_id, updated_seq",
                "sample_id, chain_version, parent_sample_id, source_code, patient_ref_masked, " +
                        "collected_at, status, payload, origin_message_id, updated_seq");
        copyTable(tenant, "instrument_result",
                "result_id, sample_id, instrument_code, analyte_code, value_num, unit, ref_low, ref_high, " +
                        "measured_at, received_at, status, payload, origin_message_id, updated_seq",
                "result_id, sample_id, instrument_code, analyte_code, value_num, unit, ref_low, ref_high, " +
                        "measured_at, received_at, status, payload, origin_message_id, updated_seq");
        copyTable(tenant, "report_revision",
                "report_id, revision, sample_id, content, content_hash, source_space, origin_message_id, updated_seq, created_at",
                "report_id, revision, sample_id, content, content_hash, source_space, origin_message_id, updated_seq, created_at");
        copyTable(tenant, "object_store",
                "object_key, object_type, content_hash, payload, updated_seq",
                "object_key, object_type, content_hash, payload, updated_seq");
        refreshWatermarks(tenant, null, false);
    }

    private void copyTable(Tenant tenant, String table, String cols, String targetCols) {
        jdbc.update("insert into " + tenant.newSchema() + "." + table + " (" + targetCols + ") " +
                "select " + cols + " from " + tenant.oldSchema() + "." + table + " on conflict do nothing");
    }

    public Map<String, Object> initialCopyStats(Tenant tenant) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String t : List.of("sample", "instrument_result", "report_revision", "object_store")) {
            Long c = jdbc.queryForObject("select count(*) from " + tenant.newSchema() + "." + t, Long.class);
            m.put(t, c);
        }
        return m;
    }

    // ---------- watermark refresh ----------

    public void refreshWatermarks(Tenant tenant, String migrationId, boolean markVerified) {
        String mid = migrationId;
        if (mid == null) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select migration_id::text as id from lab.migration
                    where tenant_id = ?::uuid order by started_at desc limit 1
                    """, tenant.tenantId());
            mid = rows.isEmpty() ? null : (String) rows.get(0).get("id");
        }
        if (mid == null) {
            return;
        }
        for (String table : List.of("sample", "instrument_result", "report_revision", "object_store")) {
            Long srcPos = maxSeq(tenant.oldSchema() + "." + table);
            Long dstPos = maxSeq(tenant.newSchema() + "." + table);
            long lag = countGreaterSeq(tenant.oldSchema() + "." + table, dstPos);
            String streamType = "object_store".equals(table) ? "OBJECT" : "TABLE";
            String name = "object_store".equals(table) ? "default" : table;
            upsertWatermark(mid, tenant.tenantId(), streamType, name, srcPos, dstPos,
                    markVerified && lag == 0 ? srcPos : null, lag);
        }
        Long intakePos = jdbc.queryForObject("""
                select coalesce(max(seq),0) from lab.intake_message where tenant_id = ?::uuid
                """, Long.class, tenant.tenantId());
        // A message is at the NEW position when it was applied there, either directly (NEW)
        // or via dual-write (OLD+NEW). Fence-applied messages are set to applied_space='NEW'
        // by applyBufferedToNew.
        long appliedNew = jdbc.queryForObject("""
                select coalesce(max(seq),0) from lab.intake_message
                where tenant_id = ?::uuid and applied_space in ('NEW','OLD+NEW')
                """, Long.class, tenant.tenantId());
        upsertWatermark(mid, tenant.tenantId(), "MESSAGE", "intake", intakePos, appliedNew,
                markVerified && intakePos != null && intakePos <= appliedNew ? intakePos : null,
                Math.max(0, (intakePos == null ? 0 : intakePos) - appliedNew));
        jdbc.update("update lab.migration set watermark_seq = ? where migration_id = ?::uuid",
                appliedNew, mid);
    }

    private Long maxSeq(String qualified) {
        Long v = jdbc.queryForObject("select coalesce(max(updated_seq),0) from " + qualified, Long.class);
        return v == null ? 0L : v;
    }

    private long countGreaterSeq(String qualified, long pos) {
        Long v = jdbc.queryForObject("select count(*) from " + qualified + " where updated_seq > ?", Long.class, pos);
        return v == null ? 0 : v;
    }

    private void upsertWatermark(String migrationId, String tenantId, String type, String name,
                                 long sourcePos, long targetPos, Long verifiedPos, long lag) {
        String state = lag == 0 ? (verifiedPos != null ? "VERIFIED" : "CAUGHT_UP") : "LAGGING";
        jdbc.update("""
                insert into lab.watermark(watermark_id, migration_id, tenant_id, stream_type, stream_name,
                  source_position, target_position, verified_position, lag, state, updated_at)
                values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (migration_id, stream_type, stream_name) do update set
                  source_position = excluded.source_position,
                  target_position = excluded.target_position,
                  verified_position = coalesce(excluded.verified_position, lab.watermark.verified_position),
                  lag = excluded.lag,
                  state = excluded.state,
                  updated_at = now()
                """, UUID.randomUUID().toString(), migrationId, tenantId, type, name,
                String.valueOf(sourcePos), String.valueOf(targetPos),
                verifiedPos == null ? null : String.valueOf(verifiedPos), lag, state);
    }

    public List<Map<String, Object>> watermarks(String migrationId) {
        return jdbc.queryForList("""
                select stream_type as "streamType", stream_name as "streamName", source_position as "sourcePosition",
                       target_position as "targetPosition", verified_position as "verifiedPosition",
                       lag, state, updated_at as "updatedAt"
                from lab.watermark where migration_id = ?::uuid order by stream_type, stream_name
                """, migrationId);
    }

    public Map<String, Object> watermarkSummary(String migrationId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select coalesce(max(lag),0) as max_lag,
                       count(*) filter (where state = 'VERIFIED') as verified,
                       count(*) as total
                from lab.watermark where migration_id = ?::uuid
                """, migrationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxLag", ((Number) row.get("max_lag")).longValue());
        out.put("verifiedStreams", ((Number) row.get("verified")).longValue());
        out.put("totalStreams", ((Number) row.get("total")).longValue());
        return out;
    }

    // ---------- incremental replication ----------

    /**
     * Copies rows present in OLD but missing/stale in NEW, keyed by business primary key.
     * REQUIRES_NEW: replication progress must survive even if the surrounding gate step
     * (e.g. a release preflight) aborts after the copy but before verification passes.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int replicateDeltas(Tenant tenant, String migrationId) {
        int total = 0;
        total += jdbc.update("""
                insert into %1$s.sample(sample_id, chain_version, parent_sample_id, source_code, patient_ref_masked,
                  collected_at, status, payload, origin_message_id, updated_seq)
                select sample_id, chain_version, parent_sample_id, source_code, patient_ref_masked,
                  collected_at, status, payload, origin_message_id, updated_seq from %2$s.sample o
                where not exists (select 1 from %1$s.sample n where n.sample_id = o.sample_id and n.updated_seq >= o.updated_seq)
                on conflict (sample_id) do update set chain_version = excluded.chain_version,
                  parent_sample_id = excluded.parent_sample_id, payload = excluded.payload,
                  updated_seq = excluded.updated_seq
                """.formatted(tenant.newSchema(), tenant.oldSchema()));
        total += jdbc.update("""
                insert into %1$s.instrument_result(result_id, sample_id, instrument_code, analyte_code, value_num,
                  unit, ref_low, ref_high, measured_at, received_at, status, payload, origin_message_id, updated_seq)
                select result_id, sample_id, instrument_code, analyte_code, value_num, unit, ref_low, ref_high,
                  measured_at, received_at, status, payload, origin_message_id, updated_seq from %2$s.instrument_result o
                where not exists (select 1 from %1$s.instrument_result n where n.result_id = o.result_id and n.updated_seq >= o.updated_seq)
                on conflict (result_id) do update set sample_id = excluded.sample_id, value_num = excluded.value_num,
                  unit = excluded.unit, ref_low = excluded.ref_low, ref_high = excluded.ref_high,
                  measured_at = excluded.measured_at, payload = excluded.payload, updated_seq = excluded.updated_seq
                """.formatted(tenant.newSchema(), tenant.oldSchema()));
        total += jdbc.update("""
                insert into %1$s.report_revision(report_id, revision, sample_id, content, content_hash,
                  source_space, origin_message_id, updated_seq, created_at)
                select report_id, revision, sample_id, content, content_hash, source_space, origin_message_id,
                  updated_seq, created_at from %2$s.report_revision o
                where not exists (select 1 from %1$s.report_revision n
                  where n.report_id = o.report_id and n.revision = o.revision and n.updated_seq >= o.updated_seq)
                on conflict (report_id, revision) do update set content = excluded.content,
                  content_hash = excluded.content_hash, updated_seq = excluded.updated_seq
                """.formatted(tenant.newSchema(), tenant.oldSchema()));
        total += jdbc.update("""
                insert into %1$s.object_store(object_key, object_type, content_hash, payload, updated_seq)
                select object_key, object_type, content_hash, payload, updated_seq from %2$s.object_store o
                where not exists (select 1 from %1$s.object_store n where n.object_key = o.object_key and n.updated_seq >= o.updated_seq)
                on conflict (object_key) do update set content_hash = excluded.content_hash,
                  payload = excluded.payload, updated_seq = excluded.updated_seq
                """.formatted(tenant.newSchema(), tenant.oldSchema()));
        if (migrationId != null) {
            refreshWatermarks(tenant, migrationId, false);
        }
        return total;
    }

    public long countUnreplicated(Tenant tenant) {
        Long s = jdbc.queryForObject("""
                select (select count(*) from %2$s.sample o where not exists
                          (select 1 from %1$s.sample n where n.sample_id = o.sample_id and n.updated_seq >= o.updated_seq))
                     + (select count(*) from %2$s.instrument_result o where not exists
                          (select 1 from %1$s.instrument_result n where n.result_id = o.result_id and n.updated_seq >= o.updated_seq))
                     + (select count(*) from %2$s.report_revision o where not exists
                          (select 1 from %1$s.report_revision n where n.report_id = o.report_id
                             and n.revision = o.revision and n.updated_seq >= o.updated_seq))
                """.formatted(tenant.newSchema(), tenant.oldSchema()), Long.class);
        return s == null ? 0 : s;
    }

    /** Applies one buffered fence message into NEW but NEVER into OLD: the fence only moves data forward. */
    public void applyBufferedToNew(Tenant tenant, UUID messageId, long seq, String messageType,
                                  JsonNode payload, String fenceId) {
        writer.apply(tenant, "NEW", messageId, seq, messageType, payload);
        jdbc.update("""
                update lab.intake_message set status = 'APPLIED_FROM_FENCE', applied_space = 'NEW'
                where message_id = ?::uuid and tenant_id = ?::uuid
                """, messageId.toString(), tenant.tenantId());
    }

    public void recordReportMeta(Tenant tenant, String messageType, JsonNode payload, long seq,
                                 String validationStatus) {
        if (!"REPORT_REVISION".equals(messageType)) {
            return;
        }
        String reportId = payload.path("reportId").asText();
        int revision = payload.path("revision").asInt(1);
        jdbc.update("""
                insert into lab.report_revision_meta(tenant_id, report_id, revision, validation_status, message_seq)
                values (?::uuid, ?, ?, ?, ?)
                on conflict (tenant_id, report_id, revision) do update set
                  validation_status = excluded.validation_status, message_seq = excluded.message_seq, updated_at = now()
                """, tenant.tenantId(), reportId, revision, validationStatus, seq);
    }

    // ---------- shadow comparison ----------

    /**
     * Compares OLD containment into NEW. REQUIRES_NEW so a failing comparison is still
     * recorded as evidence when the caller then aborts the routing change.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Map<String, Object> shadowCompare(Tenant tenant, String migrationId, String mode) {
        int compared = 0;
        int mismatched = 0;
        int newOnly = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        // The invariant compared is containment: every row OLD serves must exist in NEW with
        // identical content. Rows only in NEW are expected (fence messages are forward-only)
        // and are counted, not failed.
        List<Map<String, Object>> samples = jdbc.queryForList("""
                select o.sample_id, o.payload::text as o_payload, n.sample_id as n_id, n.payload::text as n_payload
                from %s.sample o left join %s.sample n on n.sample_id = o.sample_id
                """.formatted(tenant.oldSchema(), tenant.newSchema()));
        for (Map<String, Object> r : samples) {
            compared++;
            if (r.get("n_id") == null) {
                mismatched++;
                addDetail(details, "SAMPLE_MISSING_IN_NEW", r.get("sample_id"), null);
            } else if (!String.valueOf(r.get("o_payload")).equals(String.valueOf(r.get("n_payload")))) {
                mismatched++;
                addDetail(details, "SAMPLE_PAYLOAD_DIVERGED", r.get("sample_id"), null);
            }
        }

        List<Map<String, Object>> results = jdbc.queryForList("""
                select o.result_id, o.unit as o_unit, o.value_num::text as o_value,
                       n.result_id as n_id, n.unit as n_unit, n.value_num::text as n_value
                from %s.instrument_result o left join %s.instrument_result n on n.result_id = o.result_id
                """.formatted(tenant.oldSchema(), tenant.newSchema()));
        for (Map<String, Object> r : results) {
            compared++;
            if (r.get("n_id") == null) {
                mismatched++;
                addDetail(details, "RESULT_MISSING_IN_NEW", r.get("result_id"), null);
            } else if (!String.valueOf(r.get("o_unit")).equals(String.valueOf(r.get("n_unit")))
                    || !String.valueOf(r.get("o_value")).equals(String.valueOf(r.get("n_value")))) {
                mismatched++;
                addDetail(details, "RESULT_VALUE_OR_UNIT_DIVERGED", r.get("result_id"),
                        "old=" + r.get("o_value") + " " + r.get("o_unit")
                                + " new=" + r.get("n_value") + " " + r.get("n_unit"));
            }
        }

        List<Map<String, Object>> reports = jdbc.queryForList("""
                select o.report_id, o.revision, o.content_hash as o_hash, n.report_id as n_id, n.content_hash as n_hash
                from %s.report_revision o
                left join %s.report_revision n on n.report_id = o.report_id and n.revision = o.revision
                """.formatted(tenant.oldSchema(), tenant.newSchema()));
        for (Map<String, Object> r : reports) {
            compared++;
            if (r.get("n_id") == null) {
                mismatched++;
                addDetail(details, "REPORT_REVISION_MISSING_IN_NEW", r.get("report_id") + "#" + r.get("revision"), null);
            } else if (!String.valueOf(r.get("o_hash")).equals(String.valueOf(r.get("n_hash")))) {
                mismatched++;
                addDetail(details, "REPORT_CONTENT_HASH_DIVERGED", r.get("report_id") + "#" + r.get("revision"), null);
            }
        }

        newOnly = newOnlyCount(tenant);

        String result = mismatched == 0 ? "PASS" : "FAIL";
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("newOnlyRows", newOnly);
        summary.put("details", details);
        jdbc.update("""
                insert into lab.shadow_comparison(comparison_id, migration_id, tenant_id, mode,
                  compared_rows, mismatched_rows, result, details)
                values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID().toString(), migrationId, tenant.tenantId(), mode,
                compared, mismatched, result, crypto.canonicalJson(summary));
        evidence.append(tenant.tenantId(), "SHADOW_COMPARISON", "MIGRATION", migrationId,
                "NEW", "PASS".equals(result) ? "VERIFIED" : "PENDING_VERIFICATION",
                Map.of("mode", mode, "comparedRows", compared, "mismatchedRows", mismatched,
                        "newOnlyRows", newOnly, "result", result));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        out.put("mode", mode);
        out.put("comparedRows", compared);
        out.put("mismatchedRows", mismatched);
        out.put("newOnlyRows", newOnly);
        out.put("details", details);
        return out;
    }

    private int newOnlyCount(Tenant tenant) {
        Long n = jdbc.queryForObject("""
                select (select count(*) from %1$s.sample n where not exists
                          (select 1 from %2$s.sample o where o.sample_id = n.sample_id))
                     + (select count(*) from %1$s.instrument_result n where not exists
                          (select 1 from %2$s.instrument_result o where o.result_id = n.result_id))
                     + (select count(*) from %1$s.report_revision n where not exists
                          (select 1 from %2$s.report_revision o where o.report_id = n.report_id
                             and o.revision = n.revision))
                """.formatted(tenant.newSchema(), tenant.oldSchema()), Long.class);
        return n == null ? 0 : n.intValue();
    }

    private void addDetail(List<Map<String, Object>> details, String code, Object entity, Object extra) {
        if (details.size() >= 50) {
            return;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", code);
        d.put("entity", entity);
        if (extra != null) {
            d.put("detail", extra);
        }
        details.add(d);
    }

    public List<Map<String, Object>> comparisons(String tenantId) {
        return jdbc.queryForList("""
                select comparison_id::text as "comparisonId", mode, compared_rows as "comparedRows",
                       mismatched_rows as "mismatchedRows", result, details, created_at as "createdAt"
                from lab.shadow_comparison where tenant_id = ?::uuid order by created_at desc limit 20
                """, tenantId);
    }
}
