package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labroute.model.Tenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TenantService {

    private final JdbcTemplate jdbc;
    private final CryptoSupport crypto;
    private final com.labroute.db.SchemaService schemaService;

    public TenantService(JdbcTemplate jdbc, CryptoSupport crypto,
                         com.labroute.db.SchemaService schemaService) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.schemaService = schemaService;
    }

    private final RowMapper<Tenant> mapper = (rs, i) -> new Tenant(
            rs.getString("tenant_id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("strategy"),
            rs.getString("isolation_level"),
            rs.getInt("policy_version"),
            rs.getString("read_space"),
            rs.getString("write_space"),
            rs.getString("phase"),
            rs.getLong("generation"),
            rs.getString("dedicated_role"),
            toInstant(rs, "isolation_changed_at"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at"),
            null, null
    );

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    private static final String SELECT = """
            select t.*, o.schema_name as old_schema, n.schema_name as new_schema
            from lab.tenant t
            join lab.tenant_space o on o.tenant_id = t.tenant_id and o.space_role = 'OLD'
            join lab.tenant_space n on n.tenant_id = t.tenant_id and n.space_role = 'NEW'
            """;

    private Tenant withSchemas(ResultSet rs) throws SQLException {
        Tenant base = mapper.mapRow(rs, 0);
        return new Tenant(base.tenantId(), base.code(), base.name(), base.status(), base.strategy(),
                base.isolationLevel(), base.policyVersion(), base.readSpace(), base.writeSpace(),
                base.phase(), base.generation(), base.dedicatedRole(), base.isolationChangedAt(),
                base.createdAt(), base.updatedAt(), rs.getString("old_schema"), rs.getString("new_schema"));
    }

    public List<Tenant> list() {
        return jdbc.query(SELECT + " order by t.created_at", (rs, i) -> withSchemas(rs));
    }

    public Optional<Tenant> findByCode(String code) {
        List<Tenant> rows = jdbc.query(SELECT + " where t.code = ?", (rs, i) -> withSchemas(rs), code);
        return rows.stream().findFirst();
    }

    public Tenant requireByCode(String code) {
        return findByCode(code).orElseThrow(() ->
                com.labroute.web.ApiException.notFound("TENANT_NOT_FOUND", "unknown tenant code " + code));
    }

    public Tenant requireById(String id) {
        List<Tenant> rows = jdbc.query(SELECT + " where t.tenant_id = ?::uuid", (rs, i) -> withSchemas(rs), id);
        return rows.stream().findFirst().orElseThrow(() ->
                com.labroute.web.ApiException.notFound("TENANT_NOT_FOUND", "unknown tenant id " + id));
    }

    public record CreateTenant(String code, String name, String strategy) {
    }

    public Tenant create(CreateTenant req) {
        if (req.code() == null || !req.code().matches("^[a-z0-9][a-z0-9-]{1,40}$")) {
            throw com.labroute.web.ApiException.badRequest("TENANT_CODE_INVALID",
                    "code must be 2-41 chars of lowercase letters, digits, hyphen and start alphanumeric");
        }
        if (findByCode(req.code()).isPresent()) {
            throw com.labroute.web.ApiException.conflict("TENANT_EXISTS", "tenant code already used", Map.of());
        }
        String strategy = req.strategy() == null ? "FENCE_REPLAY" : req.strategy();
        if (!List.of("FENCE_REPLAY", "WATERMARK_ONLINE").contains(strategy)) {
            throw com.labroute.web.ApiException.badRequest("TENANT_STRATEGY_INVALID",
                    "strategy must be FENCE_REPLAY or WATERMARK_ONLINE");
        }
        String id = UUID.randomUUID().toString();
        String oldSchema = com.labroute.db.SchemaService.spaceSchema(req.code(), "OLD");
        String newSchema = com.labroute.db.SchemaService.spaceSchema(req.code(), "NEW");
        jdbc.update("""
                insert into lab.tenant(tenant_id, code, name, strategy) values (?::uuid, ?, ?, ?)
                """, id, req.code(), req.name() == null ? req.code() : req.name(), strategy);
        jdbc.update("""
                insert into lab.tenant_space(tenant_id, space_role, schema_name, object_prefix, label)
                values (?::uuid, 'OLD', ?, 'legacy', '迁移前租户空间'),
                       (?::uuid, 'NEW', ?, 'migrated', '独立租户空间')
                """, id, oldSchema, id, newSchema);
        jdbc.update("insert into lab.intake_counter(tenant_id) values (?::uuid) on conflict do nothing", id);
        schemaService.createSpace(oldSchema);
        schemaService.createSpace(newSchema);
        Tenant tenant = requireById(id);
        return tenant;
    }

    public void recordPolicyEvent(String tenantId, int seq, String from, String to, int version,
                                  String role, String reason) {
        jdbc.update("""
                insert into lab.tenant_policy_event(event_id, tenant_id, sequence, from_level, to_level,
                  policy_version, dedicated_role, reason)
                values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), tenantId, seq, from, to, version, role, reason);
    }

    public List<Map<String, Object>> policyEvents(String tenantId) {
        return jdbc.queryForList("""
                select event_id::text, sequence, from_level, to_level, policy_version,
                       dedicated_role, reason, created_at
                from lab.tenant_policy_event where tenant_id = ?::uuid order by sequence
                """, tenantId);
    }

    public Map<String, Object> summary(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", t.tenantId());
        m.put("code", t.code());
        m.put("name", t.name());
        m.put("status", t.status());
        m.put("strategy", t.strategy());
        m.put("isolationLevel", t.isolationLevel());
        m.put("policyVersion", t.policyVersion());
        m.put("readSpace", t.readSpace());
        m.put("writeSpace", t.writeSpace());
        m.put("phase", t.phase());
        m.put("generation", t.generation());
        m.put("dedicatedRole", t.dedicatedRole());
        m.put("spaces", Map.of("OLD", t.oldSchema(), "NEW", t.newSchema()));
        m.put("counts", spaceCounts(t));
        return m;
    }

    public Map<String, Object> spaceCounts(Tenant t) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("OLD", countSpace(t.oldSchema()));
        counts.put("NEW", countSpace(t.newSchema()));
        return counts;
    }

    private Map<String, Long> countSpace(String schema) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String table : List.of("sample", "instrument_result", "report_revision", "object_store")) {
            Long c = jdbc.queryForObject("select count(*) from " + schema + "." + table, Long.class);
            out.put(table, c == null ? 0L : c);
        }
        return out;
    }

    public void update(Tenant t, String phase, String readSpace, String writeSpace, long generation) {
        jdbc.update("""
                update lab.tenant set phase = ?, read_space = ?, write_space = ?, generation = ?,
                  updated_at = now() where tenant_id = ?::uuid
                """, phase, readSpace, writeSpace, generation, t.tenantId());
    }

    public void setStrategy(String tenantId, String strategy) {
        jdbc.update("update lab.tenant set strategy = ? where tenant_id = ?::uuid", strategy, tenantId);
    }

    public List<String> reportSamplesInSpace(Tenant t, String role) {
        String schema = "OLD".equals(role) ? t.oldSchema() : t.newSchema();
        return jdbc.queryForList("select distinct sample_id from " + schema + ".report_revision", String.class);
    }

    public JsonNode sampleChain(Tenant t, String role, String sampleId) {
        String schema = "OLD".equals(role) ? t.oldSchema() : t.newSchema();
        List<Map<String, Object>> samples = jdbc.queryForList(
                "select sample_id, chain_version, parent_sample_id, source_code, collected_at, status, payload, updated_seq " +
                        "from " + schema + ".sample where sample_id = ? or parent_sample_id = ? order by sample_id",
                sampleId, sampleId);
        List<Map<String, Object>> results = jdbc.queryForList(
                "select result_id, sample_id, analyte_code, value_num, unit, ref_low, ref_high, measured_at, status, updated_seq " +
                        "from " + schema + ".instrument_result where sample_id = ? order by result_id",
                sampleId);
        List<Map<String, Object>> reports = jdbc.queryForList(
                "select report_id, revision, content, content_hash, updated_seq from " + schema +
                        ".report_revision where sample_id = ? order by report_id, revision", sampleId);
        ObjectNode node = crypto.mapper().createObjectNode();
        node.set("space", crypto.mapper().valueToTree(role));
        node.put("schema", schema);
        node.set("samples", crypto.mapper().valueToTree(samples));
        node.set("results", crypto.mapper().valueToTree(results));
        node.set("reports", crypto.mapper().valueToTree(reports));
        return node;
    }

    public List<String> sampleIds(Tenant t) {
        List<String> ids = new ArrayList<>(jdbc.queryForList(
                "select distinct sample_id from " + t.oldSchema() + ".sample", String.class));
        return ids;
    }
}
