package com.labroute.service;

import com.labroute.model.Tenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side orchestration for unified semantic routing.
 *
 * This is the single entry point the publication path uses to obtain a report's clinical
 * interpretation. It enforces the gate state and implements the two history strategies:
 *
 *   MATERIALIZED - a frozen lab.report_interpretation row is served; the read is stable and
 *                  does not depend on the current rule catalogue at all.
 *   REPLAYED     - the interpretation is rebuilt from the rule set version that was in force
 *                  at the report point in time; storage stays small and replay must be
 *                  deterministic (the effective-window schema makes it so).
 *
 * Before the gate reaches ENABLED the directory interpretation is non-authoritative: the
 * service returns it labelled as a shadow alongside the legacy rendering, so callers can
 * compare but the legacy interpretation remains what is clinically served.
 */
@Service
public class SemanticRoutingService {

    public static final String SOURCE_MATERIALIZED = "MATERIALIZED";
    public static final String SOURCE_REPLAYED = "REPLAYED";
    public static final String SOURCE_SHADOW = "SHADOW_LEGACY";

    private final JdbcTemplate jdbc;
    private final TemplateRouteGate gate;
    private final ReferenceIntervalEvaluator evaluator;
    private final EvidenceService evidence;
    private final CryptoSupport crypto;

    public SemanticRoutingService(JdbcTemplate jdbc, TemplateRouteGate gate,
                                  ReferenceIntervalEvaluator evaluator, EvidenceService evidence,
                                  CryptoSupport crypto) {
        this.jdbc = jdbc;
        this.gate = gate;
        this.evaluator = evaluator;
        this.evidence = evidence;
        this.crypto = crypto;
    }

