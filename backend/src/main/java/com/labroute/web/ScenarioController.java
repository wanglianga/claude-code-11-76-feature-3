package com.labroute.web;

import com.labroute.model.Tenant;
import com.labroute.service.DemoDataService;
import com.labroute.service.ScenarioService;
import com.labroute.service.TenantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ScenarioController {

    private final ScenarioService scenarios;
    private final DemoDataService demo;
    private final TenantService tenants;

    public ScenarioController(ScenarioService scenarios, DemoDataService demo, TenantService tenants) {
        this.scenarios = scenarios;
        this.demo = demo;
        this.tenants = tenants;
    }

    @GetMapping("/scenarios")
    public Map<String, Object> list() {
        return Map.of("scenarios", scenarios.scenarios(), "history", scenarios.history(20));
    }

    @PostMapping("/scenarios/{code}/run")
    public Map<String, Object> run(@PathVariable String code,
                                   @RequestParam(defaultValue = "lab-harbor") String tenant) {
        return scenarios.run(code, tenant);
    }

    @PostMapping("/scenarios/run-all")
    public Map<String, Object> runAll(@RequestParam(defaultValue = "lab-harbor") String tenant) {
        return scenarios.runAll(tenant);
    }

    @GetMapping("/scenarios/runs/{runId}")
    public Map<String, Object> get(@PathVariable String runId) {
        return scenarios.get(runId);
    }

    /** Injects a demo message through the real signed gateway path (for the console). */
    @PostMapping("/demo/tenants/{code}/messages/{kind}")
    public Map<String, Object> demoMessage(@PathVariable String code, @PathVariable String kind,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Tenant tenant = tenants.requireByCode(code);
        Map<String, Object> payload;
        String customId = body == null ? null : (String) body.get("id");
        switch (kind) {
            case "sample" -> payload = demo.samplePayload(
                    customId != null ? customId : demo.uniqueKey("DM-S"), "SOURCE-A");
            case "result" -> {
                String sampleId = (String) body.get("sampleId");
                if (sampleId == null) {
                    throw ApiException.badRequest("SAMPLE_REQUIRED", "demo result needs sampleId");
                }
                payload = demo.resultPayload(customId != null ? customId : demo.uniqueKey("DM-RES"),
                        sampleId,
                        (String) body.getOrDefault("analyteCode", "GLU"),
                        (String) body.getOrDefault("value", "5.2"),
                        (String) body.getOrDefault("unit", "mmol/L"),
                        (String) body.getOrDefault("refLow", "3.9"),
                        (String) body.getOrDefault("refHigh", "5.5"));
            }
            case "report" -> {
                String sampleId = (String) body.get("sampleId");
                if (sampleId == null) {
                    throw ApiException.badRequest("SAMPLE_REQUIRED", "demo report needs sampleId");
                }
                int revision = body.get("revision") == null ? 1 : ((Number) body.get("revision")).intValue();
                payload = demo.reportPayload(customId != null ? customId : demo.uniqueKey("DM-REP"),
                        sampleId, revision, "demo-" + revision);
                if (revision > 1) {
                    payload.put("supersedesRevision", revision - 1);
                }
            }
            case "object" -> {
                String reportId = (String) body.get("reportId");
                if (reportId == null) {
                    throw ApiException.badRequest("REPORT_REQUIRED", "demo object needs reportId");
                }
                payload = demo.objectPayload(customId != null ? customId : demo.uniqueKey("DM-OBJ"), reportId);
            }
            default -> throw ApiException.badRequest("KIND_INVALID", "unknown message kind " + kind);
        }
        return demo.deliver(tenant, kind, payload, null, null);
    }

    @GetMapping("/demo/tenants/{code}/signing-material")
    public Map<String, Object> signingMaterial(@PathVariable String code) {
        Tenant tenant = tenants.requireByCode(code);
        return Map.of("tenant", tenant.code(),
                "credentials", demo.credentialsForDisplay(tenant.tenantId()),
                "signingScheme", List.of("POST", "path", "tenantCode", "sourceCode", "keyId",
                        "nonce", "signedAt", "sha256(canonicalBody)"),
                "note", "join with \\n, HMAC-SHA256 with the credential secret, hex lowercase");
    }
}
