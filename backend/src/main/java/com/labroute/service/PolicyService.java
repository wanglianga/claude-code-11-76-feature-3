package com.labroute.service;

import com.labroute.model.Tenant;
import com.labroute.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant isolation policy changes.
 *
 * Changing LOGICAL -> STRICT (or rotating the dedicated role) bumps policy_version and
 * immediately invalidates credentials bound to the old policy generation. It also
 * tightens the authentication window from then on. Published reports are unaffected:
 * a policy change never reassigns a report to another tenant or another space.
 */
@Service
public class PolicyService {

    private final JdbcTemplate jdbc;
    private final TenantService tenants;
    private final EvidenceService evidence;

    public PolicyService(JdbcTemplate jdbc, TenantService tenants, EvidenceService evidence) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.evidence = evidence;
    }

    @Transactional
    public Map<String, Object> change(Tenant tenant, String targetLevel, String reason) {
        String level = targetLevel == null ? tenant.isolationLevel() : targetLevel.toUpperCase();
        if (!List.of("LOGICAL", "STRICT", "DEDICATED_ROLE").contains(level)) {
            throw ApiException.badRequest("ISOLATION_LEVEL_INVALID",
                    "isolation level must be LOGICAL, STRICT or DEDICATED_ROLE");
        }
        if (level.equals(tenant.isolationLevel())) {
            throw ApiException.conflict("POLICY_NO_CHANGE",
                    "tenant already uses isolation level " + level, Map.of());
        }
        int nextVersion = tenant.policyVersion() + 1;
        String role = "DEDICATED_ROLE".equals(level)
                ? "tenant_" + tenant.code().replace('-', '_') + "_rw" : null;
        jdbc.update("""
                update lab.tenant set isolation_level = ?, policy_version = ?, dedicated_role = ?,
                  isolation_changed_at = now(), updated_at = now() where tenant_id = ?::uuid
                """, level, nextVersion, role, tenant.tenantId());
        tenants.recordPolicyEvent(tenant.tenantId(), nextVersion, tenant.isolationLevel(), level,
                nextVersion, role, reason);

        // Credentials minted under the previous policy lose their intake scopes until re-issued.
        int revokedScope = jdbc.update("""
                update lab.source_credential set scopes = 'intake:none'
                where tenant_id = ?::uuid and scopes <> 'intake:none'
                """, tenant.tenantId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromLevel", tenant.isolationLevel());
        payload.put("toLevel", level);
        payload.put("policyVersion", nextVersion);
        payload.put("dedicatedRole", role);
        payload.put("reason", reason);
        payload.put("credentialNote", "re-issue credentials with new scopes for policy version " + nextVersion);
        var ev = evidence.append(tenant.tenantId(), "ISOLATION_POLICY_CHANGED", "TENANT", tenant.code(),
                tenant.readSpace(), "VERIFIED", payload);
        payload.put("evidenceId", ev.evidenceId());
        payload.put("affectedCredentials", revokedScope);
        return payload;
    }

    /** Re-issues a credential's scopes under the current policy version. */
    @Transactional
    public Map<String, Object> reauthorise(Tenant tenant, String credentialId, String scopes) {
        int n = jdbc.update("""
                update lab.source_credential set scopes = ?, key_version = key_version + 1, active = true
                where tenant_id = ?::uuid and credential_id = ?::uuid
                """, scopes, tenant.tenantId(), credentialId);
        if (n == 0) {
            throw ApiException.notFound("CREDENTIAL_NOT_FOUND", "unknown credential for this tenant");
        }
        evidence.append(tenant.tenantId(), "CREDENTIAL_REAUTHORISED", "CREDENTIAL", credentialId,
                tenant.readSpace(), "VERIFIED", Map.of("scopes", scopes,
                        "policyVersion", tenant.policyVersion()));
        return Map.of("credentialId", credentialId, "scopes", scopes,
                "policyVersion", tenant.policyVersion(), "reauthorised", true);
    }

    public Map<String, Object> verifyIsolation(String codeA, String codeB) {
        Tenant a = tenants.requireByCode(codeA);
        Tenant b = tenants.requireByCode(codeB);
        int leaks = 0;
        List<Map<String, Object>> probes = new java.util.ArrayList<>();
        for (String[] pair : new String[][]{{"OLD", "OLD"}, {"OLD", "NEW"}, {"NEW", "OLD"}, {"NEW", "NEW"}}) {
            String schemaA = "OLD".equals(pair[0]) ? a.oldSchema() : a.newSchema();
            String schemaB = "OLD".equals(pair[1]) ? b.oldSchema() : b.newSchema();
            Long leaked = jdbc.queryForObject("""
                    select count(*) from %s.sample sa join %s.sample sb on sb.sample_id = sa.sample_id
                    """.formatted(schemaA, schemaB), Long.class);
            long n = leaked == null ? 0 : leaked;
            leaks += (int) n;
            probes.add(Map.of("tenantA", codeA, "spaceA", pair[0], "tenantB", codeB, "spaceB", pair[1],
                    "sharedPrimaryKeys", n));
        }
        boolean isolated = leaks == 0 && !a.oldSchema().equals(b.newSchema());
        return Map.of("isolated", isolated, "crossTenantKeyCollisions", leaks,
                "schemas", Map.of(codeA, List.of(a.oldSchema(), a.newSchema()),
                        codeB, List.of(b.oldSchema(), b.newSchema())),
                "probes", probes);
    }
}
