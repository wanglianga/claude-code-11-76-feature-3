package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.labroute.model.Tenant;
import com.labroute.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The routing gate and migration state machine.
 *
 * FENCE_REPLAY (per-tenant replayable buffer + one-shot gate):
 *   STEADY_OLD -> FENCED (write=FENCE, read=OLD) -> DRAINING -> RELEASE (write=NEW, read=NEW)
 *   Intake never stops: messages inside the fence are persisted in tenant-scoped order and
 *   drained in that order into NEW before the gate is opened. Reports whose revisions sat in
 *   the fence are published as PENDING_VERIFICATION until the drain is applied.
 *
 * WATERMARK_ONLINE (online incremental copy with watermarks, staged read/write routing):
 *   STEADY_OLD -> DUAL_WRITE (write=DUAL, read=OLD) -> SHADOW_CHECK -> READ_NEW (write=DUAL)
 *   -> STEADY_NEW (write=NEW). Rollback re-points reads to OLD and restores writes to DUAL/OLD.
 *
 * Both routes share the invariant: read and write space are always a single explicit value,
 * never "try old then new". The gate is the only place that flips them.
 */
@Service
public class MigrationService {

    private final JdbcTemplate jdbc;
    private final TenantService tenants;
    private final EvidenceService evidence;
    private final ReplicationService replication;
    private final CryptoSupport crypto;

