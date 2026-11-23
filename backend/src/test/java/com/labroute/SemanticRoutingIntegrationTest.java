package com.labroute;

import com.labroute.model.Tenant;
import com.labroute.service.DemoDataService;
import com.labroute.service.PublicationService;
import com.labroute.service.SemanticRoutingService;
import com.labroute.service.ShadowRenderer;
import com.labroute.service.ShadowDiffTriage;
import com.labroute.service.TemplateCatalogService;
import com.labroute.service.TemplateRouteGate;
import com.labroute.service.TenantService;
import com.labroute.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for unified semantic routing against a real PostgreSQL (ccdev_test).
 * Each test builds its own tenant and rule set, so runs are isolated and repeatable.
 *
 * Covers the three brief-mandated cases:
 *  - child/adult reference interval handoff (AGE_BOUNDARY shadow diff, then authoritative replay)
 *  - instrument replacement (INSTRUMENT_SWITCH diff, expected not blocking)
 *  - rule withdrawal after release (gate fail-closed, publication stays continuous)
 * plus both history strategies (MATERIALIZED and REPLAYED) and enable-gate refusal on a
 * BLOCKING triage finding.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class SemanticRoutingIntegrationTest {

    @Autowired TenantService tenants;
    @Autowired com.labroute.service.AuthService auth;
    @Autowired DemoDataService demo;
    @Autowired TemplateCatalogService catalog;
    @Autowired ShadowRenderer renderer;
    @Autowired TemplateRouteGate gate;
    @Autowired SemanticRoutingService routing;
    @Autowired PublicationService publication;
    @Autowired com.labroute.service.ScenarioService scenarios;

    private static final List<TemplateCatalogService.RuleSpec> GLU_RULES = List.of(
            new TemplateCatalogService.RuleSpec("GLU", "AU5800", 0L, 17L * 365L, null,
                    new BigDecimal("3.3"), new BigDecimal("5.6"), "mmol/L", "PEDIATRIC", 10),
            new TemplateCatalogService.RuleSpec("GLU", "AU5800", 18L * 365L, null, null,
                    new BigDecimal("3.9"), new BigDecimal("6.1"), "mmol/L", "ADULT", 20),
            new TemplateCatalogService.RuleSpec("GLU", "BS2000", 18L * 365L, null, null,
                    new BigDecimal("4.0"), new BigDecimal("6.3"), "mmol/L", "ADULT", 20));

    private Tenant freshTenant() {
        String code = "test-sem-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Tenant t = tenants.create(new TenantService.CreateTenant(code, code, "FENCE_REPLAY"));
        auth.addCredential(t.tenantId(), "SOURCE-A", "test-secret-" + code,
                "intake:sample,intake:result,intake:report,intake:object");
        return tenants.requireByCode(t.code());
    }

    private String publishGluRuleSet(Tenant tenant) {
        Map<String, Object> set = catalog.createRuleSet(tenant.tenantId(),
                new TemplateCatalogService.RuleSetRequest("RS-GLU", null, GLU_RULES));
        String id = String.valueOf(set.get("rule_set_id"));
        catalog.publishRuleSet(tenant.tenantId(), id, Instant.now().minus(1, ChronoUnit.HOURS), "test baseline");
        return id;
    }

    private Map<String, Object> deliverChildReport(Tenant tenant, String suffix, String instrument,
                                                   String value, String legacyLow, String legacyHigh,
                                                   Long ageDays) {
        String sid = demo.uniqueKey("SR-" + suffix + "-S");
        String rid = demo.uniqueKey("SR-" + suffix + "-R");
        String rep = demo.uniqueKey("SR-" + suffix + "-REP");
        demo.deliver(tenant, "sample", demo.samplePayload(sid, "SOURCE-A", ageDays, null), null, null);
        demo.deliver(tenant, "result", demo.resultPayload(rid, sid, "GLU", instrument, value,
                "mmol/L", legacyLow, legacyHigh, ageDays, null), null, null);
        demo.deliver(tenant, "report", demo.reportPayload(rep, sid, 1, "semantic-" + suffix), null, null);
        return Map.of("sampleId", sid, "resultId", rid, "reportId", rep);
    }

    @Test
    void childAdultHandoffShadowDiffThenAuthoritativeReplayAndMaterialization() {
        Tenant tenant = freshTenant();
        String ruleSetId = publishGluRuleSet(tenant);

        // 10-year-old, value 5.55: HIGH against the legacy adult interval (3.9-5.5), but NORMAL
        // against the catalogued paediatric band (3.3-5.6).
        var ids = deliverChildReport(tenant, "AGE", "AU5800", "5.55", "3.9", "5.5", 3650L);
        String reportId = (String) ids.get("reportId");

        gate.ensureGate(tenant.tenantId(), TemplateRouteGate.MODE_REPLAYED);
        ShadowRenderer.ShadowResult shadow = renderer.renderReport(tenant, "OLD", reportId, 1,
                java.util.UUID.fromString(ruleSetId), "TEST_AGE_HANDOFF");
        assertThat(shadow.result()).isEqualTo(ShadowRenderer.RESULT_DIVERGED);
        assertThat(shadow.diffs()).anyMatch(d -> "AGE_BOUNDARY".equals(d.diffType())
                && ShadowDiffTriage.SEV_EXPECTED.equals(d.severity()));
        assertThat(shadow.diffs()).anyMatch(d -> "FLAG_CHANGE".equals(d.diffType()));
        assertThat(shadow.diffs()).noneMatch(d -> ShadowDiffTriage.SEV_BLOCKING.equals(d.severity()));
        assertThat(shadow.baselineRows().get(0).get("flag")).isEqualTo("HIGH");
        assertThat(shadow.candidateRows().get(0).get("flag")).isEqualTo("NORMAL");

        // Gate opens because no BLOCKING finding exists.
        gate.beginEvaluation(tenant, null, ruleSetId, TemplateRouteGate.MODE_REPLAYED);
        gate.enable(tenant);
        Tenant enabled = tenants.requireById(tenant.tenantId());

        Map<String, Object> interpreted = routing.interpret(enabled, reportId, 1);
        assertThat(interpreted.get("authoritative")).isEqualTo(true);
        assertThat(interpreted.get("interpretationSource")).isEqualTo("REPLAYED");
        Map<?, ?> first = ((List<Map<String, Object>>) interpreted.get("interpretations")).get(0);
        assertThat(first.get("flag")).isEqualTo("NORMAL");
        assertThat(first.get("status")).isEqualTo("OK");

        // Publication stays continuous and now carries the authoritative interpretation.
        PublicationService.PublishResult published = publication.publish(tenant.code(), reportId);
        assertThat(published.httpStatus()).isEqualTo(200);
        assertThat(published.body().get("semanticGateState")).isEqualTo("ENABLED");
        assertThat(published.body().get("interpretationAuthoritative")).isEqualTo(true);

        // Switch the gate to the MATERIALIZED strategy: before materialisation the route must
        // refuse rather than silently fall back to live rules.
        gate.beginEvaluation(enabled, null, ruleSetId, TemplateRouteGate.MODE_MATERIALIZED);
        gate.enable(tenants.requireById(tenant.tenantId()));
        Tenant materializedTenant = tenants.requireById(tenant.tenantId());
        assertThatThrownBy(() -> routing.interpret(materializedTenant, reportId, 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("INTERPRETATION_NOT_MATERIALIZED"));

        renderer.materializeLegacy(materializedTenant, "OLD", 50);
        assertThat(renderer.listInterpretations(materializedTenant.tenantId(), reportId, 1)).isNotEmpty();
        Map<String, Object> served = routing.interpret(materializedTenant, reportId, 1);
        assertThat(served.get("interpretationSource")).isEqualTo("MATERIALIZED");
        @SuppressWarnings("unchecked")
        Map<String, Object> materializedRow =
                ((List<Map<String, Object>>) served.get("interpretations")).get(0);
        // The materialised strategy freezes the OLD interpretation: historical display is stable
        // (HIGH as originally shown), even though a live replay would now resolve NORMAL.
        assertThat(materializedRow.get("flag")).isEqualTo("HIGH");
        assertThat(materializedRow.get("historyMode")).isEqualTo("MATERIALIZED");
    }

    @Test
    void instrumentSwitchProducesExpectedDiffAndDifferentInterpretation() {
        Tenant tenant = freshTenant();
        String ruleSetId = publishGluRuleSet(tenant);

        // Adult patient, new instrument BS2000: legacy AU5800 interval flags 6.2 HIGH; the
        // catalogued BS2000 interval (4.0-6.3) makes the same value NORMAL.
        var ids = deliverChildReport(tenant, "INST", "BS2000", "6.2", "3.9", "6.1", 8000L);
        String reportId = (String) ids.get("reportId");

        gate.ensureGate(tenant.tenantId(), TemplateRouteGate.MODE_REPLAYED);
        ShadowRenderer.ShadowResult shadow = renderer.renderReport(tenant, "OLD", reportId, 1,
                java.util.UUID.fromString(ruleSetId), "TEST_INSTRUMENT");
        assertThat(shadow.diffs()).anyMatch(d -> "INSTRUMENT_SWITCH".equals(d.diffType())
                && ShadowDiffTriage.SEV_EXPECTED.equals(d.severity()));
        assertThat(shadow.candidateRows().get(0).get("instrumentCode")).isEqualTo("BS2000");
        assertThat(shadow.candidateRows().get(0).get("flag")).isEqualTo("NORMAL");
        assertThat(shadow.baselineRows().get(0).get("flag")).isEqualTo("HIGH");
        assertThat(gate.refresh(tenant.tenantId()).blockingTriageCount()).isZero();
    }

    @Test
    void withdrawnRuleFailsGateClosedAndPublicationStaysContinuous() {
        Tenant tenant = freshTenant();
        String ruleSetId = publishGluRuleSet(tenant);
        var ids = deliverChildReport(tenant, "WD", "AU5800", "5.0", "3.9", "5.5", 8000L);
        String reportId = (String) ids.get("reportId");

        gate.ensureGate(tenant.tenantId(), TemplateRouteGate.MODE_REPLAYED);
        gate.beginEvaluation(tenant, null, ruleSetId, TemplateRouteGate.MODE_REPLAYED);
        gate.enable(tenant);
        assertThat(gate.require(tenant.tenantId()).state()).isEqualTo(TemplateRouteGate.STATE_ENABLED);

        // Withdrawing the active rule set must flip the gate to BLOCKED on the next serve.
        catalog.withdrawRuleSet(tenant.tenantId(), ruleSetId, "recall after publication");
        assertThatThrownBy(() -> routing.interpret(tenants.requireById(tenant.tenantId()), reportId, 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("RULE_WITHDRAWN"));
        assertThat(gate.require(tenant.tenantId()).state()).isEqualTo(TemplateRouteGate.STATE_BLOCKED);

        // The report itself remains continuously published; the block is reported, not a 5xx.
        PublicationService.PublishResult published = publication.publish(tenant.code(), reportId);
        assertThat(published.httpStatus()).isEqualTo(200);
        assertThat(published.body().get("visibleState")).isEqualTo("VERIFIED");
        assertThat(published.body().get("semanticGateState")).isEqualTo("BLOCKED");
        assertThat(published.body().get("semanticRoutingBlocked")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) published.body().get("semanticRoutingBlocked")).get("code")).isNotNull();

        // Recovery: a new rule set version can be re-evaluated and re-enabled.
        String v2Id = publishGluRuleSet(tenant);
        gate.resetToShadow(tenants.requireById(tenant.tenantId()), "recover from withdrawn rule");
        gate.beginEvaluation(tenants.requireById(tenant.tenantId()), null, v2Id,
                TemplateRouteGate.MODE_REPLAYED);
        gate.enable(tenants.requireById(tenant.tenantId()));
        assertThat(gate.require(tenant.tenantId()).state()).isEqualTo(TemplateRouteGate.STATE_ENABLED);
    }

    @Test
    void enableIsRefusedWhileBlockingTriageIsOpen() {
        Tenant tenant = freshTenant();
        // A rule set that only knows CREA: the GLU report has no resolvable rule -> BLOCKING.
        Map<String, Object> creaSet = catalog.createRuleSet(tenant.tenantId(),
                new TemplateCatalogService.RuleSetRequest("RS-CREA", null, List.of(
                        new TemplateCatalogService.RuleSpec("CREA", null, 18L * 365L, null, null,
                                new BigDecimal("45"), new BigDecimal("104"), "umol/L", "ADULT", 20))));
        String creaId = String.valueOf(creaSet.get("rule_set_id"));
        catalog.publishRuleSet(tenant.tenantId(), creaId, Instant.now().minus(1, ChronoUnit.HOURS), "crea only");

        var ids = deliverChildReport(tenant, "NORULE", "AU5800", "5.0", "3.9", "5.5", 8000L);
        gate.ensureGate(tenant.tenantId(), TemplateRouteGate.MODE_REPLAYED);
        ShadowRenderer.ShadowResult shadow = renderer.renderReport(tenant, "OLD",
                (String) ids.get("reportId"), 1, java.util.UUID.fromString(creaId), "TEST_NO_RULE");
        assertThat(shadow.diffs()).anyMatch(d -> "NO_RULE".equals(d.diffType())
                && ShadowDiffTriage.SEV_BLOCKING.equals(d.severity()));

        gate.beginEvaluation(tenant, null, creaId, TemplateRouteGate.MODE_REPLAYED);
        assertThatThrownBy(() -> gate.enable(tenants.requireById(tenant.tenantId())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("ROUTE_GATE_BLOCKED"));
        assertThat(gate.require(tenant.tenantId()).state()).isEqualTo(TemplateRouteGate.STATE_GATED);

        // Routing is not authoritative while the gate remains closed.
        Map<String, Object> interpreted = routing.interpret(tenants.requireById(tenant.tenantId()),
                (String) ids.get("reportId"), 1);
        assertThat(interpreted.get("authoritative")).isEqualTo(false);
        assertThat(interpreted.get("interpretationSource")).isEqualTo("SHADOW_LEGACY");
    }

    @Test
    void semanticRoutingScenarioPassesAllAssertions() {
        Tenant tenant = freshTenant();
        Map<String, Object> run = scenarios.run("SEMANTIC_ROUTING", tenant.code());
        assertThat(run.get("status")).isEqualTo("PASSED");
        @SuppressWarnings("unchecked")
        List<com.labroute.service.ScenarioService.Assertion> assertions =
                (List<com.labroute.service.ScenarioService.Assertion>) run.get("assertions");
        assertThat(assertions).isNotEmpty().allSatisfy(a -> assertThat(a.passed()).isTrue());
    }
}
