package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labroute.model.Tenant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Writes one validated intake message into one physical tenant space.
 * Every write is idempotent: the message_id is recorded in <space>.applied_message,
 * and all entity upserts are keyed by business primary key with a monotonic updated_seq.
 * Re-applying the same message (double-write, replay drain, or a retried drain) is a no-op.
 */
@Service
public class SpaceWriter {

    private final JdbcTemplate jdbc;
    private final CryptoSupport crypto;

    public SpaceWriter(JdbcTemplate jdbc, CryptoSupport crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public String schema(Tenant t, String role) {
        return "OLD".equals(role) ? t.oldSchema() : t.newSchema();
    }

    public boolean alreadyApplied(String schema, UUID messageId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from " + schema + ".applied_message where message_id = ?::uuid",
                Integer.class, messageId.toString());
        return n != null && n > 0;
    }

    public void apply(Tenant tenant, String role, UUID messageId, long seq, String messageType, JsonNode payload) {
        String schema = schema(tenant, role);
        if (alreadyApplied(schema, messageId)) {
            return;
        }
        switch (messageType) {
            case "SAMPLE" -> applySample(schema, messageId, seq, payload);
            case "RESULT" -> applyResult(schema, messageId, seq, payload);
            case "REPORT_REVISION" -> applyReport(schema, messageId, seq, payload);
            case "OBJECT" -> applyObject(schema, messageId, seq, payload);
            default -> throw new IllegalArgumentException("unroutable message type " + messageType);
        }
        jdbc.update("""
                insert into %s.applied_message(message_id, seq) values (?::uuid, ?)
                on conflict (message_id) do nothing
                """.formatted(schema), messageId.toString(), seq);
    }

    private void applySample(String schema, UUID messageId, long seq, JsonNode p) {
        jdbc.update("""
                insert into %1$s.sample(sample_id, chain_version, parent_sample_id, source_code,
                  patient_ref_masked, collected_at, status, payload, origin_message_id, updated_seq, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::uuid, ?, now())
                on conflict (sample_id) do update set
                  chain_version = excluded.chain_version,
                  parent_sample_id = coalesce(excluded.parent_sample_id, %1$s.sample.parent_sample_id),
                  source_code = excluded.source_code,
                  patient_ref_masked = coalesce(excluded.patient_ref_masked, %1$s.sample.patient_ref_masked),
                  collected_at = coalesce(excluded.collected_at, %1$s.sample.collected_at),
                  status = coalesce(excluded.status, %1$s.sample.status),
                  payload = excluded.payload,
                  origin_message_id = excluded.origin_message_id,
                  updated_seq = excluded.updated_seq,
                  updated_at = now()
                where %1$s.sample.updated_seq < excluded.updated_seq
                """.formatted(schema),
                p.path("sampleId").asText(), p.path("chainVersion").asInt(1),
                text(p, "parentSampleId"), text(p, "sourceCode"),
                text(p, "patientRefMasked"), timestamp(p, "collectedAt"), text(p, "status"),
                crypto.canonicalJson(p), messageId.toString(), seq);
    }

    private void applyResult(String schema, UUID messageId, long seq, JsonNode p) {
        jdbc.update("""
                insert into %1$s.instrument_result(result_id, sample_id, instrument_code, analyte_code,
                  value_num, unit, ref_low, ref_high, measured_at, received_at, status, payload,
                  origin_message_id, updated_seq, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::uuid, ?, now())
                on conflict (result_id) do update set
                  sample_id = excluded.sample_id,
                  instrument_code = coalesce(excluded.instrument_code, %1$s.instrument_result.instrument_code),
                  analyte_code = excluded.analyte_code,
                  value_num = excluded.value_num,
                  unit = excluded.unit,
                  ref_low = coalesce(excluded.ref_low, %1$s.instrument_result.ref_low),
                  ref_high = coalesce(excluded.ref_high, %1$s.instrument_result.ref_high),
                  measured_at = coalesce(excluded.measured_at, %1$s.instrument_result.measured_at),
                  received_at = excluded.received_at,
                  status = excluded.status,
                  payload = excluded.payload,
                  origin_message_id = excluded.origin_message_id,
                  updated_seq = excluded.updated_seq,
                  updated_at = now()
                where %1$s.instrument_result.updated_seq < excluded.updated_seq
                """.formatted(schema),
                p.path("resultId").asText(), p.path("sampleId").asText(),
                text(p, "instrumentCode"), text(p, "analyteCode"),
                decimal(p, "value"), text(p, "unit"), decimal(p, "refLow"), decimal(p, "refHigh"),
                timestamp(p, "measuredAt"), timestamp(p, "receivedAt"),
                p.path("status").asText("RECEIVED"), crypto.canonicalJson(p), messageId.toString(), seq);
    }

