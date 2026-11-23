package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Semantic validator for intake messages. Three classes of outcome:
 *  - REJECTED: the message cannot be attributed or ordered at all (bad primary key, bad revision shape).
 *  - FLAGGED : structure is storable but semantics are suspect (unknown unit, inverted reference
 *              interval, value outside a sane range). Data is persisted so nothing is lost, but any
 *              derived report is withheld from "published" and shown as pending verification.
 *  - VALID   : clears primary key, unit, reference interval and revision-chain checks.
 */
@Service
public class SemanticValidator {

    private static final Set<String> KNOWN_UNITS = Set.of(
            "mg/dL", "mmol/L", "g/L", "g/dL", "umol/L", "%", "10^9/L", "10^12/L",
            "U/L", "IU/L", "ng/mL", "pg/mL", "mEq/L", "mL/min/1.73m2", "s", "ratio", "index");

    public record Finding(String code, String severity, String message, JsonNode detail) {
    }

    public record Result(String status, List<Finding> findings) {
        public boolean rejected() {
            return "REJECTED".equals(status);
        }
    }

    private final CryptoSupport crypto;

    public SemanticValidator(CryptoSupport crypto) {
        this.crypto = crypto;
    }

    public Result validate(String messageType, JsonNode payload) {
        List<Finding> f = new ArrayList<>();
        switch (messageType) {
            case "SAMPLE" -> validateSample(payload, f);
            case "RESULT" -> validateResult(payload, f);
            case "REPORT_REVISION" -> validateReport(payload, f);
            case "OBJECT" -> validateObject(payload, f);
            default -> f.add(finding("UNKNOWN_MESSAGE_TYPE", "ERROR",
                    "message type " + messageType + " is not routable", null));
        }
        boolean error = f.stream().anyMatch(x -> "ERROR".equals(x.severity()));
        boolean warn = f.stream().anyMatch(x -> "WARN".equals(x.severity()));
        String status = error ? "REJECTED" : warn ? "FLAGGED" : "VALID";
        return new Result(status, f);
    }

    private void validateSample(JsonNode p, List<Finding> f) {
        primaryKey(p, "sampleId", "^[A-Za-z0-9_.:-]{3,64}$", f);
        int version = intField(p, "chainVersion", 1);
        if (version < 1) {
            f.add(finding("CHAIN_VERSION_INVALID", "ERROR", "chainVersion must be >= 1", null));
        }
        if (p.hasNonNull("parentSampleId") && p.get("parentSampleId").asText().equals(p.path("sampleId").asText())) {
            f.add(finding("CHAIN_SELF_PARENT", "ERROR", "a sample cannot be its own parent", null));
        }
        if (version > 1 && !p.hasNonNull("parentSampleId")) {
            f.add(finding("CHAIN_PARENT_MISSING", "WARN",
                    "chainVersion > 1 should name the parent sample so the chain stays replayable", null));
        }
        if (!p.hasNonNull("collectedAt")) {
            f.add(finding("SAMPLE_COLLECTED_AT_MISSING", "WARN", "collectedAt missing; timeline replay degraded", null));
        }
    }

