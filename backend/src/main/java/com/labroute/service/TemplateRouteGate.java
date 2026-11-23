package com.labroute.service;

import com.labroute.model.Tenant;
import com.labroute.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Enable gate for unified semantic routing.
 *
 * Per-tenant lifecycle:
 *
 *   SHADOW  - directory and rule sets are being built; shadow renders run but no report is
 *             ever served a directory interpretation. Publication keeps the legacy rendering.
 *   GATED   - a candidate template + rule set version is pinned for evaluation; triage findings
 *             accumulate. The gate refuses to open while any OPEN BLOCKING finding exists.
 *   ENABLED - the directory is the single routing source for interpretations. If the active
 *             rule set is withdrawn afterwards, serve-time checks fail closed back to BLOCKED.
 *   BLOCKED - an explicit operator or system hold; routing is blocked until reset to SHADOW.
 *
 * The gate is the only place that flips semantic routing, mirroring MigrationService being the
 * only place that flips read/write spaces. Every transition is written to template_gate_event
 * and the hash-chained evidence ledger.
 */
@Service
public class TemplateRouteGate {

    public static final String STATE_SHADOW = "SHADOW";
    public static final String STATE_GATED = "GATED";
    public static final String STATE_ENABLED = "ENABLED";
    public static final String STATE_BLOCKED = "BLOCKED";

    public static final String MODE_MATERIALIZED = "MATERIALIZED";
    public static final String MODE_REPLAYED = "REPLAYED";

    private final JdbcTemplate jdbc;
    private final ShadowDiffTriage triage;
    private final TemplateCatalogService catalog;
    private final EvidenceService evidence;
    private final CryptoSupport crypto;
    private final TemplateRouteGate self;

    public TemplateRouteGate(JdbcTemplate jdbc, ShadowDiffTriage triage,
                             TemplateCatalogService catalog, EvidenceService evidence,
                             CryptoSupport crypto,
                             @org.springframework.context.annotation.Lazy TemplateRouteGate self) {
        this.jdbc = jdbc;
        this.triage = triage;
        this.catalog = catalog;
        this.evidence = evidence;
        this.crypto = crypto;
        this.self = self;
    }

    public record GateRow(String gateId, String tenantId, String state, String historyMode,
                          UUID candidateTemplateId, UUID candidateRuleSetId,
                          UUID activeTemplateId, UUID activeRuleSetId,
                          long openTriageCount, long blockingTriageCount,
                          String lastShadowResult, String lastGateReason) {
        public boolean enabled() {
            return STATE_ENABLED.equals(state);
        }
    }

