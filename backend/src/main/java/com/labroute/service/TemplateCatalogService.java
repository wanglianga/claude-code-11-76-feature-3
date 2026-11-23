package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.labroute.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Template semantic directory.
 *
 * Holds the tenant-scoped catalogue of report templates and reference-interval rule sets,
 * each with explicit version, lifecycle (DRAFT -> PUBLISHED -> WITHDRAWN) and an effective
 * time window. After migration every tenant routes report interpretation through this one
 * directory, while point-in-time effective windows keep historical reports reproducible:
 * a rule withdrawn today still resolves for a report issued last week.
 */
@Service
public class TemplateCatalogService {

    private final JdbcTemplate jdbc;
    private final CryptoSupport crypto;

    public TemplateCatalogService(JdbcTemplate jdbc, CryptoSupport crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public record RuleSpec(String analyteCode, String instrumentCode, Long ageLowDays, Long ageHighDays,
                           String sex, BigDecimal refLow, BigDecimal refHigh, String unit,
                           String flagLabel, Integer priority) {
    }

    public record RuleSetRequest(String ruleSetCode, Instant effectiveFrom, List<RuleSpec> rules) {
    }

    public record TemplateRequest(String templateCode, String analyteCode, String name,
                                  JsonNode content, UUID ruleSetId, Instant effectiveFrom) {
    }

    // ---------- rule sets ----------

    @Transactional
    public Map<String, Object> createRuleSet(String tenantId, RuleSetRequest req) {
        if (req.ruleSetCode() == null || req.ruleSetCode().isBlank()) {
            throw ApiException.badRequest("RULE_SET_CODE_REQUIRED", "ruleSetCode is required");
        }
        Integer nextVersion = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from lab.reference_rule_set
                where tenant_id = ?::uuid and rule_set_code = ?
                """, Integer.class, tenantId, req.ruleSetCode());
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into lab.reference_rule_set(rule_set_id, tenant_id, rule_set_code, version, status)
                values (?::uuid, ?::uuid, ?, ?, 'DRAFT')
                """, id, tenantId, req.ruleSetCode(), nextVersion);
        if (req.rules() != null) {
            for (RuleSpec spec : req.rules()) {
                addRule(UUID.fromString(id), tenantId, spec);
            }
        }
        return getRuleSet(tenantId, id);
    }

    @Transactional
    public Map<String, Object> addRule(String tenantId, String ruleSetId, RuleSpec spec) {
        Map<String, Object> set = requireRuleSet(tenantId, ruleSetId);
        if (!"DRAFT".equals(set.get("status"))) {
            throw ApiException.conflict("RULE_SET_NOT_DRAFT",
                    "rules can only be added to a DRAFT set; create a new version instead", Map.of("status", set.get("status")));
        }
        String ruleId = addRule(UUID.fromString(ruleSetId), tenantId, spec);
        return getRuleSet(tenantId, ruleSetId);
    }

