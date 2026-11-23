package com.labroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labroute.service.CryptoSupport;
import com.labroute.service.ReferenceIntervalEvaluator;
import com.labroute.service.ShadowDiffTriage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the typed diff classifier. No database is involved: the classifier
 * operates on the per-result maps the shadow renderer builds.
 */
class ShadowDiffTriageTest {

    private final ShadowDiffTriage triage = new ShadowDiffTriage(null, new CryptoSupport(new ObjectMapper()));

    private Map<String, Object> row(String resultId, String instrument, String flag, String status,
                                    String low, String high, Long ageLow, Long ageHigh, String ageBand,
                                    String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resultId", resultId);
        m.put("analyteCode", "GLU");
        m.put("instrumentCode", instrument);
        m.put("flag", flag);
        m.put("status", status);
        m.put("refLow", low);
        m.put("refHigh", high);
        m.put("unit", "mmol/L");
        m.put("value", new BigDecimal("5.55"));
        m.put("ageLowDays", ageLow);
        m.put("ageHighDays", ageHigh);
        m.put("ageBand", ageBand);
        m.put("renderedText", text);
        return m;
    }

    @Test
    void identicalInterpretationsProduceNoDiff() {
        Map<String, Object> base = row("R1", "AU5800", "NORMAL", "OK",
                "3.90", "6.10", 6570L, null, "6570d-open", "same text");
        Map<String, Object> cand = row("R1", "AU5800", "NORMAL", "OK",
                "3.9", "6.1", 6570L, null, "6570d-open", "same text");
        List<ShadowDiffTriage.Diff> diffs = triage.classify(base, cand);
        assertThat(diffs).isEmpty();
    }

    @Test
    void childAdultBandHandoffIsClassifiedAsAgeBoundary() {
        // Legacy result carried the adult interval even for a 10-year-old; the directory resolves
        // the pediatric band, flipping HIGH -> NORMAL.
        Map<String, Object> base = row("R1", "AU5800", "HIGH", "OK",
                "3.9", "5.5", null, null, "LEGACY(age=3650d)", "legacy text");
        Map<String, Object> cand = row("R1", "AU5800", "NORMAL", "OK",
                "3.3", "5.6", 0L, 6205L, "0d-6205d", "directory text");
        List<ShadowDiffTriage.Diff> diffs = triage.classify(base, cand);
        assertThat(diffs).anyMatch(d -> "AGE_BOUNDARY".equals(d.diffType())
                && ShadowDiffTriage.SEV_EXPECTED.equals(d.severity()));
        assertThat(diffs).anyMatch(d -> "FLAG_CHANGE".equals(d.diffType())
                && ShadowDiffTriage.SEV_ACTION.equals(d.severity()));
        // Numeric bounds written differently (5.50 vs 5.5) must not manufacture an INTERVAL_DRIFT.
        assertThat(diffs).noneMatch(d -> "INTERVAL_DRIFT".equals(d.diffType()));
    }

    @Test
    void instrumentSwitchIsExpectedAndCarriesBothIntervals() {
        Map<String, Object> base = row("R2", "AU5800", "HIGH", "OK",
                "3.9", "6.1", 6570L, null, "6570d-open", "old instrument");
        Map<String, Object> cand = row("R2", "BS2000", "NORMAL", "OK",
                "4.0", "6.3", 6570L, null, "6570d-open", "new instrument");
        List<ShadowDiffTriage.Diff> diffs = triage.classify(base, cand);
        ShadowDiffTriage.Diff sw = diffs.stream().filter(d -> "INSTRUMENT_SWITCH".equals(d.diffType()))
                .findFirst().orElseThrow();
        assertThat(sw.severity()).isEqualTo(ShadowDiffTriage.SEV_EXPECTED);
        assertThat(sw.detail()).containsKeys("baselineInstrument", "candidateInstrument");
    }

    @Test
    void withdrawnRuleIsBlockingAndShortCircuits() {
        Map<String, Object> base = row("R3", "AU5800", "NORMAL", "OK",
                "3.9", "6.1", 6570L, null, "6570d-open", "legacy");
        Map<String, Object> cand = row("R3", "AU5800", "INDETERMINATE",
                ReferenceIntervalEvaluator.STATUS_RULE_WITHDRAWN, null, null, null, null, null, "candidate");
        cand.put("reason", "rule set v2 withdrawn after release");
        List<ShadowDiffTriage.Diff> diffs = triage.classify(base, cand);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).diffType()).isEqualTo("RULE_WITHDRAWN");
        assertThat(diffs.get(0).severity()).isEqualTo(ShadowDiffTriage.SEV_BLOCKING);
    }

    @Test
    void noRuleAndUnitMismatchAreBlocking() {
        Map<String, Object> base = row("R4", "AU5800", "NORMAL", "OK",
                "3.9", "6.1", 6570L, null, "6570d-open", "legacy");
        Map<String, Object> noRule = row("R4", "AU5800", "INDETERMINATE",
                ReferenceIntervalEvaluator.STATUS_NO_RULE, null, null, null, null, null, "candidate");
        assertThat(triage.classify(base, noRule).get(0).severity()).isEqualTo(ShadowDiffTriage.SEV_BLOCKING);

        Map<String, Object> unit = row("R4", "AU5800", "INDETERMINATE",
                ReferenceIntervalEvaluator.STATUS_UNIT_MISMATCH, "3.9", "6.1", 6570L, null, "6570d-open", "x");
        assertThat(triage.classify(base, unit).get(0).diffType()).isEqualTo("UNIT_MISMATCH");
        assertThat(triage.classify(base, unit).get(0).severity()).isEqualTo(ShadowDiffTriage.SEV_BLOCKING);
    }

    @Test
    void pureIntervalDriftWithoutContextChangeNeedsAction() {
        Map<String, Object> base = row("R5", "AU5800", "HIGH", "OK",
                "3.9", "6.1", 6570L, null, "6570d-open", "text");
        Map<String, Object> cand = row("R5", "AU5800", "NORMAL", "OK",
                "3.9", "6.3", 6570L, null, "6570d-open", "text");
        List<ShadowDiffTriage.Diff> diffs = triage.classify(base, cand);
        assertThat(diffs).anyMatch(d -> "INTERVAL_DRIFT".equals(d.diffType())
                && ShadowDiffTriage.SEV_ACTION.equals(d.severity()));
    }

    @Test
    void renderOnlyDifferenceIsBenign() {
        Map<String, Object> base = row("R6", "AU5800", "NORMAL", "OK",
                "3.9", "6.1", 6570L, null, "6570d-open", "old wording");
        Map<String, Object> cand = row("R6", "AU5800", "NORMAL", "OK",
                "3.9", "6.1", 6570L, null, "6570d-open", "new wording");
        List<ShadowDiffTriage.Diff> diffs = triage.classify(base, cand);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).diffType()).isEqualTo("RENDER_ONLY");
        assertThat(diffs.get(0).severity()).isEqualTo(ShadowDiffTriage.SEV_BENIGN);
    }
}
