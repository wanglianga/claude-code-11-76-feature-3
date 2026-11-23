package com.labroute.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shadow diff triage.
 *
 * Turns a raw shadow-render comparison into typed, severity-ranked findings the enable gate
 * can reason about. Not every divergence is a defect:
 *
 *   AGE_BOUNDARY        the patient crosses the child/adult band edge - an expected,
 *                       explainable interpretation change.
 *   INSTRUMENT_SWITCH   the result was measured on a different instrument whose mapped
 *                       interval differs - expected when the instrument change is catalogued.
 *   RULE_WITHDRAWN      the candidate rule version was withdrawn after release - blocking:
 *                       a withdrawn rule must never serve a new interpretation.
 *   INTERVAL_DRIFT      numeric bounds changed for the same analyte/instrument/band context.
 *   FLAG_CHANGE         LOW/NORMAL/HIGH differs between baseline and candidate.
 *   UNIT_MISMATCH       units disagree - blocking until reconciled.
 *   NO_RULE             no catalogued rule resolves the context - blocking.
 *   RENDER_ONLY         only text/format changed, clinical flag identical - benign.
 *
 * Severities: BENIGN / EXPECTED / ACTION_REQUIRED / BLOCKING. Only an OPEN BLOCKING finding
 * (or an unresolved ACTION_REQUIRED one, per gate policy) holds the enable gate closed.
 */
@Service
public class ShadowDiffTriage {

    public static final String SEV_BENIGN = "BENIGN";
    public static final String SEV_EXPECTED = "EXPECTED";
    public static final String SEV_ACTION = "ACTION_REQUIRED";
    public static final String SEV_BLOCKING = "BLOCKING";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_DISMISSED = "DISMISSED";

    private final JdbcTemplate jdbc;
    private final CryptoSupport crypto;

