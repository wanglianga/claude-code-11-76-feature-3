package com.labroute.web;

import com.labroute.model.Tenant;
import com.labroute.service.PublicationService;
import com.labroute.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Report publication interface. Stays up through migration; every response is
 * either a verified document tagged with its source space, or an explicit
 * PENDING_VERIFICATION payload. It is never a 503 for "migration in progress".
 */
@RestController
public class PublicationController {

    private final TenantService tenants;
    private final PublicationService publication;

    public PublicationController(TenantService tenants, PublicationService publication) {
        this.tenants = tenants;
        this.publication = publication;
    }

    @GetMapping("/api/tenant/{tenantCode}/publish/reports/{reportId}")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable String tenantCode,
                                                       @PathVariable String reportId) {
        PublicationService.PublishResult result = publication.publish(tenantCode, reportId);
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    @GetMapping("/api/tenant/{tenantCode}/publish/reports")
    public Map<String, Object> inventory(@PathVariable String tenantCode,
                                         @RequestParam(defaultValue = "READ") String space) {
        Tenant tenant = tenants.requireByCode(tenantCode);
        String role = "READ".equalsIgnoreCase(space) ? tenant.readSpace() : space;
        return publication.reportInventory(tenantCode, role);
    }

    @GetMapping("/api/tenant/{tenantCode}/publications")
    public Map<String, Object> publications(@PathVariable String tenantCode,
                                            @RequestParam(defaultValue = "50") int limit) {
        Tenant tenant = tenants.requireByCode(tenantCode);
        return Map.of("tenant", tenant.code(), "publications", publication.publications(tenant.tenantId(), limit));
    }

    @GetMapping("/api/tenant/{tenantCode}/publish/reports/{reportId}/replay")
    public Map<String, Object> replay(@PathVariable String tenantCode, @PathVariable String reportId) {
        return publication.replay(tenantCode, reportId);
    }
}