    @Transactional
    public GateRow ensureGate(String tenantId, String historyMode) {
        Optional<GateRow> existing = find(tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        String mode = MODE_MATERIALIZED.equals(historyMode) ? MODE_MATERIALIZED : MODE_REPLAYED;
        jdbc.update("""
                insert into lab.template_route_gate(gate_id, tenant_id, state, history_mode)
                values (?::uuid, ?::uuid, 'SHADOW', ?)
                """, UUID.randomUUID().toString(), tenantId, mode);
        event(tenantId, null, STATE_SHADOW, "gate initialised", Map.of("historyMode", mode));
        return find(tenantId).orElseThrow();
    }

    public Optional<GateRow> find(String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from lab.template_route_gate where tenant_id = ?::uuid
                """, tenantId);
        return rows.stream().findFirst().map(this::map);
    }

    public GateRow require(String tenantId) {
        return find(tenantId).orElseGet(() -> ensureGate(tenantId, MODE_REPLAYED));
    }

    /** Pin the candidate versions and move SHADOW -> GATED (re-pinning while GATED is allowed). */
    @Transactional
    public GateRow beginEvaluation(Tenant tenant, String templateId, String ruleSetId, String historyMode) {
        GateRow gate = ensureGate(tenant.tenantId(), historyMode);
        if (STATE_BLOCKED.equals(gate.state())) {
            throw ApiException.conflict("GATE_BLOCKED",
                    "the gate is in BLOCKED; reset it to SHADOW before starting a new evaluation", Map.of());
        }
        UUID templateUuid = null;
        UUID ruleSetUuid = null;
        if (templateId != null && !templateId.isBlank()) {
            Map<String, Object> t = catalog.requireTemplate(tenant.tenantId(), templateId);
            templateUuid = UUID.fromString(String.valueOf(t.get("template_id")));
        }
        if (ruleSetId != null && !ruleSetId.isBlank()) {
            Map<String, Object> s = catalog.requireRuleSet(tenant.tenantId(), ruleSetId);
            ruleSetUuid = UUID.fromString(String.valueOf(s.get("rule_set_id")));
            if ("WITHDRAWN".equals(s.get("status"))) {
                throw ApiException.conflict("RULE_SET_WITHDRAWN",
                        "a withdrawn rule set cannot be the gate candidate", Map.of("status", s.get("status")));
            }
        }
        String mode = MODE_MATERIALIZED.equals(historyMode) ? MODE_MATERIALIZED
                : (historyMode == null ? gate.historyMode() : historyMode);
        jdbc.update("""
                update lab.template_route_gate
                set state = 'GATED', history_mode = ?, candidate_template_id = ?::uuid,
                    candidate_rule_set_id = ?::uuid, updated_at = now()
                where tenant_id = ?::uuid
                """, mode, templateUuid == null ? null : templateUuid.toString(),
                ruleSetUuid == null ? null : ruleSetUuid.toString(), tenant.tenantId());
        event(tenant.tenantId(), gate.state(), STATE_GATED, "candidate versions pinned",
                Map.of("templateId", String.valueOf(templateUuid), "ruleSetId", String.valueOf(ruleSetUuid),
                        "historyMode", mode));
        evidence.append(tenant.tenantId(), "SEMANTIC_GATE_GATED", "TEMPLATE_GATE", tenant.code(),
                null, "PENDING_VERIFICATION",
                Map.of("candidateTemplateId", String.valueOf(templateUuid),
                        "candidateRuleSetId", String.valueOf(ruleSetUuid), "historyMode", mode));
        return refresh(tenant.tenantId());
    }

    /**
     * The single opening operation. Refuses while an OPEN BLOCKING finding exists, while the
     * candidate rule set/template was withdrawn, or while no candidate was pinned.
     */
    @Transactional
    public GateRow enable(Tenant tenant) {
        GateRow gate = require(tenant.tenantId());
        if (STATE_ENABLED.equals(gate.state())) {
            return gate;
        }
        if (!STATE_GATED.equals(gate.state())) {
            throw ApiException.conflict("GATE_STATE_INVALID",
                    "enable requires GATED state; current state is " + gate.state(), Map.of("state", gate.state()));
        }
        if (gate.candidateRuleSetId() == null && gate.candidateTemplateId() == null) {
            throw ApiException.conflict("GATE_CANDIDATE_MISSING",
                    "pin a candidate template or rule set before enabling the gate", Map.of());
        }
        long blocking = triage.openBlockingCount(tenant.tenantId());
        if (blocking > 0) {
            jdbc.update("update lab.template_route_gate set last_gate_reason = ?, updated_at = now() " +
                    "where tenant_id = ?::uuid", blocking + " open BLOCKING triage finding(s)", tenant.tenantId());
            throw ApiException.conflict("ROUTE_GATE_BLOCKED",
                    "enable refused: " + blocking + " open BLOCKING shadow diff(s) must be resolved first",
                    Map.of("openBlocking", blocking, "triage", triage.list(tenant.tenantId(), "OPEN")));
        }
        if (gate.candidateRuleSetId() != null) {
            Map<String, Object> s = catalog.requireRuleSet(tenant.tenantId(), gate.candidateRuleSetId().toString());
            if (!"PUBLISHED".equals(s.get("status"))) {
                throw ApiException.conflict("ROUTE_GATE_BLOCKED",
                        "candidate rule set is " + s.get("status") + ", only PUBLISHED can be enabled",
                        Map.of("ruleSetStatus", s.get("status")));
            }
        }
        if (gate.candidateTemplateId() != null) {
            Map<String, Object> t = catalog.requireTemplate(tenant.tenantId(), gate.candidateTemplateId().toString());
            if (!"PUBLISHED".equals(t.get("status"))) {
                throw ApiException.conflict("ROUTE_GATE_BLOCKED",
                        "candidate template is " + t.get("status") + ", only PUBLISHED can be enabled",
                        Map.of("templateStatus", t.get("status")));
            }
        }
        jdbc.update("""
                update lab.template_route_gate
                set state = 'ENABLED', active_template_id = candidate_template_id,
                    active_rule_set_id = candidate_rule_set_id, last_gate_reason = null, updated_at = now()
                where tenant_id = ?::uuid
                """, tenant.tenantId());
        GateRow enabled = refresh(tenant.tenantId());
        event(tenant.tenantId(), STATE_GATED, STATE_ENABLED, "gate opened; directory is the routing source",
                Map.of("activeTemplateId", String.valueOf(enabled.activeTemplateId()),
                        "activeRuleSetId", String.valueOf(enabled.activeRuleSetId())));
        evidence.append(tenant.tenantId(), "SEMANTIC_GATE_ENABLED", "TEMPLATE_GATE", tenant.code(),
                "DIRECTORY", "VERIFIED",
                Map.of("activeTemplateId", String.valueOf(enabled.activeTemplateId()),
                        "activeRuleSetId", String.valueOf(enabled.activeRuleSetId()),
                        "historyMode", enabled.historyMode()));
        return enabled;
    }

    /**
     * Explicit operator hold, or a system hold triggered by withdrawing the active rule set.
     * REQUIRES_NEW: the hold must survive even when the caller (serve-time fail-closed check)
     * subsequently aborts its own transaction.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public GateRow block(Tenant tenant, String reason) {
        GateRow gate = require(tenant.tenantId());
        jdbc.update("""
                update lab.template_route_gate set state = 'BLOCKED', last_gate_reason = ?, updated_at = now()
                where tenant_id = ?::uuid
                """, reason, tenant.tenantId());
        event(tenant.tenantId(), gate.state(), STATE_BLOCKED, reason, Map.of());
        evidence.append(tenant.tenantId(), "SEMANTIC_GATE_BLOCKED", "TEMPLATE_GATE", tenant.code(),
                null, "PENDING_VERIFICATION", Map.of("reason", reason));
        return refresh(tenant.tenantId());
    }

    @Transactional
    public GateRow resetToShadow(Tenant tenant, String reason) {
        GateRow gate = require(tenant.tenantId());
        jdbc.update("""
                update lab.template_route_gate
                set state = 'SHADOW', candidate_template_id = null, candidate_rule_set_id = null,
                    last_gate_reason = ?, updated_at = now()
                where tenant_id = ?::uuid
                """, reason, tenant.tenantId());
        event(tenant.tenantId(), gate.state(), STATE_SHADOW, reason, Map.of());
        return refresh(tenant.tenantId());
    }

    /**
     * Serve-time fail-closed check used by the unified interpretation route. A withdrawn active
     * rule set flips the gate to BLOCKED and the route returns an explicit conflict instead of
     * silently re-interpreting history with a different rule version.
     */
    public GateRow assertServable(Tenant tenant) {
        GateRow gate = require(tenant.tenantId());
        if (STATE_BLOCKED.equals(gate.state())) {
            throw ApiException.conflict("SEMANTIC_ROUTE_BLOCKED",
                    "semantic routing is blocked: " + gate.lastGateReason(),
                    Map.of("state", gate.state(), "reason", gate.lastGateReason()));
        }
        if (!STATE_ENABLED.equals(gate.state())) {
            throw ApiException.conflict("SEMANTIC_ROUTE_NOT_ENABLED",
                    "directory interpretation is not routable in gate state " + gate.state()
                            + "; legacy rendering remains the served interpretation",
                    Map.of("state", gate.state()));
        }
        if (gate.activeRuleSetId() != null) {
            Map<String, Object> s = catalog.requireRuleSet(tenant.tenantId(), gate.activeRuleSetId().toString());
            if ("WITHDRAWN".equals(s.get("status"))) {
                // Go through the proxy so the REQUIRES_NEW hold commits even when this serve-time
                // check aborts the caller's interpretation transaction.
                self.block(tenant, "active rule set withdrawn: " + s.get("withdrawn_reason"));
                throw ApiException.conflict("RULE_WITHDRAWN",
                        "the active rule set was withdrawn; routing blocked to preserve historical semantics",
                        Map.of("ruleSetId", gate.activeRuleSetId().toString(),
                                "withdrawnReason", s.get("withdrawn_reason")));
            }
        }
        return gate;
    }

    public long openBlockingCount(String tenantId) {
        return triage.openBlockingCount(tenantId);
    }

    /** Recompute triage counters and latest shadow outcome on the gate row. */
    @Transactional
    public GateRow refresh(String tenantId) {        Map<String, Object> counts = triage.openCounts(tenantId);
        String lastShadow = jdbc.query("""
                select result from lab.shadow_render where tenant_id = ?::uuid
                order by created_at desc limit 1
                """, rs -> rs.next() ? rs.getString(1) : null, tenantId);
        jdbc.update("""
                update lab.template_route_gate
                set open_triage_count = ?, blocking_triage_count = ?, last_shadow_result = ?, updated_at = now()
                where tenant_id = ?::uuid
                """, counts.get("open"), counts.get("blocking"), lastShadow, tenantId);
        return find(tenantId).orElseThrow();
    }

    public Map<String, Object> state(Tenant tenant) {
        GateRow gate = require(tenant.tenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant.code());
        out.put("state", gate.state());
        out.put("historyMode", gate.historyMode());
        out.put("candidateTemplateId", gate.candidateTemplateId() == null ? null : gate.candidateTemplateId().toString());
        out.put("candidateRuleSetId", gate.candidateRuleSetId() == null ? null : gate.candidateRuleSetId().toString());
        out.put("activeTemplateId", gate.activeTemplateId() == null ? null : gate.activeTemplateId().toString());
        out.put("activeRuleSetId", gate.activeRuleSetId() == null ? null : gate.activeRuleSetId().toString());
        out.put("openTriageCount", gate.openTriageCount());
        out.put("blockingTriageCount", gate.blockingTriageCount());
        out.put("lastShadowResult", gate.lastShadowResult());
        out.put("lastGateReason", gate.lastGateReason());
        out.put("triage", triage.openCounts(tenant.tenantId()));
        out.put("events", jdbc.queryForList("""
                select event_id::text as "eventId", from_state as "fromState", to_state as "toState",
                       reason, detail, created_at as "createdAt"
                from lab.template_gate_event where tenant_id = ?::uuid order by created_at desc limit 20
                """, tenant.tenantId()));
        return out;
    }

    private void event(String tenantId, String from, String to, String reason, Map<String, Object> detail) {
        jdbc.update("""
                insert into lab.template_gate_event(event_id, tenant_id, from_state, to_state, reason, detail)
                values (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID().toString(), tenantId, from, to, reason,
                crypto.canonicalJson(detail));
    }

    private GateRow map(Map<String, Object> r) {
        return new GateRow(
                String.valueOf(r.get("gate_id")), String.valueOf(r.get("tenant_id")),
                (String) r.get("state"), (String) r.get("history_mode"),
                uuid(r.get("candidate_template_id")), uuid(r.get("candidate_rule_set_id")),
                uuid(r.get("active_template_id")), uuid(r.get("active_rule_set_id")),
                ((Number) r.get("open_triage_count")).longValue(),
                ((Number) r.get("blocking_triage_count")).longValue(),
                (String) r.get("last_shadow_result"), (String) r.get("last_gate_reason"));
    }

    private static UUID uuid(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