    public ShadowDiffTriage(JdbcTemplate jdbc, CryptoSupport crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public record Diff(String diffType, String severity, String resultId, String analyteCode,
                       UUID ruleId, UUID ruleSetId, Map<String, Object> detail) {
    }

    /**
     * Compares one result row's baseline interpretation against the candidate and emits zero
     * or more typed diffs. Inputs are the per-result maps produced by the shadow renderer.
     */
    public List<Diff> classify(Map<String, Object> baseline, Map<String, Object> candidate) {
        List<Diff> diffs = new ArrayList<>();
        String resultId = str(candidate, "resultId", str(baseline, "resultId", null));
        String analyte = str(candidate, "analyteCode", str(baseline, "analyteCode", null));

        String candidateStatus = str(candidate, "status", null);
        if (ReferenceIntervalEvaluator.STATUS_RULE_WITHDRAWN.equals(candidateStatus)) {
            diffs.add(new Diff("RULE_WITHDRAWN", SEV_BLOCKING, resultId, analyte,
                    uuid(candidate, "ruleId"), uuid(candidate, "ruleSetId"),
                    Map.of("reason", str(candidate, "reason", "rule set withdrawn"),
                            "baselineFlag", str(baseline, "flag", null),
                            "candidateFlag", str(candidate, "flag", null))));
            return diffs;
        }
        if (ReferenceIntervalEvaluator.STATUS_NO_RULE.equals(candidateStatus)) {
            diffs.add(new Diff("NO_RULE", SEV_BLOCKING, resultId, analyte,
                    null, uuid(candidate, "ruleSetId"),
                    Map.of("reason", str(candidate, "reason", "no rule resolves this context"))));
            return diffs;
        }
        if (ReferenceIntervalEvaluator.STATUS_UNIT_MISMATCH.equals(candidateStatus)) {
            diffs.add(new Diff("UNIT_MISMATCH", SEV_BLOCKING, resultId, analyte,
                    uuid(candidate, "ruleId"), uuid(candidate, "ruleSetId"),
                    Map.of("reason", str(candidate, "reason", "unit mismatch"),
                            "baselineUnit", str(baseline, "unit", null),
                            "candidateUnit", str(candidate, "unit", null))));
            return diffs;
        }

        String baseInstrument = str(baseline, "instrumentCode", null);
        String candInstrument = str(candidate, "instrumentCode", null);
        boolean instrumentSwitch = baseInstrument != null && candInstrument != null
                && !baseInstrument.equalsIgnoreCase(candInstrument);
        boolean ageBandChanged = !ObjectsEquals(str(baseline, "ageBand", null), str(candidate, "ageBand", null))
                || !ObjectsEquals(longVal(baseline, "ageLowDays"), longVal(candidate, "ageLowDays"))
                || !ObjectsEquals(longVal(baseline, "ageHighDays"), longVal(candidate, "ageHighDays"));
        boolean boundsChanged = !bdEquals(decimal(baseline, "refLow"), decimal(candidate, "refLow"))
                || !bdEquals(decimal(baseline, "refHigh"), decimal(candidate, "refHigh"));
        boolean flagChanged = !ObjectsEquals(str(baseline, "flag", null), str(candidate, "flag", null));

        if (instrumentSwitch) {
            diffs.add(new Diff("INSTRUMENT_SWITCH", SEV_EXPECTED, resultId, analyte,
                    uuid(candidate, "ruleId"), uuid(candidate, "ruleSetId"),
                    Map.of("baselineInstrument", baseInstrument, "candidateInstrument", candInstrument,
                            "baselineInterval", interval(baseline), "candidateInterval", interval(candidate),
                            "flagChanged", flagChanged)));
        }
        if (ageBandChanged) {
            diffs.add(new Diff("AGE_BOUNDARY", SEV_EXPECTED, resultId, analyte,
                    uuid(candidate, "ruleId"), uuid(candidate, "ruleSetId"),
                    Map.of("baselineAgeBand", str(baseline, "ageBand", null),
                            "candidateAgeBand", str(candidate, "ageBand", null),
                            "baselineInterval", interval(baseline), "candidateInterval", interval(candidate),
                            "flagChanged", flagChanged)));
        }
        if (boundsChanged && !instrumentSwitch && !ageBandChanged) {
            diffs.add(new Diff("INTERVAL_DRIFT", SEV_ACTION, resultId, analyte,
                    uuid(candidate, "ruleId"), uuid(candidate, "ruleSetId"),
                    Map.of("baselineInterval", interval(baseline), "candidateInterval", interval(candidate))));
        }
        if (flagChanged) {
            // A clinical flag flip always needs a human acknowledgement, even when the underlying
            // context change (age band / instrument) is itself expected.
            diffs.add(new Diff("FLAG_CHANGE", SEV_ACTION, resultId, analyte,
                    uuid(candidate, "ruleId"), uuid(candidate, "ruleSetId"),
                    Map.of("baselineFlag", str(baseline, "flag", null),
                            "candidateFlag", str(candidate, "flag", null),
                            "value", str(candidate, "value", null))));
        }
        if (diffs.isEmpty()
                && !ObjectsEquals(str(baseline, "renderedText", null), str(candidate, "renderedText", null))) {
            diffs.add(new Diff("RENDER_ONLY", SEV_BENIGN, resultId, analyte,
                    uuid(candidate, "ruleId"), uuid(candidate, "ruleSetId"),
                    Map.of("note", "clinical flag and interval identical; only rendered text differs")));
        }
        return diffs;
    }

    @Transactional
    public int persist(String tenantId, UUID shadowId, String reportId, Integer revision, List<Diff> diffs) {
        int n = 0;
        for (Diff d : diffs) {
            jdbc.update("""
                    insert into lab.shadow_triage(triage_id, shadow_id, tenant_id, report_id, revision,
                      result_id, analyte_code, diff_type, severity, status, rule_id, rule_set_id, detail)
                    values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, 'OPEN', ?::uuid, ?::uuid, ?::jsonb)
                    """, UUID.randomUUID().toString(), shadowId.toString(), tenantId, reportId, revision,
                    d.resultId(), d.analyteCode(), d.diffType(), d.severity(),
                    d.ruleId() == null ? null : d.ruleId().toString(),
                    d.ruleSetId() == null ? null : d.ruleSetId().toString(),
                    crypto.canonicalJson(d.detail()));
            n++;
        }
        return n;
    }

    @Transactional
    public Map<String, Object> resolve(String tenantId, String triageId, String decision, String note) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from lab.shadow_triage where triage_id = ?::uuid and tenant_id = ?::uuid
                """, triageId, tenantId);
        if (rows.isEmpty()) {
            throw com.labroute.web.ApiException.notFound("TRIAGE_NOT_FOUND", "unknown triage finding");
        }
        String status = switch (decision == null ? "" : decision.toUpperCase()) {
            case "ACCEPT" -> STATUS_ACCEPTED;
            case "DISMISS" -> STATUS_DISMISSED;
            default -> throw com.labroute.web.ApiException.badRequest("TRIAGE_DECISION_INVALID",
                    "decision must be ACCEPT or DISMISS");
        };
        jdbc.update("""
                update lab.shadow_triage set status = ?, resolution_note = ?, resolved_at = now()
                where triage_id = ?::uuid
                """, status, note, triageId);
        return get(tenantId, triageId);
    }

    public Map<String, Object> get(String tenantId, String triageId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select triage_id::text as "triageId", shadow_id::text as "shadowId", report_id as "reportId",
                       revision, result_id as "resultId", analyte_code as "analyteCode",
                       diff_type as "diffType", severity, status, detail,
                       resolution_note as "resolutionNote", created_at as "createdAt",
                       resolved_at as "resolvedAt"
                from lab.shadow_triage where triage_id = ?::uuid and tenant_id = ?::uuid
                """, triageId, tenantId);
        if (rows.isEmpty()) {
            throw com.labroute.web.ApiException.notFound("TRIAGE_NOT_FOUND", "unknown triage finding");
        }
        return rows.get(0);
    }

    public List<Map<String, Object>> list(String tenantId, String status) {
        String sql = """
                select triage_id::text as "triageId", shadow_id::text as "shadowId", report_id as "reportId",
                       revision, result_id as "resultId", analyte_code as "analyteCode",
                       diff_type as "diffType", severity, status, detail,
                       resolution_note as "resolutionNote", created_at as "createdAt",
                       resolved_at as "resolvedAt"
                from lab.shadow_triage where tenant_id = ?::uuid
                """;
        if (status != null && !status.isBlank()) {
            return jdbc.queryForList(sql + " and status = ? order by created_at desc limit 200", tenantId, status);
        }
        return jdbc.queryForList(sql + " order by created_at desc limit 200", tenantId);
    }

    public Map<String, Object> openCounts(String tenantId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where status = 'OPEN') as open,
                       count(*) filter (where status = 'OPEN' and severity = 'BLOCKING') as blocking,
                       count(*) filter (where status = 'OPEN' and severity = 'ACTION_REQUIRED') as action,
                       count(*) filter (where status = 'OPEN' and severity = 'EXPECTED') as expected,
                       count(*) filter (where status = 'OPEN' and severity = 'BENIGN') as benign
                from lab.shadow_triage where tenant_id = ?::uuid
                """, tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, v instanceof Number n ? n.longValue() : v));
        return out;
    }

    public long openBlockingCount(String tenantId) {
        Long n = jdbc.queryForObject("""
                select count(*) from lab.shadow_triage
                where tenant_id = ?::uuid and status = 'OPEN' and severity = 'BLOCKING'
                """, Long.class, tenantId);
        return n == null ? 0 : n;
    }

    /** Closes every OPEN finding for a tenant (operator bulk triage). Returns the count closed. */
    @Transactional
    public int resolveAllOpen(String tenantId, String note) {
        return jdbc.update("""
                update lab.shadow_triage set status = 'DISMISSED', resolution_note = ?, resolved_at = now()
                where tenant_id = ?::uuid and status = 'OPEN'
                """, note, tenantId);
    }

    public long openActionCount(String tenantId) {
        Long n = jdbc.queryForObject("""
                select count(*) from lab.shadow_triage
                where tenant_id = ?::uuid and status = 'OPEN' and severity = 'ACTION_REQUIRED'
                """, Long.class, tenantId);
        return n == null ? 0 : n;
    }

    // ---------- small helpers ----------

    private static String str(Map<String, Object> m, String key, String dflt) {
        Object v = m == null ? null : m.get(key);
        return v == null ? dflt : String.valueOf(v);
    }

    private static Long longVal(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private static java.math.BigDecimal decimal(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null || String.valueOf(v).isBlank() || "null".equals(String.valueOf(v))) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Numeric comparison so "3.90" and "3.9" are the same interval bound. */
    private static boolean bdEquals(java.math.BigDecimal a, java.math.BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(b) == 0;
    }

    private static UUID uuid(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null || String.valueOf(v).isBlank() || "null".equals(String.valueOf(v))) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean ObjectsEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private Map<String, Object> interval(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refLow", m == null ? null : m.get("refLow"));
        out.put("refHigh", m == null ? null : m.get("refHigh"));
        out.put("unit", m == null ? null : m.get("unit"));
        return out;
    }
}
