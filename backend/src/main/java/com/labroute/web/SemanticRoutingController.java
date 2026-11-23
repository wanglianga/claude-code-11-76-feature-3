package com.labroute.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.labroute.model.Tenant;
import com.labroute.service.SemanticRoutingService;
import com.labroute.service.ShadowDiffTriage;
import com.labroute.service.ShadowRenderer;
import com.labroute.service.TemplateCatalogService;
import com.labroute.service.TemplateRouteGate;
import com.labroute.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Control plane for unified semantic routing: the template directory, reference interval
 * rule sets, shadow rendering, diff triage and the per-tenant enable gate.
 *
 * Mutations require X-Operator-Token; reads stay open like the rest of the control-plane API.
 * Nothing here changes read/write space routing - that remains MigrationService's sole job.
 */
@RestController
@RequestMapping("/api/tenants/{code}/semantic")
public class SemanticRoutingController {

    private final TenantService tenants;
    private final TemplateCatalogService catalog;
    private final ShadowRenderer renderer;
    private final ShadowDiffTriage triage;
    private final TemplateRouteGate gate;
    private final SemanticRoutingService routing;
    private final OperatorAuth auth;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public SemanticRoutingController(TenantService tenants, TemplateCatalogService catalog,
                                     ShadowRenderer renderer, ShadowDiffTriage triage,
                                     TemplateRouteGate gate, SemanticRoutingService routing,
                                     OperatorAuth auth, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.tenants = tenants;
        this.catalog = catalog;
        this.renderer = renderer;
        this.triage = triage;
        this.gate = gate;
        this.routing = routing;
        this.auth = auth;
        this.mapper = mapper;
    }

    private void guard(HttpServletRequest request) {
        if (!auth.authorised(request)) {
            throw ApiException.unauthorized("OPERATOR_TOKEN_REQUIRED",
                    "semantic routing mutations require X-Operator-Token");
        }
    }

