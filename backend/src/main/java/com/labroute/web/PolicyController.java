package com.labroute.web;

import com.labroute.model.Tenant;
import com.labroute.service.PolicyService;
import com.labroute.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PolicyController {

    private final TenantService tenants;
    private final PolicyService policies;
    private final OperatorAuth auth;

    public PolicyController(TenantService tenants, PolicyService policies, OperatorAuth auth) {
        this.tenants = tenants;
        this.policies = policies;
        this.auth = auth;
    }

    private void guard(HttpServletRequest request) {
        if (!auth.authorised(request)) {
            throw ApiException.unauthorized("OPERATOR_TOKEN_REQUIRED",
                    "control-plane operations require X-Operator-Token");
        }
    }

    @PostMapping("/tenants/{code}/isolation")
    public Map<String, Object> change(HttpServletRequest request, @PathVariable String code,
                                      @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenants.requireByCode(code);
        return policies.change(t, (String) body.get("level"), (String) body.get("reason"));
    }

    @GetMapping("/tenants/{code}/policy-events")
    public Map<String, Object> events(@PathVariable String code) {
        Tenant t = tenants.requireByCode(code);
        return Map.of("tenant", t.code(), "policyVersion", t.policyVersion(),
                "isolationLevel", t.isolationLevel(), "events", tenants.policyEvents(t.tenantId()));
    }

    @PostMapping("/tenants/{code}/credentials/{credentialId}/reauthorise")
    public Map<String, Object> reauthorise(HttpServletRequest request, @PathVariable String code,
                                           @PathVariable String credentialId,
                                           @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenants.requireByCode(code);
        return policies.reauthorise(t, credentialId,
                (String) body.getOrDefault("scopes", "intake:sample,intake:result,intake:report,intake:object"));
    }

    @GetMapping("/isolation/verify")
    public Map<String, Object> verify(@RequestParam String a, @RequestParam String b) {
        return policies.verifyIsolation(a, b);
    }

    @GetMapping("/platform/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new HashMap<>();
        out.put("tenants", tenants.list().stream().map(tenants::summary).toList());
        out.put("isolationChecks", List.of());
        return out;
    }
}
