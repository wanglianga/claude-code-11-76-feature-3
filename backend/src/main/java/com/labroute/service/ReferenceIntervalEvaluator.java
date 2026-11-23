package com.labroute.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reference interval evaluator.
 *
 * One analyte can legitimately have several interpretations: the applicable interval depends
 * on the instrument that measured it, the patient's age band and the rule version effective at
 * the report time. This service resolves exactly one rule for a given evaluation context and
 * derives the LOW / NORMAL / HIGH flag, or an explicit non-routable outcome.
 *
 * Point-in-time semantics make history replayable: every rule and rule set carries an
 * effective window, so the interpretation of a historical report is rebuilt from the rule
 * version that was in force at its report time, never from whatever the newest draft says.
 */
@Service
public class ReferenceIntervalEvaluator {

    public static final String FLAG_LOW = "LOW";
    public static final String FLAG_NORMAL = "NORMAL";
    public static final String FLAG_HIGH = "HIGH";
    public static final String FLAG_INDETERMINATE = "INDETERMINATE";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_NO_RULE = "NO_RULE";
    public static final String STATUS_RULE_WITHDRAWN = "RULE_WITHDRAWN";
    public static final String STATUS_UNIT_MISMATCH = "UNIT_MISMATCH";

    private final JdbcTemplate jdbc;

    public ReferenceIntervalEvaluator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Everything required to interpret one measured value.
     * {@code ruleSetId} pins a specific rule set version (history replay); when null the
     * evaluator resolves the rule set published and effective at {@code asOf}.
     */
    public record EvalContext(String tenantId, String analyteCode, String instrumentCode, String sex,
                              Long ageDays, BigDecimal value, String unit, Instant asOf, UUID ruleSetId) {
        public EvalContext withRuleSet(UUID id) {
            return new EvalContext(tenantId, analyteCode, instrumentCode, sex, ageDays, value, unit, asOf, id);
        }
    }

    public record IntervalOutcome(String status, String flag, UUID ruleId, UUID ruleSetId,
                                  String ruleSetCode, Integer ruleSetVersion, String ruleSetStatus,
                                  BigDecimal refLow, BigDecimal refHigh, String unit, String instrumentCode,
                                  Long ageLowDays, Long ageHighDays, String reason, Map<String, Object> detail) {
        public boolean routable() {
            return STATUS_OK.equals(status);
        }
    }

    public IntervalOutcome evaluate(EvalContext ctx) {
        UUID ruleSetId = ctx.ruleSetId();
        Map<String, Object> ruleSet;
        if (ruleSetId != null) {
            ruleSet = findRuleSetById(ctx.tenantId(), ruleSetId);
            if (ruleSet == null) {
                return fail(STATUS_NO_RULE, "rule set " + ruleSetId + " does not exist for this tenant", ctx);
            }
        } else {
            ruleSet = findRuleSetAt(ctx.tenantId(), ctx.asOf());
            if (ruleSet == null) {
                return fail(STATUS_NO_RULE,
                        "no published rule set effective at " + ctx.asOf() + " for tenant", ctx);
            }
            ruleSetId = UUID.fromString(String.valueOf(ruleSet.get("rule_set_id")));
        }

        String setStatus = (String) ruleSet.get("status");
        if ("WITHDRAWN".equals(setStatus)) {
            Map<String, Object> detail = baseDetail(ctx, ruleSet, null);
            detail.put("withdrawnReason", ruleSet.get("withdrawn_reason"));
            return new IntervalOutcome(STATUS_RULE_WITHDRAWN, FLAG_INDETERMINATE, null, ruleSetId,
                    (String) ruleSet.get("rule_set_code"), (Integer) ruleSet.get("version"), setStatus,
                    null, null, ctx.unit(), ctx.instrumentCode(), null, null,
                    "rule set " + ruleSet.get("rule_set_code") + " v" + ruleSet.get("version")
                            + " was withdrawn: " + ruleSet.get("withdrawn_reason"), detail);
        }
        if (!"PUBLISHED".equals(setStatus)) {
            return fail(STATUS_NO_RULE, "rule set is " + setStatus + ", only PUBLISHED sets are routable",
                    ctx.withRuleSet(ruleSetId));
        }

        Map<String, Object> rule = selectRule(ctx, ruleSetId);
        if (rule == null) {
            Map<String, Object> detail = baseDetail(ctx, ruleSet, null);
            return new IntervalOutcome(STATUS_NO_RULE, FLAG_INDETERMINATE, null, ruleSetId,
                    (String) ruleSet.get("rule_set_code"), (Integer) ruleSet.get("version"), setStatus,
                    null, null, ctx.unit(), ctx.instrumentCode(), null, null,
                    "no interval rule matches analyte/instrument/age at the report time", detail);
        }

        String ruleUnit = (String) rule.get("unit");
        if (ctx.unit() != null && ruleUnit != null && !ruleUnit.equalsIgnoreCase(ctx.unit())) {
            Map<String, Object> detail = baseDetail(ctx, ruleSet, rule);
            return new IntervalOutcome(STATUS_UNIT_MISMATCH, FLAG_INDETERMINATE,
                    UUID.fromString((String) rule.get("rule_id")), ruleSetId,
                    (String) ruleSet.get("rule_set_code"), (Integer) ruleSet.get("version"), setStatus,
                    (BigDecimal) rule.get("ref_low"), (BigDecimal) rule.get("ref_high"), ruleUnit,
                    (String) rule.get("instrument_code"), (Long) rule.get("age_low_days"),
                    (Long) rule.get("age_high_days"),
                    "result unit " + ctx.unit() + " does not match rule unit " + ruleUnit, detail);
        }

        BigDecimal low = (BigDecimal) rule.get("ref_low");
        BigDecimal high = (BigDecimal) rule.get("ref_high");
        String flag = flagFor(ctx.value(), low, high);
        return new IntervalOutcome(STATUS_OK, flag,
                UUID.fromString(String.valueOf(rule.get("rule_id"))), ruleSetId,
                (String) ruleSet.get("rule_set_code"), (Integer) ruleSet.get("version"), setStatus,
                low, high, ruleUnit, (String) rule.get("instrument_code"),
                (Long) rule.get("age_low_days"), (Long) rule.get("age_high_days"),
                "matched rule " + rule.get("rule_id"), baseDetail(ctx, ruleSet, rule));
    }

