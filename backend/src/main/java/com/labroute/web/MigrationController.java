package com.labroute.web;

import com.labroute.model.Tenant;
import com.labroute.service.MigrationService;
import com.labroute.service.ReplicationService;
import com.labroute.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Operator control plane for the routing gate, migration state machine and rollback. */
@RestController
@RequestMapping("/api/tenants/{code}")
public class MigrationController {

    private final TenantService tenants;
    private final MigrationService migrations;
    private final ReplicationService replication;
    private final com.labroute.service.IntakeService intake;
    private final OperatorAuth auth;

    public MigrationController(TenantService tenants, MigrationService migrations,
                               ReplicationService replication, com.labroute.service.IntakeService intake,
                               OperatorAuth auth) {
        this.tenants = tenants;
        this.migrations = migrations;
        this.replication = replication;
        this.intake = intake;
        this.auth = auth;
    }

    private void guard(HttpServletRequest request) {
        if (!auth.authorised(request)) {
            throw ApiException.unauthorized("OPERATOR_TOKEN_REQUIRED",
                    "control-plane operations require X-Operator-Token");
        }
    }

    @GetMapping("/routing")
    public Map<String, Object> routing(@PathVariable String code) {
        return migrations.routingState(tenants.requireByCode(code));
    }

    @GetMapping("/migration")
    public Map<String, Object> migration(@PathVariable String code) {
        Tenant tenant = tenants.requireByCode(code);
        return Map.of("tenant", tenant.code(), "phase", tenant.phase(),
                "routing", migrations.routingState(tenant),
                "migrations", migrations.list(tenant.tenantId()));
    }

    @PostMapping("/migration/start")
    public Map<String, Object> start(HttpServletRequest request, @PathVariable String code,
                                     @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        Tenant tenant = tenants.requireByCode(code);
        String strategy = body == null ? null : (String) body.get("strategy");
        return migrations.start(tenant, strategy);
    }

    // ---- FENCE_REPLAY ----

    @PostMapping("/migration/fence/open")
    public Map<String, Object> openFence(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        return migrations.openFence(tenants.requireByCode(code));
    }

    @PostMapping("/migration/fence/drain")
    public Map<String, Object> drainFence(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        return migrations.drainFence(tenants.requireByCode(code));
    }

    @PostMapping("/migration/fence/release")
    public Map<String, Object> releaseFence(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        return migrations.releaseFence(tenants.requireByCode(code));
    }

    // ---- WATERMARK_ONLINE ----

    @PostMapping("/migration/online/dual-write")
    public Map<String, Object> dualWrite(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        return migrations.startDualWrite(tenants.requireByCode(code));
    }

    @PostMapping("/migration/online/shadow-check")
    public Map<String, Object> shadowCheck(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        return migrations.shadowCheck(tenants.requireByCode(code));
    }

    @PostMapping("/migration/online/switch-read")
    public Map<String, Object> switchRead(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        return migrations.switchRead(tenants.requireByCode(code));
    }

    @PostMapping("/migration/online/switch-write")
    public Map<String, Object> switchWrite(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        return migrations.switchWrite(tenants.requireByCode(code));
    }

    @PostMapping("/migration/replicate")
    public Map<String, Object> replicate(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        Tenant tenant = tenants.requireByCode(code);
        MigrationService.MigrationRow m = migrations.requireMigration(tenant.tenantId());
        int applied = replication.replicateDeltas(tenant, m.migrationId());
        replication.refreshWatermarks(tenant, m.migrationId(), false);
        return Map.of("appliedRows", applied, "watermarks", replication.watermarks(m.migrationId()));
    }

    @PostMapping("/migration/shadow-compare")
    public Map<String, Object> shadowCompare(HttpServletRequest request, @PathVariable String code) {
        guard(request);
        Tenant tenant = tenants.requireByCode(code);
        MigrationService.MigrationRow m = migrations.requireMigration(tenant.tenantId());
        return replication.shadowCompare(tenant, m.migrationId(), "MANUAL");
    }

    @GetMapping("/migration/shadow-comparisons")
    public Map<String, Object> comparisons(@PathVariable String code) {
        Tenant tenant = tenants.requireByCode(code);
        return Map.of("tenant", tenant.code(), "comparisons", replication.comparisons(tenant.tenantId()));
    }

    @PostMapping("/migration/rollback")
    public Map<String, Object> rollback(HttpServletRequest request, @PathVariable String code,
                                        @RequestBody(required = false) Map<String, Object> body) {
        guard(request);
        String reason = body == null ? null : (String) body.get("reason");
        return migrations.rollback(tenants.requireByCode(code), reason);
    }

    @GetMapping("/migration/fence-timeline")
    public Object fenceTimeline(@PathVariable String code) {
        return migrations.fenceTimeline(tenants.requireByCode(code).tenantId());
    }

    @GetMapping("/intake/messages")
    public Map<String, Object> messages(@PathVariable String code,
                                        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        Tenant tenant = tenants.requireByCode(code);
        return Map.of("tenant", tenant.code(), "messages", intake.messages(tenant.tenantId(), limit),
                "stats", intake.stats(tenant.tenantId()));
    }

    @GetMapping("/intake/messages/{messageId}")
    public Map<String, Object> messageDetail(@PathVariable String code, @PathVariable String messageId) {
        Tenant tenant = tenants.requireByCode(code);
        return intake.messageDetail(tenant.tenantId(), messageId);
    }
}