    private Tenant tenant(String code) {
        return tenants.requireByCode(code);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static Long lng(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private static BigDecimal dec(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(v));
    }

    private static Instant instant(Map<String, Object> body, String key) {
        String v = str(body, key);
        return v == null ? null : Instant.parse(v);
    }

    // ---------- rule sets ----------

    @PostMapping("/rule-sets")
    public Map<String, Object> createRuleSet(HttpServletRequest request, @PathVariable String code,
                                             @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        List<TemplateCatalogService.RuleSpec> specs = new ArrayList<>();
        Object rules = body.get("rules");
        if (rules instanceof List<?> list) {
            for (Object item : list) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) item;
                specs.add(new TemplateCatalogService.RuleSpec(
                        str(r, "analyteCode"), str(r, "instrumentCode"),
                        lng(r, "ageLowDays"), lng(r, "ageHighDays"), str(r, "sex"),
                        dec(r, "refLow"), dec(r, "refHigh"), str(r, "unit"),
                        str(r, "flagLabel"), r.get("priority") == null ? null : ((Number) r.get("priority")).intValue()));
            }
        }
        var req = new TemplateCatalogService.RuleSetRequest(str(body, "ruleSetCode"),
                instant(body, "effectiveFrom"), specs);
        return catalog.createRuleSet(t.tenantId(), req);
    }

    @PostMapping("/rule-sets/{ruleSetId}/rules")
    public Map<String, Object> addRule(HttpServletRequest request, @PathVariable String code,
                                       @PathVariable String ruleSetId, @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        var spec = new TemplateCatalogService.RuleSpec(
                str(body, "analyteCode"), str(body, "instrumentCode"),
                lng(body, "ageLowDays"), lng(body, "ageHighDays"), str(body, "sex"),
                dec(body, "refLow"), dec(body, "refHigh"), str(body, "unit"),
                str(body, "flagLabel"),
                body.get("priority") == null ? null : ((Number) body.get("priority")).intValue());
        return catalog.addRule(t.tenantId(), ruleSetId, spec);
    }

    @PostMapping("/rule-sets/{ruleSetId}/publish")
    public Map<String, Object> publishRuleSet(HttpServletRequest request, @PathVariable String code,
                                              @PathVariable String ruleSetId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        return catalog.publishRuleSet(tenant(code).tenantId(), ruleSetId, instant(body, "effectiveFrom"),
                str(body, "reason"));
    }

    @PostMapping("/rule-sets/{ruleSetId}/withdraw")
    public Map<String, Object> withdrawRuleSet(HttpServletRequest request, @PathVariable String code,
                                               @PathVariable String ruleSetId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        Map<String, Object> withdrawn = catalog.withdrawRuleSet(t.tenantId(), ruleSetId, str(body, "reason"));
        // Withdrawing the rule set the gate currently serves fails the gate closed immediately.
        gate.find(t.tenantId()).ifPresent(g -> {
            if (TemplateRouteGate.STATE_ENABLED.equals(g.state())
                    && g.activeRuleSetId() != null
                    && g.activeRuleSetId().toString().equals(ruleSetId)) {
                gate.block(t, "active rule set withdrawn: " + str(body, "reason"));
            }
        });
        return withdrawn;
    }

    @GetMapping("/rule-sets")
    public Map<String, Object> listRuleSets(@PathVariable String code) {
        Tenant t = tenant(code);
        return Map.of("tenant", t.code(), "ruleSets", catalog.listRuleSets(t.tenantId()));
    }

    @GetMapping("/rule-sets/{ruleSetId}")
    public Map<String, Object> getRuleSet(@PathVariable String code, @PathVariable String ruleSetId) {
        return catalog.getRuleSet(tenant(code).tenantId(), ruleSetId);
    }

    @GetMapping("/rules")
    public Map<String, Object> rules(@PathVariable String code, @RequestParam String analyteCode) {
        Tenant t = tenant(code);
        return Map.of("tenant", t.code(), "analyteCode", analyteCode,
                "rules", catalog.rulesForAnalyte(t.tenantId(), analyteCode));
    }

    // ---------- templates ----------

    @PostMapping("/templates")
    public Map<String, Object> createTemplate(HttpServletRequest request, @PathVariable String code,
                                              @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        JsonNode content = mapper.valueToTree(body.getOrDefault("content", Map.of()));
        UUID ruleSetId = str(body, "ruleSetId") == null ? null : UUID.fromString(str(body, "ruleSetId"));
        var req = new TemplateCatalogService.TemplateRequest(str(body, "templateCode"),
                str(body, "analyteCode"), str(body, "name"), content, ruleSetId,
                instant(body, "effectiveFrom"));
        return catalog.createTemplate(t.tenantId(), req);
    }

    @PostMapping("/templates/{templateId}/publish")
    public Map<String, Object> publishTemplate(HttpServletRequest request, @PathVariable String code,
                                               @PathVariable String templateId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        return catalog.publishTemplate(tenant(code).tenantId(), templateId, instant(body, "effectiveFrom"));
    }

    @PostMapping("/templates/{templateId}/withdraw")
    public Map<String, Object> withdrawTemplate(HttpServletRequest request, @PathVariable String code,
                                                @PathVariable String templateId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        return catalog.withdrawTemplate(tenant(code).tenantId(), templateId, str(body, "reason"));
    }

    @GetMapping("/templates")
    public Map<String, Object> listTemplates(@PathVariable String code) {
        Tenant t = tenant(code);
        return Map.of("tenant", t.code(), "templates", catalog.listTemplates(t.tenantId()));
    }

    // ---------- shadow rendering & history ----------

    @PostMapping("/shadow/render")
    public Map<String, Object> renderOne(HttpServletRequest request, @PathVariable String code,
                                         @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        String ruleSetId = str(body, "ruleSetId");
        ShadowRenderer.ShadowResult sr = renderer.renderReport(t, str(body, "space"),
                str(body, "reportId"),
                body.get("revision") == null ? null : ((Number) body.get("revision")).intValue(),
                ruleSetId == null ? null : UUID.fromString(ruleSetId), str(body, "reason"));
        gate.refresh(t.tenantId());
        return Map.of("shadowId", sr.shadowId().toString(), "reportId", sr.reportId(),
                "revision", sr.revision(), "result", sr.result(), "diffCount", sr.diffCount(),
                "baselineHash", sr.baselineHash(), "candidateHash", sr.candidateHash(),
                "baselineRows", sr.baselineRows(), "candidateRows", sr.candidateRows(),
                "triage", sr.triageRows());
    }

    @PostMapping("/shadow/render-space")
    public Map<String, Object> renderSpace(HttpServletRequest request, @PathVariable String code,
                                           @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        String ruleSetId = str(body, "ruleSetId");
        Map<String, Object> out = renderer.renderSpace(t, str(body, "space"),
                ruleSetId == null ? null : UUID.fromString(ruleSetId), str(body, "reason"),
                str(body, "historyMode"),
                body.get("limit") == null ? 100 : ((Number) body.get("limit")).intValue());
        gate.refresh(t.tenantId());
        return out;
    }

    @PostMapping("/history/materialize")
    public Map<String, Object> materialize(HttpServletRequest request, @PathVariable String code,
                                           @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        int limit = body == null || body.get("limit") == null ? 100 : ((Number) body.get("limit")).intValue();
        return renderer.materializeLegacy(t, str(body, "space"), limit);
    }

    @PostMapping("/history/replay")
    public Map<String, Object> replay(HttpServletRequest request, @PathVariable String code,
                                      @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        int limit = body == null || body.get("limit") == null ? 100 : ((Number) body.get("limit")).intValue();
        String ruleSetId = body == null ? null : str(body, "ruleSetId");
        return renderer.replayHistory(t, str(body, "space"),
                ruleSetId == null ? null : UUID.fromString(ruleSetId), limit);
    }

    @GetMapping("/shadow/renders")
    public Map<String, Object> renders(@PathVariable String code,
                                       @RequestParam(required = false) String reportId) {
        Tenant t = tenant(code);
        return Map.of("tenant", t.code(), "renders", renderer.listRenders(t.tenantId(), reportId));
    }

    @GetMapping("/shadow/renders/{shadowId}")
    public Map<String, Object> renderDetail(@PathVariable String code, @PathVariable String shadowId) {
        return renderer.renderDetail(tenant(code).tenantId(), shadowId);
    }

    @GetMapping("/interpretations/{reportId}")
    public Map<String, Object> interpretations(@PathVariable String code, @PathVariable String reportId,
                                               @RequestParam(required = false) Integer revision) {
        Tenant t = tenant(code);
        return Map.of("tenant", t.code(), "reportId", reportId,
                "interpretations", renderer.listInterpretations(t.tenantId(), reportId, revision));
    }

    // ---------- triage ----------

    @GetMapping("/triage")
    public Map<String, Object> triage(@PathVariable String code,
                                      @RequestParam(required = false) String status) {
        Tenant t = tenant(code);
        return Map.of("tenant", t.code(), "openCounts", triage.openCounts(t.tenantId()),
                "findings", triage.list(t.tenantId(), status));
    }

    @PostMapping("/triage/{triageId}/resolve")
    public Map<String, Object> resolveTriage(HttpServletRequest request, @PathVariable String code,
                                             @PathVariable String triageId,
                                             @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        Map<String, Object> out = triage.resolve(t.tenantId(), triageId, str(body, "decision"),
                str(body, "note"));
        gate.refresh(t.tenantId());
        return out;
    }

    // ---------- gate ----------

    @GetMapping("/gate")
    public Map<String, Object> gateState(@PathVariable String code) {
        Tenant t = tenant(code);
        return gate.state(t);
    }

    @PostMapping("/gate/init")
    public Map<String, Object> initGate(HttpServletRequest request, @PathVariable String code,
                                        @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        TemplateRouteGate.GateRow row = gate.ensureGate(t.tenantId(),
                body == null ? null : str(body, "historyMode"));
        return gate.state(t);
    }

    @PostMapping("/gate/begin")
    public Map<String, Object> begin(HttpServletRequest request, @PathVariable String code,
                                     @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        gate.beginEvaluation(t, str(body, "templateId"), str(body, "ruleSetId"), str(body, "historyMode"));
        return gate.state(t);
    }

    @PostMapping("/gate/enable")
    public Map<String, Object> enable(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        Tenant t = tenant(code);
        gate.enable(t);
        return gate.state(t);
    }

    @PostMapping("/gate/block")
    public Map<String, Object> block(HttpServletRequest request, @PathVariable String code,
                                     @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        gate.block(t, body == null ? "operator hold" : str(body, "reason"));
        return gate.state(t);
    }

    @PostMapping("/gate/reset")
    public Map<String, Object> reset(HttpServletRequest request, @PathVariable String code,
                                     @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        Tenant t = tenant(code);
        gate.resetToShadow(t, body == null ? "operator reset" : str(body, "reason"));
        return gate.state(t);
    }

    // ---------- served interpretation ----------

    @GetMapping("/reports/{reportId}/interpretation")
    public Map<String, Object> interpretation(@PathVariable String code, @PathVariable String reportId,
                                              @RequestParam(required = false) Integer revision) {
        return routing.interpret(tenant(code), reportId, revision);
    }
}
