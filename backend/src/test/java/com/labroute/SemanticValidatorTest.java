package com.labroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labroute.service.CryptoSupport;
import com.labroute.service.SemanticValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SemanticValidator validator =
            new SemanticValidator(new CryptoSupport(mapper));

    private com.fasterxml.jackson.databind.JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void validResultClearsAllChecks() throws Exception {
        var r = validator.validate("RESULT", json("""
                {"resultId":"R-1","sampleId":"S-1","analyteCode":"GLU","value":"5.1",
                 "unit":"mmol/L","refLow":"3.9","refHigh":"5.5","measuredAt":"2026-01-01T00:00:00Z"}
                """));
        assertThat(r.status()).isEqualTo("VALID");
        assertThat(r.rejected()).isFalse();
    }

    @Test
    void missingUnitIsRejected() throws Exception {
        var r = validator.validate("RESULT", json("""
                {"resultId":"R-1","sampleId":"S-1","value":"5.1","refLow":"3.9","refHigh":"5.5"}
                """));
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.findings()).anyMatch(f -> f.code().equals("UNIT_MISSING"));
    }

    @Test
    void invertedReferenceIntervalIsRejected() throws Exception {
        var r = validator.validate("RESULT", json("""
                {"resultId":"R-1","sampleId":"S-1","value":"5.1","unit":"mmol/L","refLow":"6.0","refHigh":"3.0"}
                """));
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.findings()).anyMatch(f -> f.code().equals("REFERENCE_INTERVAL_INVERTED"));
    }

    @Test
    void unknownUnitIsFlaggedNotRejected() throws Exception {
        var r = validator.validate("RESULT", json("""
                {"resultId":"R-1","sampleId":"S-1","value":"5.1","unit":"furlongs/L","refLow":"3","refHigh":"6"}
                """));
        assertThat(r.status()).isEqualTo("FLAGGED");
        assertThat(r.findings()).anyMatch(f -> f.code().equals("UNIT_UNRECOGNISED"));
    }

    @Test
    void malformedPrimaryKeyIsRejected() throws Exception {
        var r = validator.validate("SAMPLE", json("""
                {"sampleId":"bad key with spaces","chainVersion":1}
                """));
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.findings()).anyMatch(f -> f.code().equals("PRIMARY_KEY_MALFORMED"));
    }

    @Test
    void revisionSupersedingHigherRevisionIsRejected() throws Exception {
        var r = validator.validate("REPORT_REVISION", json("""
                {"reportId":"REP-1","sampleId":"S-1","revision":2,"supersedesRevision":5,
                 "content":{"rows":[]}}
                """));
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.findings()).anyMatch(f -> f.code().equals("REVISION_RELATION_INVALID"));
    }

    @Test
    void revisionGapIsFlagged() throws Exception {
        var r = validator.validate("REPORT_REVISION", json("""
                {"reportId":"REP-1","sampleId":"S-1","revision":4,"supersedesRevision":2,
                 "content":{"rows":[]}}
                """));
        assertThat(r.status()).isEqualTo("FLAGGED");
        assertThat(r.findings()).anyMatch(f -> f.code().equals("REVISION_GAP"));
    }

    @Test
    void unknownMessageTypeIsRejected() throws Exception {
        var r = validator.validate("TELEPATHY", json("{}"));
        assertThat(r.status()).isEqualTo("REJECTED");
    }
}
