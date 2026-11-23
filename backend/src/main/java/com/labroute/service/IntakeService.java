package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.labroute.model.Tenant;
import com.labroute.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The sample-receipt gateway. It is never closed during migration.
 *
 * Contract:
 *  - Every accepted message is persisted (intake_message) before the HTTP response is
 *    returned, so a receipt always means "durably held", not "queued in memory".
 *  - Ordering is per tenant: a single monotonic seq allocated under a row lock on
 *    lab.intake_counter. Fence drain replays exactly that order.
 *  - Routing is a single explicit decision from tenant.write_space. There is no
 *    "try OLD then NEW" path, so a mixed read/write can never be produced by intake.
 *  - A message that fails semantic validation is still persisted and receipted, with
 *    status REJECTED. Losing the input is never an acceptable response.
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final JdbcTemplate jdbc;
    private final TenantService tenants;
    private final AuthService auth;
    private final CryptoSupport crypto;
    private final SemanticValidator validator;
    private final SpaceWriter writer;
    private final EvidenceService evidence;
    private final ReplicationService replication;

    public IntakeService(JdbcTemplate jdbc, TenantService tenants, AuthService auth, CryptoSupport crypto,
                         SemanticValidator validator, SpaceWriter writer, EvidenceService evidence,
                         ReplicationService replication) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.auth = auth;
        this.crypto = crypto;
        this.validator = validator;
        this.writer = writer;
        this.evidence = evidence;
        this.replication = replication;
    }

    public record IntakeRequest(String tenantCode, String endpoint, String messageType,
                                String sourceCode, String keyId, String nonce, String signedAt,
                                String signature, String signatureHeader, String idempotencyKey,
                                String rawBody) {
    }

    private static final Map<String, String> TYPE_TO_PATH = Map.of(
            "SAMPLE", "/api/intake/sample",
            "RESULT", "/api/intake/result",
            "REPORT_REVISION", "/api/intake/report",
            "OBJECT", "/api/intake/object"
    );

    private static final Map<String, String> PATH_TO_TYPE = Map.of(
            "/api/intake/sample", "SAMPLE",
            "/api/intake/result", "RESULT",
            "/api/intake/report", "REPORT_REVISION",
            "/api/intake/object", "OBJECT"
    );

    public static String pathForType(String type) {
        return TYPE_TO_PATH.getOrDefault(type, "/api/intake/unknown");
    }

    public static String typeForPath(String path) {
        return PATH_TO_TYPE.get(path);
    }

    @Transactional
    public Map<String, Object> intake(IntakeRequest req) {
        Tenant tenant = tenants.requireByCode(req.tenantCode());
        if (!"ACTIVE".equals(tenant.status())) {
            throw ApiException.forbidden("TENANT_NOT_ACTIVE", "tenant is " + tenant.status());
        }
        String messageType = typeForPath(req.endpoint());
        if (messageType == null) {
            throw ApiException.badRequest("ENDPOINT_UNKNOWN", "unknown intake endpoint " + req.endpoint());
        }
        if (req.idempotencyKey() == null || req.idempotencyKey().isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required so retries cannot duplicate samples");
        }

        JsonNode payload;
        String canonical;
        try {
            payload = crypto.readTree(req.rawBody() == null ? "{}" : req.rawBody());
            canonical = crypto.canonicalJson(payload);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("BODY_INVALID", "body must be a JSON object");
        }
        if (!payload.isObject()) {
            throw ApiException.badRequest("BODY_INVALID", "body must be a JSON object");
        }
        String payloadHash = crypto.sha256Hex(canonical);

        // 1) Idempotency is checked BEFORE burning the nonce so a legitimate retry
        //    (same key, same body) returns the original receipt without AUTH_REPLAY.
        Optional<Map<String, Object>> existing = findByIdempotencyKey(tenant.tenantId(), req.idempotencyKey());
        if (existing.isPresent()) {
            Map<String, Object> row = existing.get();
            String storedHash = (String) row.get("payload_hash");
            if (!payloadHash.equals(storedHash)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used for a different payload",
                        Map.of("originalHash", storedHash, "incomingHash", payloadHash));
            }
            auth.verifySignature(tenant, req.sourceCode(), req.keyId(), req.nonce(), req.signedAt(),
                    req.signature(), req.signatureHeader(), canonical, req.endpoint());
            Map<String, Object> receipt = receiptFromRow(row);
            receipt.put("duplicate", true);
            return receipt;
        }

        AuthService.VerifiedSource source = auth.verifySignature(tenant, req.sourceCode(), req.keyId(),
                req.nonce(), req.signedAt(), req.signature(), req.signatureHeader(), canonical, req.endpoint());
        auth.consumeNonce(tenant, req.sourceCode(), req.nonce(), source.signedAt());

        // 2) Semantic validation. Never drop the payload even when rejected.
        SemanticValidator.Result validation = validator.validate(messageType, payload);
        long seq = nextSeq(tenant.tenantId());
        UUID messageId = UUID.randomUUID();

        Map<String, Object> fence = null;
        String writeSpace = tenant.writeSpace();
        String status;
        String appliedSpace = null;
        String naturalKey = naturalKey(messageType, payload);
        String fenceId = null;
        String migrationId = null;
        List<String> routes = new ArrayList<>();

        if (validation.rejected()) {
            status = "REJECTED";
            writeSpace = "NONE";
        } else if ("FENCE".equals(writeSpace)) {
            Optional<Map<String, Object>> fenceOpt = currentFence(tenant.tenantId());
            if (fenceOpt.isEmpty()) {
                // write_space says FENCE but no fence row: fail closed, do not guess a space.
                throw ApiException.conflict("FENCE_STATE_DESYNC",
                        "tenant is fenced but no open fence exists; operator action required", Map.of());
            }
            fence = fenceOpt.get();
            fenceId = (String) fence.get("fenceId");
            migrationId = (String) fence.get("migrationId");
            status = "BUFFERED";
            log.info("tenant {} message {} buffered in fence {} at seq {}", tenant.code(), messageId, fenceId, seq);
        } else {
            status = "APPLIED";
        }

        try {
            jdbc.update("""
                    insert into lab.intake_message(message_id, tenant_id, seq, idempotency_key, endpoint,
                      message_type, natural_key, payload, payload_hash, source_code, key_id, signature,
                      nonce, signed_at, storage_mode, status, applied_space, fence_id, migration_id,
                      validation_status, findings)
                    values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?::uuid, ?, ?::jsonb)
                    """, messageId.toString(), tenant.tenantId(), seq, req.idempotencyKey(), req.endpoint(),
                    messageType, naturalKey, canonical, payloadHash, source.sourceCode(), source.keyId(),
                    source.signature(), req.nonce(), source.signedAt(), writeSpace, status, appliedSpace,
                    fenceId, migrationId, validation.status(),
                    crypto.canonicalJson(validation.findings().stream().map(f -> Map.of(
                            "code", f.code(), "severity", f.severity(), "message", f.message())).toList()));
            replication.recordReportMeta(tenant, messageType, payload, seq, validation.status());
        } catch (DuplicateKeyException e) {
            Map<String, Object> row = findByIdempotencyKey(tenant.tenantId(), req.idempotencyKey())
                    .orElseThrow(() -> e);
            Map<String, Object> receipt = receiptFromRow(row);
            receipt.put("duplicate", true);
            return receipt;
        }

        // 3) Apply or buffer, exactly once, in the routing space the gate chose.
        if (validation.rejected()) {
            // persisted, not applied
        } else if ("FENCE".equals(writeSpace)) {
            jdbc.update("""
                    insert into lab.intake_buffer(buffer_id, fence_id, tenant_id, seq, message_id,
                      message_type, natural_key, payload)
                    values (?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?::jsonb)
                    """, UUID.randomUUID().toString(), fenceId, tenant.tenantId(), seq,
                    messageId.toString(), messageType, naturalKey, canonical);
            jdbc.update("""
                    update lab.fence set buffered_count = buffered_count + 1, high_seq = greatest(coalesce(high_seq,0), ?)
                    where fence_id = ?::uuid
                    """, seq, fenceId);
            routes.add("BUFFERED");
        } else if ("DUAL".equals(writeSpace)) {
            writer.apply(tenant, "OLD", messageId, seq, messageType, payload);
            writer.apply(tenant, "NEW", messageId, seq, messageType, payload);
            appliedSpace = "OLD+NEW";
            status = "APPLIED_DUAL";
            routes.add("OLD");
            routes.add("NEW");
            jdbc.update("""
                    update lab.intake_message set status = ?, applied_space = ? where message_id = ?::uuid
                    """, status, appliedSpace, messageId.toString());
        } else if ("NEW".equals(writeSpace)) {
            writer.apply(tenant, "NEW", messageId, seq, messageType, payload);
            appliedSpace = "NEW";
            routes.add("NEW");
            jdbc.update("""
                    update lab.intake_message set applied_space = ? where message_id = ?::uuid
                    """, appliedSpace, messageId.toString());
        } else if ("OLD".equals(writeSpace)) {
            writer.apply(tenant, "OLD", messageId, seq, messageType, payload);
            appliedSpace = "OLD";
            routes.add("OLD");
            jdbc.update("""
                    update lab.intake_message set applied_space = ? where message_id = ?::uuid
                    """, appliedSpace, messageId.toString());
        }

        writer.recordBoundary(tenant.tenantId(), messageId, seq, messageType, payload,
                source.sourceCode(), source.keyId(), source.scope(), payloadHash, source.signature(),
                appliedSpace != null ? appliedSpace : ("FENCE".equals(writeSpace) ? null : null));

        persistFindings(tenant.tenantId(), messageType, payload, validation);

        String visibleState = validation.rejected() ? "REJECTED"
                : validation.status().equals("FLAGGED") ? "PENDING_VERIFICATION" : "VERIFIED";
        evidence.append(tenant.tenantId(), "INTAKE_RECEIPT", messageType, naturalKey,
                appliedSpace, visibleState, Map.of(
                        "messageId", messageId.toString(), "seq", seq, "status", status,
                        "validation", validation.status(), "fenceId", fenceId == null ? "" : fenceId,
                        "endpoint", req.endpoint(), "sourceCode", source.sourceCode(),
                        "payloadHash", payloadHash));

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("messageId", messageId.toString());
        receipt.put("tenant", tenant.code());
        receipt.put("seq", seq);
        receipt.put("messageType", messageType);
        receipt.put("naturalKey", naturalKey);
        receipt.put("status", status);
        receipt.put("appliedSpace", appliedSpace);
        receipt.put("validationStatus", validation.status());
        receipt.put("findings", validation.findings());
        receipt.put("durable", true);
        receipt.put("duplicate", false);
        receipt.put("fenceId", fenceId);
        receipt.put("migrationId", migrationId);
        receipt.put("routing", routes);
        receipt.put("readSpaceAtReceipt", tenant.readSpace());
        receipt.put("writeSpaceAtReceipt", writeSpace);
        receipt.put("payloadHash", payloadHash);
        receipt.put("generation", tenant.generation());
        if (validation.rejected()) {
            throw new PersistedRejection(receipt);
        }
        return receipt;
    }

    /** Thrown after the rejected message has been persisted; mapped to 422 by the controller. */
    public static class PersistedRejection extends RuntimeException {
        private final Map<String, Object> receipt;

        public PersistedRejection(Map<String, Object> receipt) {
            super("message persisted but rejected by semantic validation");
            this.receipt = receipt;
        }

        public Map<String, Object> receipt() {
            return receipt;
        }
    }

    private void persistFindings(String tenantId, String messageType, JsonNode payload,
                                 SemanticValidator.Result validation) {
        for (SemanticValidator.Finding f : validation.findings()) {
            jdbc.update("""
                    insert into lab.semantic_finding(finding_id, tenant_id, sample_id, result_id, report_id,
                      rule_code, severity, message, detail)
                    values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID().toString(), tenantId,
                    payload.path("sampleId").isMissingNode() ? null : payload.path("sampleId").asText(),
                    "RESULT".equals(messageType) ? payload.path("resultId").asText() : null,
                    "REPORT_REVISION".equals(messageType) ? payload.path("reportId").asText() : null,
                    f.code(), f.severity(), f.message(), crypto.canonicalJson(f.detail()));
        }
    }

    private long nextSeq(String tenantId) {
        // Atomic, gap-free per tenant. Row lock serialises concurrent gateway threads.
        jdbc.update("insert into lab.intake_counter(tenant_id) values (?::uuid) on conflict do nothing", tenantId);
        Long seq = jdbc.queryForObject("""
                update lab.intake_counter set last_seq = last_seq + 1
                where tenant_id = ?::uuid returning last_seq
                """, Long.class, tenantId);
        return seq == null ? 1 : seq;
    }

    private Optional<Map<String, Object>> findByIdempotencyKey(String tenantId, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select message_id::text, tenant_id::text, seq, idempotency_key, message_type, natural_key,
                       payload_hash, status, applied_space, validation_status, fence_id::text, migration_id::text
                from lab.intake_message where tenant_id = ?::uuid and idempotency_key = ?
                """, tenantId, key);
        return rows.stream().findFirst();
    }

    private Map<String, Object> receiptFromRow(Map<String, Object> row) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("messageId", row.get("message_id"));
        receipt.put("seq", row.get("seq"));
        receipt.put("messageType", row.get("message_type"));
        receipt.put("naturalKey", row.get("natural_key"));
        receipt.put("status", row.get("status"));
        receipt.put("appliedSpace", row.get("applied_space"));
        receipt.put("validationStatus", row.get("validation_status"));
        receipt.put("fenceId", row.get("fence_id"));
        receipt.put("migrationId", row.get("migration_id"));
        receipt.put("payloadHash", row.get("payload_hash"));
        receipt.put("durable", true);
        return receipt;
    }

    private Optional<Map<String, Object>> currentFence(String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select fence_id::text as "fenceId", migration_id::text as "migrationId"
                from lab.fence where tenant_id = ?::uuid and state in ('OPEN','DRAINING')
                order by opened_at desc limit 1
                """, tenantId);
        return rows.stream().findFirst();
    }

    private String naturalKey(String type, JsonNode p) {
        return switch (type) {
            case "SAMPLE" -> "sample:" + p.path("sampleId").asText();
            case "RESULT" -> "result:" + p.path("resultId").asText();
            case "REPORT_REVISION" -> "report:" + p.path("reportId").asText() + "#" + p.path("revision").asText();
            case "OBJECT" -> "object:" + p.path("objectKey").asText();
            default -> "unknown";
        };
    }

    public List<Map<String, Object>> messages(String tenantId, int limit) {
        return jdbc.queryForList("""
                select message_id::text as "messageId", seq, message_type as "messageType",
                       natural_key as "naturalKey", status, applied_space as "appliedSpace",
                       validation_status as "validationStatus", fence_id::text as "fenceId",
                       source_code as "sourceCode", received_at as "receivedAt", findings
                from lab.intake_message where tenant_id = ?::uuid order by seq desc limit ?
                """, tenantId, limit);
    }

    public Map<String, Object> messageDetail(String tenantId, String messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select message_id::text as "messageId", seq, idempotency_key as "idempotencyKey",
                       endpoint, message_type as "messageType", natural_key as "naturalKey",
                       payload, payload_hash as "payloadHash", source_code as "sourceCode",
                       key_id as "keyId", signature, nonce, signed_at as "signedAt",
                       storage_mode as "storageMode", status, applied_space as "appliedSpace",
                       fence_id::text as "fenceId", migration_id::text as "migrationId",
                       validation_status as "validationStatus", findings, received_at as "receivedAt"
                from lab.intake_message where tenant_id = ?::uuid and message_id = ?::uuid
                """, tenantId, messageId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("MESSAGE_NOT_FOUND", "unknown message for this tenant");
        }
        return rows.get(0);
    }

    public Map<String, Object> stats(String tenantId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) as total,
                       count(*) filter (where status = 'BUFFERED') as buffered,
                       count(*) filter (where status = 'REJECTED') as rejected,
                       count(*) filter (where validation_status = 'FLAGGED') as flagged,
                       count(*) filter (where applied_space like '%NEW%') as touched_new
                from lab.intake_message where tenant_id = ?::uuid
                """, tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, v instanceof Number n ? n.longValue() : v));
        return out;
    }

    public HttpStatus rejectionStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