    /**
     * Interpret one report revision. When {@code revision} is null the newest revision in the
     * tenant's current read space is used. The gate's assertServable() performs the
     * fail-closed withdrawal check and raises RULE_WITHDRAWN / SEMANTIC_ROUTE_BLOCKED.
     *
     * REQUIRES_NEW so a gate refusal rolls back only this interpretation and never poisons the
     * caller's transaction - the publication path catches the exception and keeps serving.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Map<String, Object> interpret(Tenant tenant, String reportId, Integer revision) {
        Optional<TemplateRouteGate.GateRow> gateOpt = gate.find(tenant.tenantId());
        TemplateRouteGate.GateRow gateRow = gateOpt.orElseGet(() -> gate.ensureGate(tenant.tenantId(),
                TemplateRouteGate.MODE_REPLAYED));
        boolean enabled = gateRow.enabled();
        // ENABLED runs the serve-time withdrawal check (fail-closed); BLOCKED always refuses.
        // SHADOW/GATED intentionally fall through to a non-authoritative shadow interpretation.
        if (enabled || TemplateRouteGate.STATE_BLOCKED.equals(gateRow.state())) {
            gate.assertServable(tenant);
            gateRow = gate.require(tenant.tenantId());
        }

        String schema = "NEW".equals(tenant.readSpace()) ? tenant.newSchema() : tenant.oldSchema();
        Map<String, Object> report = loadReport(schema, reportId, revision);
        int resolvedRevision = ((Number) report.get("revision")).intValue();
        Instant reportTime = instant(report.get("created_at"));
        String sampleId = (String) report.get("sample_id");
        List<Map<String, Object>> results = loadResults(schema, sampleId);

        List<Map<String, Object>> interpretations;
        String source;
        if (enabled) {
            if (TemplateRouteGate.MODE_MATERIALIZED.equals(gateRow.historyMode())) {
                interpretations = materializedRows(tenant.tenantId(), reportId, resolvedRevision);
                if (interpretations.isEmpty()) {
                    // The materialised strategy promised a frozen row; its absence is an explicit
                    // operational signal, never a silent fallback to live rules.
                    throw com.labroute.web.ApiException.conflict("INTERPRETATION_NOT_MATERIALIZED",
                            "gate runs MATERIALIZED but no frozen interpretation exists for this report; "
                                    + "run history materialisation before serving",
                            Map.of("reportId", reportId, "revision", resolvedRevision));
                }
                source = SOURCE_MATERIALIZED;
            } else {
                UUID ruleSetId = gateRow.activeRuleSetId();
                interpretations = replayRows(tenant, results, reportTime, ruleSetId);
                source = SOURCE_REPLAYED;
            }
        } else {
            // Shadow phase: show what the directory *would* produce, clearly non-authoritative.
            interpretations = replayRows(tenant, results, reportTime, gateRow.candidateRuleSetId());
            source = SOURCE_SHADOW;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant.code());
        out.put("reportId", reportId);
        out.put("revision", resolvedRevision);
        out.put("readSpace", tenant.readSpace());
        out.put("reportTime", reportTime == null ? null : reportTime.toString());
        out.put("gateState", gateRow.state());
        out.put("historyMode", gateRow.historyMode());
        out.put("authoritative", enabled);
        out.put("interpretationSource", source);
        out.put("activeRuleSetId", gateRow.activeRuleSetId() == null ? null : gateRow.activeRuleSetId().toString());
        out.put("interpretations", interpretations);
        if (enabled) {
            evidence.append(tenant.tenantId(), "INTERPRETATION_SERVED", "REPORT", reportId,
                    "DIRECTORY", "VERIFIED",
                    Map.of("revision", resolvedRevision, "source", source,
                            "ruleSetId", String.valueOf(gateRow.activeRuleSetId()),
                            "resultCount", interpretations.size()));
        }
        return out;
    }

    private List<Map<String, Object>> materializedRows(String tenantId, String reportId, int revision) {
        return jdbc.queryForList("""
                select result_id as "resultId", analyte_code as "analyteCode", rule_set_version as "ruleSetVersion",
                       history_mode as "historyMode", value_num as "value", unit,
                       ref_low as "refLow", ref_high as "refHigh", flag,
                       rendered_text as "renderedText", content_hash as "contentHash",
                       report_time as "reportTime"
                from lab.report_interpretation
                where tenant_id = ?::uuid and report_id = ? and revision = ?
                order by result_id
                """, tenantId, reportId, revision);
    }

    private List<Map<String, Object>> replayRows(Tenant tenant, List<Map<String, Object>> results,
                                                 Instant reportTime, UUID ruleSetId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> result : results) {
            String sampleId = String.valueOf(result.get("sample_id"));
            Long ageDays = ageFromPayload(result);
            if (ageDays == null) {
                ageDays = ageFromSample(tenant, sampleId);
            }
            String sex = sexFromPayload(result, tenant, sampleId);
            ReferenceIntervalEvaluator.EvalContext ctx = new ReferenceIntervalEvaluator.EvalContext(
                    tenant.tenantId(), (String) result.get("analyte_code"),
                    (String) result.get("instrument_code"), sex, ageDays,
                    (BigDecimal) result.get("value_num"), (String) result.get("unit"),
                    reportTime == null ? Instant.now() : reportTime, ruleSetId);
            ReferenceIntervalEvaluator.IntervalOutcome o = evaluator.evaluate(ctx);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resultId", result.get("result_id"));
            m.put("analyteCode", o.detail().get("analyteCode"));
            m.put("instrumentCode", o.instrumentCode());
            m.put("value", result.get("value_num"));
            m.put("unit", o.unit());
            m.put("refLow", o.refLow());
            m.put("refHigh", o.refHigh());
            m.put("flag", o.flag());
            m.put("status", o.status());
            m.put("ruleSetId", o.ruleSetId() == null ? null : o.ruleSetId().toString());
            m.put("ruleSetVersion", o.ruleSetCode() == null ? null
                    : o.ruleSetCode() + "#" + o.ruleSetVersion());
            m.put("ruleSetStatus", o.ruleSetStatus());
            m.put("ageBandDays", java.util.Arrays.asList(o.ageLowDays(), o.ageHighDays()));
            m.put("patientAgeDays", ageDays);
            m.put("reason", o.reason());
            rows.add(m);
        }
        return rows;
    }

    // ---------- helpers shared with the renderer ----------

    private Map<String, Object> loadReport(String schema, String reportId, Integer revision) {
        List<Map<String, Object>> rows;
        if (revision == null) {
            rows = jdbc.queryForList("""
                    select * from %s.report_revision where report_id = ? order by revision desc limit 1
                    """.formatted(schema), reportId);
        } else {
            rows = jdbc.queryForList("""
                    select * from %s.report_revision where report_id = ? and revision = ?
                    """.formatted(schema), reportId, revision);
        }
        if (rows.isEmpty()) {
            throw com.labroute.web.ApiException.notFound("REPORT_NOT_FOUND",
                    "report " + reportId + " is not present in " + schema);
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> loadResults(String schema, String sampleId) {
        return jdbc.queryForList("""
                select result_id, sample_id, instrument_code, analyte_code, value_num, unit,
                       ref_low, ref_high, measured_at, payload::text as payload_text
                from %s.instrument_result where sample_id = ? order by result_id
                """.formatted(schema), sampleId);
    }

    private Long ageFromPayload(Map<String, Object> result) {
        JsonNodeLite node = parse(String.valueOf(result.get("payload_text")));
        if (node == null) {
            return null;
        }
        Long age = node.longField("patientAgeDays");
        if (age == null && node.has("patientDob")) {
            try {
                OffsetDateTime dob = OffsetDateTime.parse(node.text("patientDob"));
                String at = node.text("measuredAt");
                OffsetDateTime atDate = at == null ? OffsetDateTime.now() : OffsetDateTime.parse(at);
                age = ReferenceIntervalEvaluator.ageDays(dob, atDate);
            } catch (Exception ignored) {
                // unparseable date -> age unknown; the evaluator falls back to age 0 band
            }
        }
        return age;
    }

    private String sexFromPayload(Map<String, Object> result, Tenant tenant, String sampleId) {
        JsonNodeLite node = parse(String.valueOf(result.get("payload_text")));
        String sex = node == null ? null : node.text("patientSex");
        if (sex != null) {
            return sex;
        }
        return sampleField(tenant, sampleId, "patientSex");
    }

    private Long ageFromSample(Tenant tenant, String sampleId) {
        String raw = samplePayloadText(tenant, sampleId);
        JsonNodeLite node = parse(raw);
        if (node == null) {
            return null;
        }
        Long age = node.longField("patientAgeDays");
        if (age == null && node.has("patientDob")) {
            try {
                OffsetDateTime dob = OffsetDateTime.parse(node.text("patientDob"));
                String at = node.text("collectedAt");
                OffsetDateTime atDate = at == null ? OffsetDateTime.now() : OffsetDateTime.parse(at);
                age = ReferenceIntervalEvaluator.ageDays(dob, atDate);
            } catch (Exception ignored) {
                // unparseable date -> age unknown; evaluator falls back to the lowest band
            }
        }
        return age;
    }

    private String sampleField(Tenant tenant, String sampleId, String field) {
        JsonNodeLite node = parse(samplePayloadText(tenant, sampleId));
        return node == null ? null : node.text(field);
    }

    private String samplePayloadText(Tenant tenant, String sampleId) {
        String schema = "NEW".equals(tenant.readSpace()) ? tenant.newSchema() : tenant.oldSchema();
        List<Map<String, Object>> samples = jdbc.queryForList(
                "select payload::text as t from " + schema + ".sample where sample_id = ?", sampleId);
        return samples.isEmpty() ? null : String.valueOf(samples.get(0).get("t"));
    }

    private JsonNodeLite parse(String raw) {
        return JsonNodeLite.of(raw, crypto.mapper());
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (value instanceof Instant i) {
            return i;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value)).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** Tiny null-safe JSON field reader so this service has no direct Jackson coupling. */
    private static final class JsonNodeLite {
        private final com.fasterxml.jackson.databind.JsonNode node;

        private JsonNodeLite(com.fasterxml.jackson.databind.JsonNode node) {
            this.node = node;
        }

        static JsonNodeLite of(String raw, com.fasterxml.jackson.databind.ObjectMapper mapper) {
            if (raw == null || raw.isBlank() || "null".equals(raw)) {
                return null;
            }
            try {
                return new JsonNodeLite(mapper.readTree(raw));
            } catch (Exception e) {
                return null;
            }
        }

        boolean has(String field) {
            return node != null && node.hasNonNull(field);
        }

        Long longField(String field) {
            return has(field) ? node.get(field).asLong() : null;
        }

        String text(String field) {
            return has(field) ? node.get(field).asText() : null;
        }
    }
}