    /** Interprets a value against an explicit legacy interval (the interval stored on the result). */
    public IntervalOutcome evaluateLegacy(BigDecimal value, BigDecimal low, BigDecimal high,
                                          String unit, String instrumentCode, Long ageDays) {
        String flag = flagFor(value, low, high);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "LEGACY_RESULT_INTERVAL");
        detail.put("refLow", low);
        detail.put("refHigh", high);
        detail.put("unit", unit);
        return new IntervalOutcome(STATUS_OK, flag, null, null, null, null, "LEGACY",
                low, high, unit, instrumentCode, null, ageDays,
                "interpreted with the interval carried on the historical result row", detail);
    }

    public String flagFor(BigDecimal value, BigDecimal low, BigDecimal high) {
        if (value == null || low == null || high == null) {
            return FLAG_INDETERMINATE;
        }
        if (value.compareTo(low) < 0) {
            return FLAG_LOW;
        }
        if (value.compareTo(high) > 0) {
            return FLAG_HIGH;
        }
        return FLAG_NORMAL;
    }

    public static Long ageDays(OffsetDateTime dob, OffsetDateTime at) {
        if (dob == null || at == null) {
            return null;
        }
        return Duration.between(dob, at).toDays();
    }

    public static Long ageDays(OffsetDateTime dob, Instant at) {
        if (dob == null || at == null) {
            return null;
        }
        return Duration.between(dob.toInstant(), at).toDays();
    }

    // ---------- rule resolution ----------

    private Map<String, Object> selectRule(EvalContext ctx, UUID ruleSetId) {
        Long age = ctx.ageDays() == null ? 0L : ctx.ageDays();
        OffsetDateTime point = OffsetDateTime.ofInstant(
                ctx.asOf() == null ? Instant.now() : ctx.asOf(), ZoneOffset.UTC);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from lab.reference_interval_rule
                where rule_set_id = ?::uuid
                  and tenant_id = ?::uuid
                  and analyte_code = ?
                  and status = 'ACTIVE'
                  and effective_from <= ?
                  and (effective_to is null or effective_to > ?)
                  and age_low_days <= ?
                  and (age_high_days is null or age_high_days >= ?)
                  and (sex is null or (?::text is not null and sex = ?::text))
                  and (instrument_code is null or instrument_code = ?::text)
                order by
                  -- exact instrument match beats the tenant-wide wildcard rule
                  (instrument_code is null) asc,
                  -- narrower age band beats the catch-all band
                  (age_high_days is null) asc,
                  coalesce(age_high_days, 9223372036854775807) asc,
                  priority asc,
                  created_at asc
                limit 1
                """, ruleSetId.toString(), ctx.tenantId(), ctx.analyteCode(),
                point, point, age, age, ctx.sex(), ctx.sex(),
                ctx.instrumentCode() == null ? "" : ctx.instrumentCode());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> findRuleSetAt(String tenantId, Instant asOf) {
        OffsetDateTime point = OffsetDateTime.ofInstant(asOf == null ? Instant.now() : asOf, ZoneOffset.UTC);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from lab.reference_rule_set
                where tenant_id = ?::uuid and status = 'PUBLISHED'
                  and effective_from <= ?
                  and (effective_to is null or effective_to > ?)
                order by version desc limit 1
                """, tenantId, point, point);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> findRuleSetById(String tenantId, UUID ruleSetId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from lab.reference_rule_set where rule_set_id = ?::uuid and tenant_id = ?::uuid
                """, ruleSetId.toString(), tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Optional<Map<String, Object>> ruleSetById(String tenantId, UUID ruleSetId) {
        return Optional.ofNullable(findRuleSetById(tenantId, ruleSetId));
    }

    private IntervalOutcome fail(String status, String reason, EvalContext ctx) {
        return new IntervalOutcome(status, FLAG_INDETERMINATE, null, ctx.ruleSetId(),
                null, null, null, null, null, ctx.unit(), ctx.instrumentCode(), null, ctx.ageDays(),
                reason, baseDetail(ctx, null, null));
    }

    private Map<String, Object> baseDetail(EvalContext ctx, Map<String, Object> ruleSet, Map<String, Object> rule) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("analyteCode", ctx.analyteCode());
        d.put("instrumentCode", ctx.instrumentCode());
        d.put("ageDays", ctx.ageDays());
        d.put("sex", ctx.sex());
        d.put("value", ctx.value());
        d.put("unit", ctx.unit());
        d.put("asOf", String.valueOf(ctx.asOf()));
        if (ruleSet != null) {
            d.put("ruleSetCode", ruleSet.get("rule_set_code"));
            d.put("ruleSetVersion", ruleSet.get("version"));
        }
        if (rule != null) {
            d.put("matchedInstrument", rule.get("instrument_code"));
            d.put("ageBandDays", java.util.Arrays.asList(rule.get("age_low_days"), rule.get("age_high_days")));
        }
        return d;
    }
}