    private String addRule(UUID ruleSetId, String tenantId, RuleSpec spec) {
        if (spec.analyteCode() == null || spec.unit() == null) {
            throw ApiException.badRequest("RULE_SPEC_INVALID", "analyteCode and unit are required for each rule");
        }
        if (spec.refLow() != null && spec.refHigh() != null && spec.refLow().compareTo(spec.refHigh()) > 0) {
            throw ApiException.badRequest("REFERENCE_INTERVAL_INVERTED",
                    "refLow " + spec.refLow() + " is greater than refHigh " + spec.refHigh());
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into lab.reference_interval_rule(rule_id, rule_set_id, tenant_id, analyte_code,
                  instrument_code, age_low_days, age_high_days, sex, ref_low, ref_high, unit,
                  flag_label, priority, status, effective_from)
                values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', now())
                """, id, ruleSetId.toString(), tenantId, spec.analyteCode(),
                blankToNull(spec.instrumentCode()), spec.ageLowDays() == null ? 0L : spec.ageLowDays(),
                spec.ageHighDays(), blankToNull(spec.sex()), spec.refLow(), spec.refHigh(), spec.unit(),
                spec.flagLabel() == null ? "NORMAL" : spec.flagLabel(),
                spec.priority() == null ? 100 : spec.priority());
        return id;
    }

    @Transactional
    public Map<String, Object> publishRuleSet(String tenantId, String ruleSetId, Instant effectiveFrom,
                                              String reason) {
        Map<String, Object> set = requireRuleSet(tenantId, ruleSetId);
        if (!"DRAFT".equals(set.get("status"))) {
            throw ApiException.conflict("RULE_SET_NOT_DRAFT",
                    "only a DRAFT rule set can be published", Map.of("status", set.get("status")));
        }
        Long ruleCount = jdbc.queryForObject(
                "select count(*) from lab.reference_interval_rule where rule_set_id = ?::uuid",
                Long.class, ruleSetId);
        if (ruleCount == null || ruleCount == 0) {
            throw ApiException.conflict("RULE_SET_EMPTY",
                    "a rule set needs at least one interval rule before publication", Map.of());
        }
        Instant from = effectiveFrom == null ? Instant.now() : effectiveFrom;
        jdbc.update("""
                update lab.reference_rule_set set status = 'PUBLISHED', effective_from = ?, updated_at = now()
                where rule_set_id = ?::uuid
                """, OffsetDateTime.ofInstant(from, ZoneOffset.UTC), ruleSetId);
        return getRuleSet(tenantId, ruleSetId);
    }

    /**
     * Withdrawal closes the effective window at {@code withdrawnAt} but keeps the row: reports
     * whose report time precedes the withdrawal still resolve this version deterministically.
     */
    @Transactional
    public Map<String, Object> withdrawRuleSet(String tenantId, String ruleSetId, String reason) {
        Map<String, Object> set = requireRuleSet(tenantId, ruleSetId);
        if ("WITHDRAWN".equals(set.get("status"))) {
            return getRuleSet(tenantId, ruleSetId);
        }
        Instant now = Instant.now();
        OffsetDateTime withdrawnAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.update("""
                update lab.reference_rule_set
                set status = 'WITHDRAWN', withdrawn_at = now(), withdrawn_reason = ?,
                    effective_to = ?, updated_at = now()
                where rule_set_id = ?::uuid
                """, reason == null ? "operator withdrawal" : reason, withdrawnAt, ruleSetId);
        jdbc.update("""
                update lab.reference_interval_rule set status = 'WITHDRAWN', effective_to = ?
                where rule_set_id = ?::uuid and status = 'ACTIVE'
                """, withdrawnAt, ruleSetId);
        return getRuleSet(tenantId, ruleSetId);
    }

    // ---------- templates ----------

    @Transactional
    public Map<String, Object> createTemplate(String tenantId, TemplateRequest req) {
        if (req.templateCode() == null || req.analyteCode() == null) {
            throw ApiException.badRequest("TEMPLATE_SPEC_INVALID", "templateCode and analyteCode are required");
        }
        if (req.ruleSetId() != null) {
            requireRuleSet(tenantId, req.ruleSetId().toString());
        }
        Integer nextVersion = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from lab.template_catalog
                where tenant_id = ?::uuid and template_code = ?
                """, Integer.class, tenantId, req.templateCode());
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into lab.template_catalog(template_id, tenant_id, template_code, version, analyte_code,
                  name, status, rule_set_id, content)
                values (?::uuid, ?::uuid, ?, ?, ?, ?, 'DRAFT', ?::uuid, ?::jsonb)
                """, id, tenantId, req.templateCode(), nextVersion, req.analyteCode(),
                req.name() == null ? req.templateCode() : req.name(),
                req.ruleSetId() == null ? null : req.ruleSetId().toString(),
                crypto.canonicalJson(req.content() == null ? Map.of() : req.content()));
        return getTemplate(tenantId, id);
    }

    @Transactional
    public Map<String, Object> publishTemplate(String tenantId, String templateId, Instant effectiveFrom) {
        Map<String, Object> t = requireTemplate(tenantId, templateId);
        if (!"DRAFT".equals(t.get("status"))) {
            throw ApiException.conflict("TEMPLATE_NOT_DRAFT",
                    "only a DRAFT template can be published", Map.of("status", t.get("status")));
        }
        Instant from = effectiveFrom == null ? Instant.now() : effectiveFrom;
        jdbc.update("""
                update lab.template_catalog set status = 'PUBLISHED', effective_from = ?, updated_at = now()
                where template_id = ?::uuid
                """, OffsetDateTime.ofInstant(from, ZoneOffset.UTC), templateId);
        return getTemplate(tenantId, templateId);
    }

    @Transactional
    public Map<String, Object> withdrawTemplate(String tenantId, String templateId, String reason) {
        Map<String, Object> t = requireTemplate(tenantId, templateId);
        if ("WITHDRAWN".equals(t.get("status"))) {
            return getTemplate(tenantId, templateId);
        }
        jdbc.update("""
                update lab.template_catalog set status = 'WITHDRAWN', withdrawn_at = now(),
                  withdrawn_reason = ?, effective_to = now(), updated_at = now()
                where template_id = ?::uuid
                """, reason == null ? "operator withdrawal" : reason, templateId);
        return getTemplate(tenantId, templateId);
    }

    // ---------- point-in-time resolution ----------

    public Optional<Map<String, Object>> resolveTemplateAt(String tenantId, String analyteCode, Instant asOf) {
        OffsetDateTime point = OffsetDateTime.ofInstant(asOf == null ? Instant.now() : asOf, ZoneOffset.UTC);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from lab.template_catalog
                where tenant_id = ?::uuid and analyte_code = ? and status = 'PUBLISHED'
                  and effective_from <= ?
                  and (effective_to is null or effective_to > ?)
                order by version desc limit 1
                """, tenantId, analyteCode, point, point);
        return rows.stream().findFirst();
    }

    public Optional<Map<String, Object>> resolveRuleSetAt(String tenantId, Instant asOf) {
        OffsetDateTime point = OffsetDateTime.ofInstant(asOf == null ? Instant.now() : asOf, ZoneOffset.UTC);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from lab.reference_rule_set
                where tenant_id = ?::uuid and status = 'PUBLISHED'
                  and effective_from <= ?
                  and (effective_to is null or effective_to > ?)
                order by version desc limit 1
                """, tenantId, point, point);
        return rows.stream().findFirst();
    }

    // ---------- reads ----------

    public Map<String, Object> requireRuleSet(String tenantId, String ruleSetId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from lab.reference_rule_set where rule_set_id = ?::uuid and tenant_id = ?::uuid
                """, ruleSetId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("RULE_SET_NOT_FOUND", "unknown rule set for this tenant");
        }
        return rows.get(0);
    }

    public Map<String, Object> getRuleSet(String tenantId, String ruleSetId) {
        Map<String, Object> set = requireRuleSet(tenantId, ruleSetId);
        List<Map<String, Object>> rules = jdbc.queryForList("""
                select rule_id::text as "ruleId", analyte_code as "analyteCode", instrument_code as "instrumentCode",
                       age_low_days as "ageLowDays", age_high_days as "ageHighDays", sex,
                       ref_low as "refLow", ref_high as "refHigh", unit, flag_label as "flagLabel",
                       priority, status, effective_from as "effectiveFrom", effective_to as "effectiveTo"
                from lab.reference_interval_rule where rule_set_id = ?::uuid
                order by analyte_code, (instrument_code is null) asc, age_low_days
                """, ruleSetId);
        Map<String, Object> out = new LinkedHashMap<>(set);
        out.put("rules", rules);
        return out;
    }

    public Map<String, Object> requireTemplate(String tenantId, String templateId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from lab.template_catalog where template_id = ?::uuid and tenant_id = ?::uuid
                """, templateId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("TEMPLATE_NOT_FOUND", "unknown template for this tenant");
        }
        return rows.get(0);
    }

    public Map<String, Object> getTemplate(String tenantId, String templateId) {
        return requireTemplate(tenantId, templateId);
    }

    public List<Map<String, Object>> listRuleSets(String tenantId) {
        return jdbc.queryForList("""
                select rule_set_id::text as "ruleSetId", rule_set_code as "ruleSetCode", version, status,
                       effective_from as "effectiveFrom", effective_to as "effectiveTo",
                       withdrawn_reason as "withdrawnReason", created_at as "createdAt",
                       (select count(*) from lab.reference_interval_rule r where r.rule_set_id = s.rule_set_id) as "ruleCount"
                from lab.reference_rule_set s where tenant_id = ?::uuid
                order by rule_set_code, version desc
                """, tenantId);
    }

    public List<Map<String, Object>> listTemplates(String tenantId) {
        return jdbc.queryForList("""
                select template_id::text as "templateId", template_code as "templateCode", version,
                       analyte_code as "analyteCode", name, status, rule_set_id::text as "ruleSetId",
                       effective_from as "effectiveFrom", effective_to as "effectiveTo",
                       withdrawn_reason as "withdrawnReason", updated_at as "updatedAt"
                from lab.template_catalog where tenant_id = ?::uuid
                order by analyte_code, template_code, version desc
                """, tenantId);
    }

    public List<Map<String, Object>> rulesForAnalyte(String tenantId, String analyteCode) {
        return jdbc.queryForList("""
                select r.rule_id::text as "ruleId", r.analyte_code as "analyteCode",
                       r.instrument_code as "instrumentCode", r.age_low_days as "ageLowDays",
                       r.age_high_days as "ageHighDays", r.sex, r.ref_low as "refLow",
                       r.ref_high as "refHigh", r.unit, r.flag_label as "flagLabel", r.priority,
                       r.status, s.rule_set_id::text as "ruleSetId", s.rule_set_code as "ruleSetCode",
                       s.version as "ruleSetVersion", s.status as "ruleSetStatus"
                from lab.reference_interval_rule r
                join lab.reference_rule_set s on s.rule_set_id = r.rule_set_id
                where r.tenant_id = ?::uuid and r.analyte_code = ?
                order by s.version desc, r.instrument_code nulls last, r.age_low_days
                """, tenantId, analyteCode);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
