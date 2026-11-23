package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.labroute.model.Tenant;
import com.labroute.web.ApiException;
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
import java.util.UUID;

/**
 * Shadow renderer.
 *
 * For every report revision it produces two interpretations of the same measured results:
 *
 *   baseline  - the interpretation the legacy space would have served, evaluated from the
 *               reference interval carried on each historical instrument_result row.
 *   candidate - the interpretation the unified semantic directory produces, resolved through
 *               the {@link ReferenceIntervalEvaluator} at the report point in time using the
 *               candidate rule set version.
 *
 * The two rendered documents are hashed and compared; every per-result difference is handed
 * to the {@link ShadowDiffTriage} for typed classification. The renderer also owns the two
 * history strategies named in the brief:
 *
 *   MATERIALIZED - each historical report's old interpretation is frozen into
 *                  lab.report_interpretation up front: reads are stable and cheap, at the cost
 *                  of maintaining a materialised row per report/result.
 *   REPLAYED     - only rule versions are retained and the interpretation is rebuilt from the
 *                  rule set in force at the report time: little storage, but every read depends
 *                  on a reliable point-in-time replay (which the effective-window schema gives).
 */
@Service
public class ShadowRenderer {

    public static final String RESULT_MATCH = "MATCH";
    public static final String RESULT_DIVERGED = "DIVERGED";
    public static final String RESULT_INCONCLUSIVE = "INCONCLUSIVE";

    private final JdbcTemplate jdbc;
    private final CryptoSupport crypto;
    private final ReferenceIntervalEvaluator evaluator;
    private final ShadowDiffTriage triage;

