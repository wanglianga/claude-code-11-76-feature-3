package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only, hash-chained evidence ledger. Each entry's hash covers the previous
 * entry hash for the same tenant, so tampering with or dropping an entry is detectable
 * on replay. Evidence is how "which space served this report" is answered after the fact.
 */
@Service
public class EvidenceService {

    private final JdbcTemplate jdbc;
    private final CryptoSupport crypto;

    public EvidenceService(JdbcTemplate jdbc, CryptoSupport crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public record Evidence(String evidenceId, long seq, String eventType, String entityType,
                           String entityId, String sourceSpace, String visibleState,
                           JsonNode payload, String prevHash, String hash) {
    }

    @Transactional
    public Evidence append(String tenantId, String eventType, String entityType, String entityId,
                           String sourceSpace, String visibleState, Map<String, Object> payload) {
        Long maxSeq = jdbc.queryForObject(
                "select coalesce(max(seq),0) from lab.evidence_log where tenant_id = ?::uuid", Long.class, tenantId);
        long seq = (maxSeq == null ? 0 : maxSeq) + 1;
        String prevHash = jdbc.query("select hash from lab.evidence_log where tenant_id = ?::uuid and seq = ?",
                rs -> rs.next() ? rs.getString(1) : null, tenantId, seq - 1);
        if (prevHash == null) {
            prevHash = "GENESIS";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("seq", seq);
        body.put("eventType", eventType);
        body.put("entityType", entityType);
        body.put("entityId", entityId);
        body.put("sourceSpace", sourceSpace);
        body.put("visibleState", visibleState);
        body.put("payload", payload == null ? Map.of() : payload);
        body.put("prevHash", prevHash);
        String hash = crypto.sha256Canonical(body);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into lab.evidence_log(evidence_id, tenant_id, seq, event_type, entity_type,
                  entity_id, source_space, visible_state, payload, prev_hash, hash)
                values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, id, tenantId, seq, eventType, entityType, entityId, sourceSpace, visibleState,
                crypto.canonicalJson(payload == null ? Map.of() : payload), prevHash, hash);
        return new Evidence(id, seq, eventType, entityType, entityId, sourceSpace, visibleState,
                crypto.mapper().valueToTree(payload == null ? Map.of() : payload), prevHash, hash);
    }

    public List<Map<String, Object>> list(String tenantId, String entityId, int limit) {
        StringBuilder sql = new StringBuilder("""
                select evidence_id::text, seq, event_type, entity_type, entity_id, source_space,
                       visible_state, payload, prev_hash, hash, created_at
                from lab.evidence_log where tenant_id = ?::uuid
                """);
        Object[] args;
        if (entityId == null || entityId.isBlank()) {
            sql.append(" order by seq desc limit ?");
            args = new Object[]{tenantId, limit};
        } else {
            sql.append(" and (entity_id = ? or payload->>'sampleId' = ?) order by seq desc limit ?");
            args = new Object[]{tenantId, entityId, entityId, limit};
        }
        return jdbc.queryForList(sql.toString(), args);
    }

    /** Replays the chain and reports the first broken link, if any. */
    public Map<String, Object> verifyChain(String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select seq, event_type, entity_type, entity_id, source_space, visible_state,
                       payload, prev_hash, hash
                from lab.evidence_log where tenant_id = ?::uuid order by seq
                """, tenantId);
        String expectedPrev = "GENESIS";
        long checked = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("seq", ((Number) row.get("seq")).longValue());
            body.put("eventType", row.get("event_type"));
            body.put("entityType", row.get("entity_type"));
            body.put("entityId", row.get("entity_id"));
            body.put("sourceSpace", row.get("source_space"));
            body.put("visibleState", row.get("visible_state"));
            body.put("payload", crypto.readTree(String.valueOf(row.get("payload"))));
            body.put("prevHash", row.get("prev_hash"));
            String recomputed = crypto.sha256Canonical(body);
            if (!expectedPrev.equals(row.get("prev_hash"))) {
                return Map.of("valid", false, "checked", checked, "brokenAtSeq", row.get("seq"),
                        "reason", "prev_hash does not chain to the previous entry");
            }
            if (!recomputed.equals(row.get("hash"))) {
                return Map.of("valid", false, "checked", checked, "brokenAtSeq", row.get("seq"),
                        "reason", "entry hash does not match its content");
            }
            expectedPrev = (String) row.get("hash");
            checked++;
        }
        return Map.of("valid", true, "checked", checked, "headHash", expectedPrev);
    }

    public JsonNode asJson(Map<String, Object> row) {
        ObjectNode n = crypto.mapper().createObjectNode();
        row.forEach((k, v) -> n.set(k, crypto.mapper().valueToTree(v)));
        return n;
    }
}