    private void validateResult(JsonNode p, List<Finding> f) {
        primaryKey(p, "resultId", "^[A-Za-z0-9_.:-]{3,64}$", f);
        primaryKey(p, "sampleId", "^[A-Za-z0-9_.:-]{3,64}$", f);
        String unit = p.path("unit").asText("");
        if (unit.isBlank()) {
            f.add(finding("UNIT_MISSING", "ERROR", "unit is required; a unitless result cannot be published", null));
        } else if (!KNOWN_UNITS.contains(unit)) {
            f.add(finding("UNIT_UNRECOGNISED", "WARN",
                    "unit '" + unit + "' is not in the controlled unit catalogue", text("unit", unit)));
        }
        BigDecimal low = decimal(p, "refLow");
        BigDecimal high = decimal(p, "refHigh");
        BigDecimal value = decimal(p, "value");
        if (low != null && high != null && low.compareTo(high) > 0) {
            f.add(finding("REFERENCE_INTERVAL_INVERTED", "ERROR",
                    "refLow " + low + " is greater than refHigh " + high, text("refLow", low)));
        }
        if (low == null || high == null) {
            f.add(finding("REFERENCE_INTERVAL_MISSING", "WARN",
                    "reference interval incomplete; report cannot claim a verified interpretation", null));
        }
        if (value == null) {
            f.add(finding("RESULT_VALUE_MISSING", "ERROR", "numeric value is required", null));
        } else {
            if (value.abs().compareTo(new BigDecimal("1000000")) > 0) {
                f.add(finding("RESULT_VALUE_OUT_OF_RANGE", "WARN",
                        "value magnitude exceeds the plausible range and needs manual review", text("value", value)));
            }
            if (low != null && high != null && low.compareTo(high) <= 0
                    && (value.compareTo(low) < 0 || value.compareTo(high) > 0)) {
                f.add(finding("RESULT_OUTSIDE_REFERENCE", "INFO",
                        "value lies outside the stated reference interval (clinically abnormal, not a data error)",
                        text("value", value)));
            }
        }
        if (p.hasNonNull("sampleId") && !p.hasNonNull("measuredAt")) {
            f.add(finding("RESULT_MEASURED_AT_MISSING", "WARN",
                    "measuredAt missing; late-arriving results cannot be ordered against collection time", null));
        }
    }

    private void validateReport(JsonNode p, List<Finding> f) {
        primaryKey(p, "reportId", "^[A-Za-z0-9_.:-]{3,64}$", f);
        primaryKey(p, "sampleId", "^[A-Za-z0-9_.:-]{3,64}$", f);
        int revision = intField(p, "revision", 0);
        if (revision < 1) {
            f.add(finding("REVISION_INVALID", "ERROR", "revision must be >= 1", null));
        }
        if (p.hasNonNull("supersedesRevision")) {
            int supersedes = p.get("supersedesRevision").asInt();
            if (supersedes >= revision) {
                f.add(finding("REVISION_RELATION_INVALID", "ERROR",
                        "supersedesRevision must be lower than revision", null));
            }
            if (revision != supersedes + 1) {
                f.add(finding("REVISION_GAP", "WARN",
                        "revision " + revision + " does not directly follow " + supersedes + "; sequence audit needed", null));
            }
        }
        if (!p.hasNonNull("content") || !p.get("content").isObject()) {
            f.add(finding("REPORT_CONTENT_MISSING", "ERROR", "content object is required to hash the report", null));
        }
        if (p.hasNonNull("content")) {
            JsonNode content = p.get("content");
            if (content.has("results") && !content.get("results").isArray()) {
                f.add(finding("REPORT_CONTENT_SHAPE", "WARN", "content.results should be an array", null));
            }
        }
    }

    private void validateObject(JsonNode p, List<Finding> f) {
        primaryKey(p, "objectKey", "^[A-Za-z0-9_./:-]{3,200}$", f);
        if (!p.hasNonNull("content")) {
            f.add(finding("OBJECT_CONTENT_MISSING", "ERROR", "object content is required", null));
        }
        String type = p.path("objectType").asText("UNKNOWN");
        if (!Set.of("RAW_PDF", "IMAGE", "XML", "JSON", "UNKNOWN").contains(type)) {
            f.add(finding("OBJECT_TYPE_UNKNOWN", "WARN", "objectType " + type + " is unmapped", null));
        }
    }

    private void primaryKey(JsonNode p, String field, String pattern, List<Finding> f) {
        JsonNode v = p.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            f.add(finding("PRIMARY_KEY_MISSING", "ERROR", field + " is required", text("field", field)));
            return;
        }
        if (!v.asText().matches(pattern)) {
            f.add(finding("PRIMARY_KEY_MALFORMED", "ERROR",
                    field + " does not match the tenant key format", text("field", field)));
        }
    }

    private int intField(JsonNode p, String field, int dflt) {
        JsonNode v = p.get(field);
        return v == null || v.isNull() ? dflt : v.asInt(dflt);
    }

    private BigDecimal decimal(JsonNode p, String field) {
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

    private JsonNode text(String field, Object value) {
        ObjectNode n = crypto.mapper().createObjectNode();
        n.put(field, String.valueOf(value));
        return n;
    }

    private Finding finding(String code, String severity, String message, JsonNode detail) {
        return new Finding(code, severity, message,
                detail == null ? crypto.mapper().createObjectNode() : detail);
    }
}