    public ShadowRenderer(JdbcTemplate jdbc, CryptoSupport crypto,
                          ReferenceIntervalEvaluator evaluator, ShadowDiffTriage triage) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.evaluator = evaluator;
        this.triage = triage;
    }

    public record ShadowResult(UUID shadowId, String reportId, int revision, String result,
                               String baselineHash, String candidateHash, int diffCount,
                               List<Map<String, Object>> baselineRows,
                               List<Map<String, Object>> candidateRows,
                               List<ShadowDiffTriage.Diff> diffs,
                               List<Map<String, Object>> triageRows) {
    }

    // ---------- public API ----------

    /** Full shadow pass for one report revision; persists shadow_render and triage rows. */
    @Transactional
    public ShadowResult renderReport(Tenant tenant, String space, String reportId, Integer revision,
                                     UUID candidateRuleSetId, String reason) {
        String schema = "NEW".equalsIgnoreCase(space) ? tenant.newSchema() : tenant.oldSchema();
        Map<String, Object> report = loadReport(schema, reportId, revision);
        Instant reportTime = instant(report.get("created_at"));
        String sampleId = (String) report.get("sample_id");
        List<Map<String, Object>> results = loadResults(schema, sampleId);

        List<Map<String, Object>> baselineRows = new ArrayList<>();
        List<Map<String, Object>> candidateRows = new ArrayList<>();
        List<ShadowDiffTriage.Diff> diffs = new ArrayList<>();

        for (Map<String, Object> result : results) {
            PatientContext ctx = patientContext(tenant, schema, sampleId, result);
            Map<String, Object> baseline = renderLegacy(result, ctx);
            Map<String, Object> candidate = renderCandidate(tenant, result, ctx, reportTime, candidateRuleSetId);
            tagLegacyInstrumentMapping(tenant, candidateRuleSetId, baseline, candidate, ctx);
            baselineRows.add(baseline);
            candidateRows.add(candidate);
            diffs.addAll(triage.classify(baseline, candidate));
        }

        String baselineHash = crypto.sha256Canonical(baselineRows);
        String candidateHash = crypto.sha256Canonical(candidateRows);
        boolean candidateHealthy = candidateRows.stream().allMatch(
                r -> ReferenceIntervalEvaluator.STATUS_OK.equals(String.valueOf(r.get("status"))));
        String shadowResult = !candidateHealthy ? RESULT_INCONCLUSIVE
                : baselineHash.equals(candidateHash) ? RESULT_MATCH : RESULT_DIVERGED;

        String shadowId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into lab.shadow_render(shadow_id, tenant_id, report_id, revision, run_reason,
                  baseline_label, candidate_label, baseline_hash, candidate_hash, baseline_payload,
                  candidate_payload, result, diff_count)
                values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, shadowId, tenant.tenantId(), reportId,
                ((Number) report.get("revision")).intValue(), reason == null ? "MANUAL" : reason,
                "LEGACY_SPACE_INTERVAL",
                candidateRuleSetId == null ? "DIRECTORY_AT_REPORT_TIME" : "CANDIDATE_RULE_SET",
                baselineHash, candidateHash,
                crypto.canonicalJson(baselineRows), crypto.canonicalJson(candidateRows),
                shadowResult, diffs.size());
        triage.persist(tenant.tenantId(), UUID.fromString(shadowId), reportId,
                ((Number) report.get("revision")).intValue(), diffs);
        List<Map<String, Object>> triageRows = jdbc.queryForList("""
                select triage_id::text as "triageId", diff_type as "diffType", severity, status,
                       result_id as "resultId", detail, created_at as "createdAt"
                from lab.shadow_triage where shadow_id = ?::uuid order by created_at
                """, shadowId);
        return new ShadowResult(UUID.fromString(shadowId), reportId, ((Number) report.get("revision")).intValue(),
                shadowResult, baselineHash, candidateHash, diffs.size(),
                baselineRows, candidateRows, diffs, triageRows);
    }

    /**
     * Bulk shadow pass over every report revision in a space, also materialising the chosen
     * history strategy's interpretation rows.
     */
    @Transactional
    public Map<String, Object> renderSpace(Tenant tenant, String space, UUID candidateRuleSetId,
                                           String reason, String historyMode, int limit) {
        String schema = "NEW".equalsIgnoreCase(space) ? tenant.newSchema() : tenant.oldSchema();
        List<Map<String, Object>> reports = jdbc.queryForList("""
                select report_id, revision from %s.report_revision order by report_id, revision limit %d
                """.formatted(schema, Math.min(Math.max(limit, 1), 500)));
        List<Map<String, Object>> runs = new ArrayList<>();
        int match = 0;
        int diverged = 0;
        int inconclusive = 0;
        for (Map<String, Object> r : reports) {
            String reportId = (String) r.get("report_id");
            int rev = ((Number) r.get("revision")).intValue();
            ShadowResult sr = renderReport(tenant, space, reportId, rev, candidateRuleSetId, reason);
            if ("MATERIALIZED".equals(historyMode)) {
                materializeOne(tenant, schema, reportId, rev, sr.baselineRows(), "MATERIALIZED");
            } else if ("REPLAYED".equals(historyMode)) {
                materializeOne(tenant, schema, reportId, rev, sr.candidateRows(), "REPLAYED");
            }
            switch (sr.result()) {
                case RESULT_MATCH -> match++;
                case RESULT_DIVERGED -> diverged++;
                default -> inconclusive++;
            }
            runs.add(Map.of("reportId", reportId, "revision", rev, "result", sr.result(),
                    "diffCount", sr.diffCount()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant.code());
        out.put("space", "NEW".equalsIgnoreCase(space) ? "NEW" : "OLD");
        out.put("historyMode", historyMode);
        out.put("reportsProcessed", reports.size());
        out.put("match", match);
        out.put("diverged", diverged);
        out.put("inconclusive", inconclusive);
        out.put("runs", runs);
        return out;
    }

    /** Freeze the legacy interpretation of every historical report (the materialised strategy). */
    @Transactional
    public Map<String, Object> materializeLegacy(Tenant tenant, String space, int limit) {
        return renderSpace(tenant, space, null, "HISTORY_MATERIALIZE", "MATERIALIZED", limit);
    }

    /** Rebuild every historical interpretation from the rule versions at report time. */
    @Transactional
    public Map<String, Object> replayHistory(Tenant tenant, String space, UUID ruleSetId, int limit) {
        return renderSpace(tenant, space, ruleSetId, "HISTORY_REPLAY", "REPLAYED", limit);
    }

    public List<Map<String, Object>> listInterpretations(String tenantId, String reportId, Integer revision) {
        String sql = """
                select interpretation_id::text as "interpretationId", report_id as "reportId", revision,
                       result_id as "resultId", analyte_code as "analyteCode", template_id::text as "templateId",
                       rule_set_id::text as "ruleSetId", rule_id::text as "ruleId",
                       rule_set_version as "ruleSetVersion", history_mode as "historyMode",
                       report_time as "reportTime", value_num as "value", unit,
                       ref_low as "refLow", ref_high as "refHigh", flag, rendered_text as "renderedText",
                       content_hash as "contentHash", created_at as "createdAt"
                from lab.report_interpretation where tenant_id = ?::uuid and report_id = ?
                """;
        if (revision != null) {
            return jdbc.queryForList(sql + " and revision = ? order by result_id", tenantId, reportId, revision);
        }
        return jdbc.queryForList(sql + " order by revision desc, result_id", tenantId, reportId);
    }

    public List<Map<String, Object>> listRenders(String tenantId, String reportId) {
        String sql = """
                select shadow_id::text as "shadowId", report_id as "reportId", revision, run_reason as "runReason",
                       baseline_label as "baselineLabel", candidate_label as "candidateLabel",
                       baseline_hash as "baselineHash", candidate_hash as "candidateHash",
                       result, diff_count as "diffCount", created_at as "createdAt"
                from lab.shadow_render where tenant_id = ?::uuid
                """;
        if (reportId != null && !reportId.isBlank()) {
            return jdbc.queryForList(sql + " and report_id = ? order by created_at desc", tenantId, reportId);
        }
        return jdbc.queryForList(sql + " order by created_at desc limit 100", tenantId);
    }

    public Map<String, Object> renderDetail(String tenantId, String shadowId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select shadow_id::text as "shadowId", report_id as "reportId", revision, run_reason as "runReason",
                       baseline_label as "baselineLabel", candidate_label as "candidateLabel",
                       baseline_hash as "baselineHash", candidate_hash as "candidateHash",
                       baseline_payload as "baselinePayload", candidate_payload as "candidatePayload",
                       result, diff_count as "diffCount", created_at as "createdAt"
                from lab.shadow_render where shadow_id = ?::uuid and tenant_id = ?::uuid
                """, shadowId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SHADOW_NOT_FOUND", "unknown shadow render");
        }
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("triage", jdbc.queryForList("""
                select triage_id::text as "triageId", diff_type as "diffType", severity, status,
                       result_id as "resultId", analyte_code as "analyteCode", detail,
                       resolution_note as "resolutionNote"
                from lab.shadow_triage where shadow_id = ?::uuid order by created_at
                """, shadowId));
        return out;
    }

    // ---------- rendering ----------

    private Map<String, Object> renderLegacy(Map<String, Object> result, PatientContext ctx) {
        BigDecimal value = (BigDecimal) result.get("value_num");
        BigDecimal low = (BigDecimal) result.get("ref_low");
        BigDecimal high = (BigDecimal) result.get("ref_high");
        String unit = (String) result.get("unit");
        String instrument = (String) result.get("instrument_code");
        ReferenceIntervalEvaluator.IntervalOutcome o =
                evaluator.evaluateLegacy(value, low, high, unit, instrument, ctx.ageDays());
        return toMap(result, o, "LEGACY", ctx, null);
    }

    private Map<String, Object> renderCandidate(Tenant tenant, Map<String, Object> result,
                                                PatientContext ctx, Instant reportTime, UUID ruleSetId) {
        BigDecimal value = (BigDecimal) result.get("value_num");
        ReferenceIntervalEvaluator.EvalContext ec = new ReferenceIntervalEvaluator.EvalContext(
                tenant.tenantId(),
                (String) result.get("analyte_code"),
                (String) result.get("instrument_code"),
                ctx.sex(), ctx.ageDays(), value, (String) result.get("unit"),
                reportTime == null ? Instant.now() : reportTime, ruleSetId);
        ReferenceIntervalEvaluator.IntervalOutcome o = evaluator.evaluate(ec);
        return toMap(result, o, "DIRECTORY", ctx, reportTime);
    }

    private Map<String, Object> toMap(Map<String, Object> result,
                                      ReferenceIntervalEvaluator.IntervalOutcome o,
                                      String source, PatientContext ctx, Instant reportTime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resultId", result.get("result_id"));
        m.put("analyteCode", result.get("analyte_code"));
        m.put("instrumentCode", o.instrumentCode());
        m.put("value", result.get("value_num"));
        m.put("unit", o.unit());
        m.put("refLow", o.refLow());
        m.put("refHigh", o.refHigh());
        m.put("flag", o.flag());
        m.put("status", o.status());
        m.put("ruleId", o.ruleId() == null ? null : o.ruleId().toString());
        m.put("ruleSetId", o.ruleSetId() == null ? null : o.ruleSetId().toString());
        m.put("ruleSetCode", o.ruleSetCode());
        m.put("ruleSetVersion", o.ruleSetVersion());
        m.put("ruleSetStatus", o.ruleSetStatus());
        m.put("ageLowDays", o.ageLowDays());
        m.put("ageHighDays", o.ageHighDays());
        m.put("ageBand", ageBandLabel(o.ageLowDays(), o.ageHighDays(), ctx.ageDays(), source));
        m.put("patientAgeDays", ctx.ageDays());
        m.put("reason", o.reason());
        m.put("renderedText", renderedText(result, o));
        m.put("reportTime", reportTime == null ? null : reportTime.toString());
        return m;
    }

    private String renderedText(Map<String, Object> result, ReferenceIntervalEvaluator.IntervalOutcome o) {
        String version = o.ruleSetCode() == null ? "legacy-interval"
                : o.ruleSetCode() + " v" + o.ruleSetVersion();
        return o.flag() + " " + result.get("analyte_code") + " " + result.get("value_num")
                + " " + o.unit() + " [" + o.refLow() + "-" + o.refHigh() + "] (" + version + ")";
    }

    private String ageBandLabel(Long low, Long high, Long patientAge, String source) {
        if ("LEGACY".equals(source)) {
            return patientAge == null ? "LEGACY_NO_BAND" : "LEGACY(age=" + patientAge + "d)";
        }
        if (low == null && high == null) {
            return "ALL_AGES";
        }
        return (low == null ? 0 : low) + "d-" + (high == null ? "open" : high + "d");
    }

    /**
     * Legacy results carry only an interval, not the catalogued instrument dimension. When the
     * legacy interval exactly matches another instrument's catalogued rule for the same analyte
     * and age band, that identifies the historical instrument mapping - so an instrument
     * replacement is classified as INSTRUMENT_SWITCH rather than unexplained interval drift.
     * The baseline row is re-tagged with that rule's instrument and age band; its interval and
     * the HIGH flag it produced stay untouched.
     */
    private void tagLegacyInstrumentMapping(Tenant tenant, UUID candidateRuleSetId,
                                            Map<String, Object> baseline, Map<String, Object> candidate,
                                            PatientContext ctx) {
        if (candidateRuleSetId == null || baseline.get("refLow") == null || baseline.get("refHigh") == null) {
            return;
        }
        long age = ctx.ageDays() == null ? 0L : ctx.ageDays();
        String physicalInstrument = String.valueOf(baseline.get("instrumentCode"));
        List<Map<String, Object>> matches = jdbc.queryForList("""
                select instrument_code, age_low_days, age_high_days from lab.reference_interval_rule
                where rule_set_id = ?::uuid and tenant_id = ?::uuid and analyte_code = ?
                  and status = 'ACTIVE' and instrument_code is not null
                  and ref_low = ?::numeric and ref_high = ?::numeric
                  and age_low_days <= ? and (age_high_days is null or age_high_days >= ?)
                order by (instrument_code = ?) asc limit 1
                """, candidateRuleSetId.toString(), tenant.tenantId(),
                str(baseline, "analyteCode"), baseline.get("refLow"), baseline.get("refHigh"),
                age, age, physicalInstrument);
        if (matches.isEmpty()) {
            return;
        }
        Map<String, Object> mapped = matches.get(0);
        String mappedInstrument = (String) mapped.get("instrument_code");
        if (mappedInstrument.equalsIgnoreCase(physicalInstrument)) {
            return;
        }
        Long low = (Long) mapped.get("age_low_days");
        Long high = (Long) mapped.get("age_high_days");
        baseline.put("instrumentCode", mappedInstrument);
        baseline.put("ageLowDays", low);
        baseline.put("ageHighDays", high);
        baseline.put("ageBand", ageBandLabel(low, high, ctx.ageDays(), "DIRECTORY"));
        baseline.put("legacyInstrumentMapping",
                Map.of("physicalInstrument", physicalInstrument, "mappedFromInterval", mappedInstrument));
    }

    private void materializeOne(Tenant tenant, String schema, String reportId, int revision,
                                List<Map<String, Object>> rows, String historyMode) {
        Map<String, Object> report = loadReport(schema, reportId, revision);
        String sampleId = (String) report.get("sample_id");
        Instant reportTime = instant(report.get("created_at"));
        java.time.OffsetDateTime boundTime = java.time.OffsetDateTime.ofInstant(
                reportTime == null ? Instant.now() : reportTime, java.time.ZoneOffset.UTC);
        for (Map<String, Object> row : rows) {
            String hash = crypto.sha256Canonical(row);
            jdbc.update("""
                    insert into lab.report_interpretation(interpretation_id, tenant_id, report_id, revision,
                      sample_id, result_id, analyte_code, template_id, rule_set_id, rule_id,
                      rule_set_version, history_mode, report_time, value_num, unit, ref_low, ref_high,
                      flag, rendered_text, content_hash)
                    values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, report_id, revision, result_id) do update set
                      template_id = excluded.template_id, rule_set_id = excluded.rule_set_id,
                      rule_id = excluded.rule_id, rule_set_version = excluded.rule_set_version,
                      history_mode = excluded.history_mode, value_num = excluded.value_num,
                      unit = excluded.unit, ref_low = excluded.ref_low, ref_high = excluded.ref_high,
                      flag = excluded.flag, rendered_text = excluded.rendered_text,
                      content_hash = excluded.content_hash
                    """, UUID.randomUUID().toString(), tenant.tenantId(), reportId, revision, sampleId,
                    str(row, "resultId"), str(row, "analyteCode"),
                    uuidOrNull(row, "templateId"), uuidOrNull(row, "ruleSetId"), uuidOrNull(row, "ruleId"),
                    row.get("ruleSetCode") == null ? null
                            : row.get("ruleSetCode") + "#" + row.get("ruleSetVersion"),
                    historyMode, boundTime,
                    (BigDecimal) row.get("value"), row.get("unit"),
                    (BigDecimal) row.get("refLow"), (BigDecimal) row.get("refHigh"),
                    str(row, "flag"), str(row, "renderedText"), hash);
        }
    }

    // ---------- data loading ----------

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
            throw ApiException.notFound("REPORT_NOT_FOUND",
                    "report " + reportId + " not found in space schema " + schema);
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> loadResults(String schema, String sampleId) {
        return jdbc.queryForList("""
                select result_id, sample_id, instrument_code, analyte_code, value_num, unit,
                       ref_low, ref_high, measured_at, status, payload::text as payload_text
                from %s.instrument_result where sample_id = ? order by result_id
                """.formatted(schema), sampleId);
    }

    private PatientContext patientContext(Tenant tenant, String schema, String sampleId,
                                          Map<String, Object> result) {
        Long ageDays = null;
        String sex = null;
        JsonNode resultPayload = readJsonNullable(String.valueOf(result.get("payload_text")));
        if (resultPayload != null) {
            ageDays = longFromNode(resultPayload, "patientAgeDays", ageDays);
            sex = textFromNode(resultPayload, "patientSex", sex);
            if (ageDays == null && resultPayload.hasNonNull("patientDob")) {
                ageDays = dobToAge(resultPayload.path("patientDob").asText(),
                        resultPayload.path("measuredAt").asText(null));
            }
        }
        List<Map<String, Object>> samples = jdbc.queryForList("""
                select payload::text as payload_text from %s.sample where sample_id = ?
                """.formatted(schema), sampleId);
        if (!samples.isEmpty()) {
            JsonNode samplePayload = readJsonNullable(String.valueOf(samples.get(0).get("payload_text")));
            if (samplePayload != null) {
                ageDays = longFromNode(samplePayload, "patientAgeDays", ageDays);
                sex = textFromNode(samplePayload, "patientSex", sex);
                if (ageDays == null && samplePayload.hasNonNull("patientDob")) {
                    ageDays = dobToAge(samplePayload.path("patientDob").asText(),
                            samplePayload.path("collectedAt").asText(null));
                }
            }
        }
        return new PatientContext(ageDays, sex);
    }

    private JsonNode readJsonNullable(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return crypto.readTree(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Long longFromNode(JsonNode node, String field, Long current) {
        if (current != null || !node.hasNonNull(field)) {
            return current;
        }
        return node.get(field).asLong();
    }

    private String textFromNode(JsonNode node, String field, String current) {
        if (current != null || !node.hasNonNull(field)) {
            return current;
        }
        return node.get(field).asText();
    }

    private Long dobToAge(String dob, String at) {
        try {
            OffsetDateTime dobDate = OffsetDateTime.parse(dob);
            OffsetDateTime atDate = at == null ? OffsetDateTime.now() : OffsetDateTime.parse(at);
            return ReferenceIntervalEvaluator.ageDays(dobDate, atDate);
        } catch (Exception e) {
            return null;
        }
    }

    private record PatientContext(Long ageDays, String sex) {
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

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String uuidOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank() || "null".equals(String.valueOf(v))) {
            return null;
        }
        return String.valueOf(v);
    }
}