    private void applyReport(String schema, UUID messageId, long seq, JsonNode p) {
        String content = crypto.canonicalJson(p.has("content") ? p.get("content") : p);
        String hash = crypto.sha256Hex(content);
        jdbc.update("""
                insert into %1$s.report_revision(report_id, revision, sample_id, content, content_hash,
                  source_space, origin_message_id, updated_seq)
                values (?, ?, ?, ?::jsonb, ?, null, ?::uuid, ?)
                on conflict (report_id, revision) do update set
                  sample_id = excluded.sample_id,
                  content = excluded.content,
                  content_hash = excluded.content_hash,
                  origin_message_id = excluded.origin_message_id,
                  updated_seq = excluded.updated_seq
                where %1$s.report_revision.updated_seq < excluded.updated_seq
                """.formatted(schema),
                p.path("reportId").asText(), p.path("revision").asInt(1),
                p.path("sampleId").asText(), content, hash, messageId.toString(), seq);
    }

    private void applyObject(String schema, UUID messageId, long seq, JsonNode p) {
        String content = crypto.canonicalJson(p.has("content") ? p.get("content") : p);
        jdbc.update("""
                insert into %1$s.object_store(object_key, object_type, content_hash, payload, updated_seq, updated_at)
                values (?, ?, ?, ?::jsonb, ?, now())
                on conflict (object_key) do update set
                  object_type = excluded.object_type,
                  content_hash = excluded.content_hash,
                  payload = excluded.payload,
                  updated_seq = excluded.updated_seq,
                  updated_at = now()
                where %1$s.object_store.updated_seq < excluded.updated_seq
                """.formatted(schema),
                p.path("objectKey").asText(), p.path("objectType").asText("UNKNOWN"),
                crypto.sha256Hex(content), crypto.canonicalJson(p), seq);
    }

    private static String text(JsonNode p, String field) {
        JsonNode v = p.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static BigDecimal decimal(JsonNode p, String field) {
        JsonNode v = p.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static OffsetDateTime timestamp(JsonNode p, String field) {
        JsonNode v = p.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v.asText());
        } catch (Exception e) {
            return null;
        }
    }

    public void recordBoundary(String tenantId, UUID messageId, long seq, String type, JsonNode payload,
                               String sourceCode, String keyId, String scope, String payloadHash,
                               String signature, String spaceRole) {
        if (!"SAMPLE".equals(type)) {
            return;
        }
        try {
            jdbc.update("""
                    insert into lab.boundary_book_entry(entry_id, tenant_id, sample_id, chain_version,
                      entry_type, source_code, key_id, authorization_scope, payload_hash, signature,
                      message_id, message_seq, space_role)
                    values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?, ?)
                    on conflict (tenant_id, message_id) do nothing
                    """, UUID.randomUUID().toString(), tenantId, payload.path("sampleId").asText(),
                    payload.path("chainVersion").asInt(1), type, sourceCode, keyId, scope,
                    payloadHash, signature, messageId.toString(), seq, spaceRole);
        } catch (DuplicateKeyException ignored) {
            // already recorded for this message
        }
    }
}