    public MigrationService(JdbcTemplate jdbc, TenantService tenants, EvidenceService evidence,
                            ReplicationService replication, CryptoSupport crypto) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.evidence = evidence;
        this.replication = replication;
        this.crypto = crypto;
    }

    public static final List<String> FENCE_PHASES = List.of("STEADY_OLD", "FENCED", "DRAINING", "STEADY_NEW");
    public static final List<String> ONLINE_PHASES = List.of("STEADY_OLD", "DUAL_WRITE", "SHADOW_CHECK",
            "READ_NEW", "STEADY_NEW");

    public record MigrationRow(String migrationId, String tenantId, String strategy, String state,
                               String sourceSpace, String targetSpace, Instant startedAt, Instant fencedAt,
                               Instant cutoverAt, Instant completedAt, Instant rolledBackAt, long watermarkSeq,
                               String rollbackReason) {
    }

    private final org.springframework.jdbc.core.RowMapper<MigrationRow> mMapper = (rs, i) -> new MigrationRow(
            rs.getString("migration_id"), rs.getString("tenant_id"), rs.getString("strategy"),
            rs.getString("state"), rs.getString("source_space"), rs.getString("target_space"),
            instant(rs, "started_at"), instant(rs, "fenced_at"), instant(rs, "cutover_at"),
            instant(rs, "completed_at"), instant(rs, "rolled_back_at"), rs.getLong("watermark_seq"),
            rs.getString("rollback_reason"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    public List<MigrationRow> list(String tenantId) {
        return jdbc.query("select * from lab.migration where tenant_id = ?::uuid order by started_at desc",
                mMapper, tenantId);
    }

    public MigrationRow active(String tenantId) {
        List<MigrationRow> rows = jdbc.query("""
                select * from lab.migration where tenant_id = ?::uuid
                  and state not in ('COMPLETED','ROLLED_BACK','FAILED') order by started_at desc limit 1
                """, mMapper, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public MigrationRow requireMigration(String tenantId) {
        MigrationRow row = active(tenantId);
        if (row == null) {
            throw ApiException.conflict("NO_ACTIVE_MIGRATION",
                    "tenant has no active migration; start one before fencing or routing", Map.of());
        }
        return row;
    }

    @Transactional
    public Map<String, Object> start(Tenant tenant, String requestedStrategy) {
        if (active(tenant.tenantId()) != null) {
            throw ApiException.conflict("MIGRATION_ALREADY_ACTIVE",
                    "an active migration already exists for this tenant", Map.of());
        }
        String strategy = requestedStrategy == null ? tenant.strategy() : requestedStrategy;
        if (!List.of("FENCE_REPLAY", "WATERMARK_ONLINE").contains(strategy)) {
            throw ApiException.badRequest("STRATEGY_INVALID", "unknown strategy " + strategy);
        }
        String migrationId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into lab.migration(migration_id, tenant_id, strategy, state)
                values (?::uuid, ?::uuid, ?, 'STEADY')
                """, migrationId, tenant.tenantId(), strategy);
        tenants.setStrategy(tenant.tenantId(), strategy);
        replication.seedNewFromOld(tenant);
        evidence.append(tenant.tenantId(), "MIGRATION_STARTED", "MIGRATION", migrationId,
                tenant.readSpace(), "VERIFIED", Map.of("strategy", strategy,
                        "initialCopy", replication.initialCopyStats(tenant)));
        return get(tenant.tenantId(), migrationId);
    }

    public Map<String, Object> get(String tenantId, String migrationId) {
        List<MigrationRow> rows = jdbc.query("select * from lab.migration where migration_id = ?::uuid",
                mMapper, migrationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("MIGRATION_NOT_FOUND", "unknown migration");
        }
        MigrationRow row = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("migrationId", row.migrationId());
        out.put("tenantId", row.tenantId());
        out.put("strategy", row.strategy());
        out.put("state", row.state());
        out.put("sourceSpace", row.sourceSpace());
        out.put("targetSpace", row.targetSpace());
        out.put("startedAt", row.startedAt());
        out.put("fencedAt", row.fencedAt());
        out.put("cutoverAt", row.cutoverAt());
        out.put("completedAt", row.completedAt());
        out.put("rolledBackAt", row.rolledBackAt());
        out.put("watermarkSeq", row.watermarkSeq());
        out.put("rollbackReason", row.rollbackReason());
        out.put("watermarks", replication.watermarks(migrationId));
        out.put("fence", currentFence(tenantId).orElse(null));
        out.put("shadowComparisons", jdbc.queryForList(
                "select comparison_id::text, mode, compared_rows, mismatched_rows, result, created_at " +
                        "from lab.shadow_comparison where migration_id = ?::uuid order by created_at desc limit 10",
                migrationId));
        return out;
    }

    // ---------- FENCE_REPLAY ----------

    @Transactional
    public Map<String, Object> openFence(Tenant tenant) {
        MigrationRow m = requireStrategy(tenant, "FENCE_REPLAY");
        if (!"STEADY_OLD".equals(tenant.phase())) {
            throw ApiException.conflict("FENCE_STATE_INVALID",
                    "fence can only open from STEADY_OLD, current phase is " + tenant.phase(), Map.of());
        }
        if (currentFence(tenant.tenantId()).isPresent()) {
            throw ApiException.conflict("FENCE_ALREADY_OPEN", "a fence is already open", Map.of());
        }
        String fenceId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into lab.fence(fence_id, migration_id, tenant_id, state)
                values (?::uuid, ?::uuid, ?::uuid, 'OPEN')
                """, fenceId, m.migrationId(), tenant.tenantId());
        jdbc.update("update lab.migration set state = 'FENCED', fenced_at = now() where migration_id = ?::uuid",
                m.migrationId());
        transition(tenant, "FENCED", "OLD", "FENCE", "FENCE_OPENED",
                Map.of("fenceId", fenceId, "note", "writes buffered, reads still served from OLD"));
        appendEvidence(tenant, "FENCE_OPENED", "FENCE", fenceId, "OLD", "VERIFIED",
                Map.of("migrationId", m.migrationId(), "readSpace", "OLD", "writeSpace", "FENCE"));
        return currentFence(tenant.tenantId()).orElseThrow();
    }

    @Transactional
    public Map<String, Object> drainFence(Tenant tenant) {
        MigrationRow m = requireStrategy(tenant, "FENCE_REPLAY");
        Map<String, Object> fence = currentFence(tenant.tenantId()).orElseThrow(() ->
                ApiException.conflict("NO_OPEN_FENCE", "no fence is open", Map.of()));
        String fenceId = (String) fence.get("fenceId");
        Tenant fresh = tenants.requireById(tenant.tenantId());
        if (!"FENCED".equals(fresh.phase()) && !"DRAINING".equals(fresh.phase())) {
            throw ApiException.conflict("FENCE_STATE_INVALID", "fence is not drainable in phase " + fresh.phase(), Map.of());
        }
        jdbc.update("update lab.fence set state = 'DRAINING' where fence_id = ?::uuid", fenceId);
        if (!"DRAINING".equals(fresh.phase())) {
            transition(fresh, "DRAINING", "OLD", "FENCE", "FENCE_DRAINING",
                    Map.of("fenceId", fenceId, "note", "buffer draining in seq order into NEW"));
        }

        // Freeze the high-water mark at drain start: messages arriving after this point
        // keep buffering and will be picked up by a subsequent drain pass.
        Long highSeq = jdbc.queryForObject(
                "select coalesce(max(seq),0) from lab.intake_message where tenant_id = ?::uuid and fence_id = ?::uuid",
                Long.class, tenant.tenantId(), fenceId);
        long high = highSeq == null ? 0 : highSeq;
        List<Map<String, Object>> pending = jdbc.queryForList("""
                select b.buffer_id::text, b.seq, b.message_id::text, b.message_type, b.natural_key, b.payload::text
                from lab.intake_buffer b
                where b.fence_id = ?::uuid and b.status = 'BUFFERED' and b.seq <= ?
                order by b.seq
                """, fenceId, high);
        int applied = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map<String, Object> row : pending) {
            long seq = ((Number) row.get("seq")).longValue();
            UUID messageId = UUID.fromString((String) row.get("message_id"));
            try {
                replication.applyBufferedToNew(fresh, messageId, seq,
                        (String) row.get("message_type"), crypto.readTree((String) row.get("payload")), fenceId);
                jdbc.update("""
                        update lab.intake_buffer set status = 'APPLIED', applied_space = 'NEW', applied_at = now()
                        where buffer_id = ?::uuid
                        """, row.get("buffer_id"));
                applied++;
            } catch (Exception ex) {
                jdbc.update("""
                        update lab.intake_buffer set status = 'FAILED', apply_error = ? where buffer_id = ?::uuid
                        """, ex.getMessage(), row.get("buffer_id"));
                errors.add(Map.of("seq", seq, "messageId", messageId.toString(), "error", String.valueOf(ex.getMessage())));
            }
        }
        Map<String, Object> counts = fenceCounts(fenceId);
        jdbc.update("""
                update lab.fence set buffered_count = ?, applied_count = ?, high_seq = greatest(coalesce(high_seq,0), ?)
                where fence_id = ?::uuid
                """, counts.get("buffered"), counts.get("applied"), high, fenceId);
        appendEvidence(tenant, "FENCE_DRAIN_PASS", "FENCE", fenceId, "NEW", "VERIFIED",
                Map.of("highSeq", high, "applied", applied, "errors", errors.size(),
                        "counts", counts, "order", "tenant-scoped seq ascending"));
        Map<String, Object> out = new LinkedHashMap<>(currentFence(tenant.tenantId()).orElseThrow());
        out.put("appliedThisPass", applied);
        out.put("errors", errors);
        out.put("highSeq", high);
        return out;
    }

    @Transactional
    public Map<String, Object> releaseFence(Tenant tenant) {
        MigrationRow m = requireStrategy(tenant, "FENCE_REPLAY");
        Map<String, Object> fence = currentFence(tenant.tenantId()).orElseThrow(() ->
                ApiException.conflict("NO_OPEN_FENCE", "no fence is open", Map.of()));
        String fenceId = (String) fence.get("fenceId");
        Map<String, Object> counts = fenceCounts(fenceId);
        long buffered = (Long) counts.get("buffered");
        if (buffered > 0) {
            throw ApiException.conflict("FENCE_NOT_DRAINED",
                    "fence still holds " + buffered + " buffered message(s); drain before releasing",
                    Map.of("pending", buffered, "counts", counts));
        }
        // Copy anything that existed in OLD but was never dual-written, using the watermark.
        replication.replicateDeltas(tenant, m.migrationId());
        replication.refreshWatermarks(tenant, m.migrationId(), true);
        Map<String, Object> comparison = replication.shadowCompare(tenant, m.migrationId(), "RELEASE_PREFLIGHT");
        if (!"PASS".equals(comparison.get("result"))) {
            throw ApiException.conflict("RELEASE_PREFLIGHT_FAILED",
                    "shadow comparison failed; gate stays on OLD", Map.of("comparison", comparison));
        }
        jdbc.update("""
                update lab.fence set state = 'RELEASED', released_at = now() where fence_id = ?::uuid
                """, fenceId);
        jdbc.update("""
                update lab.migration set state = 'COMPLETED', cutover_at = now(), completed_at = now()
                where migration_id = ?::uuid
                """, m.migrationId());
        transition(tenant, "STEADY_NEW", "NEW", "NEW", "ROUTE_GATE_RELEASED",
                Map.of("fenceId", fenceId, "comparison", comparison));
        appendEvidence(tenant, "ROUTE_GATE_RELEASED", "MIGRATION", m.migrationId(), "NEW", "VERIFIED",
                Map.of("fenceId", fenceId, "readSpace", "NEW", "writeSpace", "NEW",
                        "comparison", comparison.get("result")));
        return get(tenant.tenantId(), m.migrationId());
    }

    // ---------- WATERMARK_ONLINE ----------

    @Transactional
    public Map<String, Object> startDualWrite(Tenant tenant) {
        MigrationRow m = requireStrategy(tenant, "WATERMARK_ONLINE");
        if (!"STEADY_OLD".equals(tenant.phase())) {
            throw ApiException.conflict("ROUTE_STATE_INVALID",
                    "dual write can only start from STEADY_OLD, current phase " + tenant.phase(), Map.of());
        }
        jdbc.update("update lab.migration set state = 'DUAL_WRITE' where migration_id = ?::uuid", m.migrationId());
        transition(tenant, "DUAL_WRITE", "OLD", "DUAL", "DUAL_WRITE_ENABLED",
                Map.of("note", "intake writes OLD and NEW idempotently; reads still on OLD"));
        replication.refreshWatermarks(tenant, m.migrationId(), false);
        return get(tenant.tenantId(), m.migrationId());
    }

    @Transactional
    public Map<String, Object> shadowCheck(Tenant tenant) {
        MigrationRow m = requireStrategy(tenant, "WATERMARK_ONLINE");
        if (!List.of("DUAL_WRITE", "SHADOW_CHECK").contains(tenant.phase())) {
            throw ApiException.conflict("ROUTE_STATE_INVALID",
                    "shadow check requires DUAL_WRITE, current phase " + tenant.phase(), Map.of());
        }
        replication.replicateDeltas(tenant, m.migrationId());
        replication.refreshWatermarks(tenant, m.migrationId(), false);
        Map<String, Object> comparison = replication.shadowCompare(tenant, m.migrationId(), "SHADOW_CHECK");
        String nextState = "PASS".equals(comparison.get("result")) ? "SHADOW_CHECK" : "DUAL_WRITE";
        jdbc.update("update lab.migration set state = ? where migration_id = ?::uuid", nextState, m.migrationId());
        if (!"SHADOW_CHECK".equals(tenant.phase())) {
            transition(tenant, "SHADOW_CHECK", "OLD", "DUAL", "SHADOW_CHECKED", Map.of("comparison", comparison));
        }
        return get(tenant.tenantId(), m.migrationId());
    }

    @Transactional
    public Map<String, Object> switchRead(Tenant tenant) {
        MigrationRow m = requireStrategy(tenant, "WATERMARK_ONLINE");
        if (!"SHADOW_CHECK".equals(tenant.phase())) {
            throw ApiException.conflict("ROUTE_STATE_INVALID",
                    "read cutover requires a passing SHADOW_CHECK phase, current phase " + tenant.phase(), Map.of());
        }
        // Bring replication up to date before checking lag: the gate must refuse only on a
        // genuine backlog, not on deltas that a single catch-up pass would clear.
        replication.replicateDeltas(tenant, m.migrationId());
        replication.refreshWatermarks(tenant, m.migrationId(), false);
        Map<String, Object> lag = replication.watermarkSummary(m.migrationId());
        long maxLag = ((Number) lag.getOrDefault("maxLag", 0L)).longValue();
        if (maxLag > 0) {
            throw ApiException.conflict("WATERMARK_LAG",
                    "cannot cut reads over while replication lag is non-zero",
                    Map.of("maxLag", maxLag, "watermarks", replication.watermarks(m.migrationId())));
        }
        jdbc.update("update lab.migration set state = 'READ_NEW', cutover_at = now() where migration_id = ?::uuid",
                m.migrationId());
        transition(tenant, "READ_NEW", "NEW", "DUAL", "READ_CUTOVER",
                Map.of("note", "reads now served from NEW; writes still dual until write cutover"));
        appendEvidence(tenant, "READ_CUTOVER", "MIGRATION", m.migrationId(), "NEW", "VERIFIED",
                Map.of("readSpace", "NEW", "writeSpace", "DUAL", "maxLag", maxLag));
        return get(tenant.tenantId(), m.migrationId());
    }

    @Transactional
    public Map<String, Object> switchWrite(Tenant tenant) {
        MigrationRow m = requireStrategy(tenant, "WATERMARK_ONLINE");
        if (!"READ_NEW".equals(tenant.phase())) {
            throw ApiException.conflict("ROUTE_STATE_INVALID",
                    "write cutover requires READ_NEW phase, current phase " + tenant.phase(), Map.of());
        }
        replication.replicateDeltas(tenant, m.migrationId());
        long pending = replication.countUnreplicated(tenant);
        if (pending > 0) {
            throw ApiException.conflict("REPLICATION_PENDING",
                    "cannot stop dual-write while OLD has " + pending + " rows not yet verified in NEW",
                    Map.of("pendingRows", pending));
        }
        Map<String, Object> comparison = replication.shadowCompare(tenant, m.migrationId(), "WRITE_CUTOVER");
        if (!"PASS".equals(comparison.get("result"))) {
            throw ApiException.conflict("WRITE_CUTOVER_COMPARISON_FAILED",
                    "shadow comparison failed at write cutover", Map.of("comparison", comparison));
        }
        jdbc.update("""
                update lab.migration set state = 'COMPLETED', completed_at = now() where migration_id = ?::uuid
                """, m.migrationId());
        transition(tenant, "STEADY_NEW", "NEW", "NEW", "WRITE_CUTOVER",
                Map.of("comparison", comparison));
        return get(tenant.tenantId(), m.migrationId());
    }

    // ---------- ROLLBACK ----------

    @Transactional
    public Map<String, Object> rollback(Tenant tenant, String reason) {
        // Rollback applies to the in-flight migration, or the most recently completed one
        // (an operator may still need to retreat after the gate opened).
        MigrationRow m = active(tenant.tenantId());
        if (m == null) {
            List<MigrationRow> history = list(tenant.tenantId());
            m = history.isEmpty() ? null : history.get(0);
        }
        if (m == null) {
            throw ApiException.conflict("NO_MIGRATION",
                    "tenant has no migration to roll back", Map.of());
        }
        String fromRead = tenant.readSpace();
        String fromPhase = tenant.phase();
        List<Map<String, Object>> marks = replication.watermarks(m.migrationId());
        List<Map<String, Object>> affected = jdbc.queryForList("""
                select report_id, revision, visible_state, source_space
                from lab.report_publication where tenant_id = ?::uuid order by report_id, revision
                """, tenant.tenantId());
        String rollbackId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into lab.rollback_record(rollback_id, migration_id, tenant_id, reason,
                  from_read_space, to_read_space, from_phase, to_phase, watermark_snapshot, affected_reports)
                values (?::uuid, ?::uuid, ?::uuid, ?, ?, 'OLD', ?, 'STEADY_OLD', ?::jsonb, ?::jsonb)
                """, rollbackId, m.migrationId(), tenant.tenantId(),
                reason == null ? "operator requested" : reason, fromRead, fromPhase,
                crypto.canonicalJson(marks), crypto.canonicalJson(affected));
        jdbc.update("""
                update lab.migration set state = 'ROLLED_BACK', rolled_back_at = now(),
                  rollback_reason = ?, rollback_id = ?::uuid where migration_id = ?::uuid
                """, reason, rollbackId, m.migrationId());
        jdbc.update("""
                update lab.fence set state = 'RELEASED', released_at = now() where tenant_id = ?::uuid
                  and state in ('OPEN','DRAINING')
                """, tenant.tenantId());
        transition(tenant, "STEADY_OLD", "OLD", "OLD", "ROLLED_BACK",
                Map.of("rollbackId", rollbackId, "reason", String.valueOf(reason),
                        "affectedReports", affected.size()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rollbackId", rollbackId);
        payload.put("reason", reason);
        payload.put("fromReadSpace", fromRead);
        payload.put("toReadSpace", "OLD");
        payload.put("watermarkSnapshot", marks);
        payload.put("affectedReports", affected.size());
        var ev = evidence.append(tenant.tenantId(), "ROLLBACK", "MIGRATION", m.migrationId(),
                "OLD", "VERIFIED", payload);
        jdbc.update("update lab.rollback_record set evidence_id = ?::uuid where rollback_id = ?::uuid",
                ev.evidenceId(), rollbackId);
        Map<String, Object> out = new LinkedHashMap<>(get(tenant.tenantId(), m.migrationId()));
        out.put("rollbackId", rollbackId);
        out.put("toReadSpace", "OLD");
        out.put("toWriteSpace", "OLD");
        out.put("evidenceId", ev.evidenceId());
        return out;
    }

    // ---------- shared ----------

    public java.util.Optional<Map<String, Object>> currentFence(String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select fence_id::text as "fenceId", migration_id::text as "migrationId", state,
                       opened_at as "openedAt", released_at as "releasedAt", high_seq as "highSeq",
                       buffered_count as "bufferedCount", applied_count as "appliedCount", notes
                from lab.fence where tenant_id = ?::uuid and state in ('OPEN','DRAINING')
                order by opened_at desc limit 1
                """, tenantId);
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> counts = fenceCounts((String) row.get("fenceId"));
        row.put("counts", counts);
        row.put("pending", counts.get("buffered"));
        return java.util.Optional.of(row);
    }

    public Map<String, Object> fenceCounts(String fenceId) {
        Map<String, Object> m = jdbc.queryForMap("""
                select count(*) filter (where status = 'BUFFERED') as buffered,
                       count(*) filter (where status = 'APPLIED') as applied,
                       count(*) filter (where status = 'FAILED') as failed,
                       count(*) as total
                from lab.intake_buffer where fence_id = ?::uuid
                """, fenceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buffered", ((Number) m.get("buffered")).longValue());
        out.put("applied", ((Number) m.get("applied")).longValue());
        out.put("failed", ((Number) m.get("failed")).longValue());
        out.put("total", ((Number) m.get("total")).longValue());
        return out;
    }

    private MigrationRow requireStrategy(Tenant tenant, String strategy) {
        MigrationRow m = requireMigration(tenant.tenantId());
        if (!strategy.equals(m.strategy())) {
            throw ApiException.conflict("STRATEGY_MISMATCH",
                    "active migration uses " + m.strategy() + ", this operation is for " + strategy, Map.of());
        }
        return m;
    }

    /**
     * The single place that flips routing. A generation bump makes stale in-flight
     * decisions detectable by the intake path.
     */
    public void transition(Tenant tenant, String phase, String readSpace, String writeSpace,
                           String eventType, Map<String, Object> payload) {
        jdbc.update("""
                update lab.tenant set phase = ?, read_space = ?, write_space = ?,
                  generation = generation + 1, updated_at = now() where tenant_id = ?::uuid
                """, phase, readSpace, writeSpace, tenant.tenantId());
    }

    private void appendEvidence(Tenant tenant, String eventType, String entityType, String entityId,
                                String sourceSpace, String visibleState, Map<String, Object> payload) {
        evidence.append(tenant.tenantId(), eventType, entityType, entityId, sourceSpace, visibleState, payload);
    }

    public Map<String, Object> routingState(Tenant tenant) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant.code());
        out.put("phase", tenant.phase());
        out.put("strategy", tenant.strategy());
        out.put("generation", tenant.generation());
        out.put("readSpace", tenant.readSpace());
        out.put("writeSpace", tenant.writeSpace());
        out.put("isolationLevel", tenant.isolationLevel());
        out.put("policyVersion", tenant.policyVersion());
        out.put("fence", currentFence(tenant.tenantId()).orElse(null));
        out.put("migration", active(tenant.tenantId()));
        out.put("spaces", Map.of("OLD", tenant.oldSchema(), "NEW", tenant.newSchema()));
        return out;
    }

    public JsonNode fenceTimeline(String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select b.seq, b.message_type, b.natural_key, b.status, b.buffered_at, b.applied_at, b.applied_space
                from lab.intake_buffer b join lab.fence f on f.fence_id = b.fence_id
                where f.tenant_id = ?::uuid order by b.buffered_at desc, b.seq desc limit 200
                """, tenantId);
        return crypto.mapper().valueToTree(rows);
    }
}
