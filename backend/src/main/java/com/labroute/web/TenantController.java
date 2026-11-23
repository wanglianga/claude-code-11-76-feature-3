package com.labroute.web;

import com.labroute.db.SchemaService;
import com.labroute.model.Tenant;
import com.labroute.service.AuthService;
import com.labroute.service.EvidenceService;
import com.labroute.service.IntakeService;
import com.labroute.service.MigrationService;
import com.labroute.service.PublicationService;
import com.labroute.service.ReplicationService;
import com.labroute.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TenantController {

    private final TenantService tenants;
    private final AuthService auth;
    private final EvidenceService evidence;
    private final MigrationService migrations;
    private final IntakeService intake;
    private final PublicationService publication;
    private final ReplicationService replication;
    private final SchemaService schemaService;
    private final JdbcTemplate jdbc;
    private final OperatorAuth operatorAuth;

    public TenantController(TenantService tenants, AuthService auth, EvidenceService evidence,
                            MigrationService migrations, IntakeService intake,
                            PublicationService publication, ReplicationService replication,
                            SchemaService schemaService, JdbcTemplate jdbc, OperatorAuth operatorAuth) {
        this.tenants = tenants;
        this.auth = auth;
        this.evidence = evidence;
        this.migrations = migrations;
        this.intake = intake;
        this.publication = publication;
        this.replication = replication;
        this.schemaService = schemaService;
        this.jdbc = jdbc;
        this.operatorAuth = operatorAuth;
    }

    private void guard(HttpServletRequest request) {
        if (!operatorAuth.authorised(request)) {
            throw ApiException.unauthorized("OPERATOR_TOKEN_REQUIRED",
                    "control-plane operations require X-Operator-Token");
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "labroute");
    }

    @GetMapping("/tenants")
    public Map<String, Object> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tenant t : tenants.list()) {
            out.add(tenants.summary(t));
        }
        return Map.of("tenants", out);
    }

    @PostMapping("/tenants")
    public Map<String, Object> create(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant created = tenants.create(new TenantService.CreateTenant(
                (String) body.get("code"), (String) body.get("name"), (String) body.get("strategy")));
        return tenants.summary(tenants.requireById(created.tenantId()));
    }

    @GetMapping("/tenants/{code}")
    public Map<String, Object> get(@PathVariable String code) {
        Tenant t = tenants.requireByCode(code);
        Map<String, Object> out = new LinkedHashMap<>(tenants.summary(t));
        out.put("routing", migrations.routingState(t));
        out.put("policyEvents", tenants.policyEvents(t.tenantId()));
        out.put("intakeStats", intake.stats(t.tenantId()));
        out.put("credentials", auth.credentials(t.tenantId()).stream().map(c -> Map.of(
                "credentialId", c.credentialId(), "sourceCode", c.sourceCode(), "keyId", c.keyId(),
                "scopes", c.scopes(), "keyVersion", c.keyVersion(), "active", c.active())).toList());
        out.put("publications", publication.publications(t.tenantId(), 25));
        out.put("evidenceChain", evidence.verifyChain(t.tenantId()));
        return out;
    }

    @PostMapping("/tenants/{code}/credentials")
    public Map<String, Object> addCredential(HttpServletRequest request, @PathVariable String code,
                                             @RequestBody Map<String, Object> body) {
        guard(request);
        Tenant t = tenants.requireByCode(code);
        AuthService.CredentialRecord rec = auth.addCredential(t.tenantId(),
                (String) body.get("sourceCode"), (String) body.get("secret"),
                (String) body.getOrDefault("scopes", "intake:sample,intake:result,intake:report,intake:object"));
        evidence.append(t.tenantId(), "CREDENTIAL_ADDED", "CREDENTIAL", rec.keyId(), t.readSpace(), "VERIFIED",
                Map.of("sourceCode", rec.sourceCode(), "scopes", rec.scopes()));
        return Map.of("credentialId", rec.credentialId(), "sourceCode", rec.sourceCode(),
                "keyId", rec.keyId(), "secret", rec.secret(), "scopes", rec.scopes());
    }

    @PostMapping("/tenants/{code}/credentials/{credentialId}/rotate")
    public Map<String, Object> rotate(HttpServletRequest request, @PathVariable String code,
                                      @PathVariable String credentialId) {
        guard(request);
        Tenant t = tenants.requireByCode(code);
        auth.rotate(t.tenantId(), credentialId, "operator rotation");
        evidence.append(t.tenantId(), "CREDENTIAL_ROTATED", "CREDENTIAL", credentialId, t.readSpace(), "VERIFIED",
                Map.of("reason", "operator rotation"));
        return Map.of("rotated", true, "credentialId", credentialId);
    }

    @GetMapping("/tenants/{code}/boundary-book")
    public Map<String, Object> boundaryBook(@PathVariable String code) {
        Tenant t = tenants.requireByCode(code);
        List<Map<String, Object>> entries = jdbc.queryForList("""
                select entry_id::text as "entryId", sample_id as "sampleId", chain_version as "chainVersion",
                       entry_type as "entryType", source_code as "sourceCode", key_id as "keyId",
                       authorization_scope as "scope", payload_hash as "payloadHash", signature,
                       message_seq as "messageSeq", space_role as "spaceRole", status, created_at as "createdAt"
                from lab.boundary_book_entry where tenant_id = ?::uuid
                order by created_at desc limit 200
                """, t.tenantId());
        return Map.of("tenant", t.code(), "entries", entries);
    }

    @GetMapping("/tenants/{code}/samples/{sampleId}/chain")
    public Object sampleChain(@PathVariable String code, @PathVariable String sampleId,
                              @RequestParam(defaultValue = "READ") String space) {
        Tenant t = tenants.requireByCode(code);
        String role = "READ".equalsIgnoreCase(space) ? t.readSpace() : space.toUpperCase();
        return tenants.sampleChain(t, role, sampleId);
    }

    @GetMapping("/tenants/{code}/evidence")
    public Map<String, Object> evidence(@PathVariable String code,
                                        @RequestParam(required = false) String entity,
                                        @RequestParam(defaultValue = "100") int limit) {
        Tenant t = tenants.requireByCode(code);
        return Map.of("tenant", t.code(), "entries", evidence.list(t.tenantId(), entity, limit),
                "chain", evidence.verifyChain(t.tenantId()));
    }

    @GetMapping("/tenants/{code}/findings")
    public Map<String, Object> findings(@PathVariable String code,
                                        @RequestParam(defaultValue = "100") int limit) {
        Tenant t = tenants.requireByCode(code);
        return Map.of("tenant", t.code(), "findings", jdbc.queryForList("""
                select finding_id::text as "findingId", sample_id as "sampleId", result_id as "resultId",
                       report_id as "reportId", rule_code as "ruleCode", severity, message, detail,
                       status, detected_at as "detectedAt"
                from lab.semantic_finding where tenant_id = ?::uuid order by detected_at desc limit ?
                """, t.tenantId(), limit));
    }

    @GetMapping("/tenants/{code}/watermarks")
    public Map<String, Object> watermarks(@PathVariable String code) {
        Tenant t = tenants.requireByCode(code);
        MigrationService.MigrationRow m = migrations.active(t.tenantId());
        if (m == null) {
            List<MigrationService.MigrationRow> history = migrations.list(t.tenantId());
            m = history.isEmpty() ? null : history.get(0);
        }
        if (m == null) {
            return Map.of("tenant", t.code(), "watermarks", List.of(), "summary", Map.of("maxLag", 0L));
        }
        return Map.of("tenant", t.code(), "migrationId", m.migrationId(),
                "watermarks", replication.watermarks(m.migrationId()),
                "summary", replication.watermarkSummary(m.migrationId()));
    }
}
