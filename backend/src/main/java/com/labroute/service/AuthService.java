package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.labroute.config.LabRouteProperties;
import com.labroute.model.Tenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fail-closed source authentication. A message is accepted only when its key is
 * active, bound to exactly this tenant, its scope covers the endpoint, the HMAC
 * matches, the timestamp is inside the tenant policy window and the nonce is new.
 * Nothing here ever falls back to "accept because tenant exists".
 */
@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final CryptoSupport crypto;
    private final LabRouteProperties props;

    public AuthService(JdbcTemplate jdbc, CryptoSupport crypto, LabRouteProperties props) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.props = props;
    }

    public record VerifiedSource(String sourceCode, String keyId, String scope, String nonce,
                                 OffsetDateTime signedAt, String signature, String secret) {
    }

    public VerifiedSource verify(Tenant tenant, String sourceCode, String keyId, String nonce,
                                 String signedAtRaw, String signature, String signatureHeader,
                                 String bodyCanonical, String endpointScope) {
        VerifiedSource verified = verifySignature(tenant, sourceCode, keyId, nonce, signedAtRaw,
                signature, signatureHeader, bodyCanonical, endpointScope);
        consumeNonce(tenant, sourceCode, nonce, verified.signedAt());
        return verified;
    }

    /**
     * Verifies key binding, timestamp window, HMAC and scope WITHOUT burning the nonce.
     * Intake calls this first so that a legitimate idempotent retry (same nonce, same body)
     * can return the original receipt instead of being rejected as a replay.
     */
    public VerifiedSource verifySignature(Tenant tenant, String sourceCode, String keyId, String nonce,
                                          String signedAtRaw, String signature, String signatureHeader,
                                          String bodyCanonical, String endpointScope) {
        String effectiveSig = signature != null ? signature : signatureHeader;
        if (sourceCode == null || keyId == null || nonce == null || signedAtRaw == null || effectiveSig == null) {
            throw com.labroute.web.ApiException.unauthorized("AUTH_HEADERS_MISSING",
                    "X-Source-Code, X-Key-Id, X-Nonce, X-Signed-At and X-Signature are required");
        }
        if (nonce.length() < 8 || nonce.length() > 120) {
            throw com.labroute.web.ApiException.unauthorized("AUTH_NONCE_INVALID", "nonce length out of bounds");
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select key_id, secret, scopes, active, key_version
                from lab.source_credential
                where key_id = ? and tenant_id = ?::uuid and source_code = ?
                """, keyId, tenant.tenantId(), sourceCode);
        if (rows.isEmpty()) {
            throw com.labroute.web.ApiException.unauthorized("AUTH_KEY_UNKNOWN",
                    "key is not registered for this tenant and source (tenant boundaries are per key)");
        }
        Map<String, Object> key = rows.get(0);
        if (!Boolean.TRUE.equals(key.get("active"))) {
            throw com.labroute.web.ApiException.unauthorized("AUTH_KEY_INACTIVE", "key has been rotated out or revoked");
        }

        OffsetDateTime signedAt;
        try {
            signedAt = OffsetDateTime.parse(signedAtRaw);
        } catch (Exception e) {
            throw com.labroute.web.ApiException.unauthorized("AUTH_TIMESTAMP_INVALID", "X-Signed-At must be ISO-8601");
        }
        String level = tenant.isolationLevel();
        long skew = "STRICT".equals(level) ? props.auth().strictSkewSeconds() : props.auth().standardSkewSeconds();
        long drift = Math.abs(ChronoUnit.SECONDS.between(signedAt, OffsetDateTime.now()));
        if (drift > skew) {
            throw new com.labroute.web.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "AUTH_TIMESTAMP_SKEW",
                    "signature timestamp outside the " + level + " policy window of " + skew + "s",
                    Map.of("driftSeconds", drift, "allowedSeconds", skew, "isolationLevel", level));
        }

        String signing = crypto.signingString("POST", endpointScope, tenant.code(), sourceCode,
                keyId, nonce, signedAtRaw, bodyCanonical);
        String expected = crypto.hmacSha256Hex((String) key.get("secret"), signing);
        if (!CryptoSupport.constantTimeEquals(expected, effectiveSig.toLowerCase())) {
            throw com.labroute.web.ApiException.unauthorized("AUTH_SIGNATURE_MISMATCH",
                    "HMAC signature does not match the canonical request");
        }

        String scopes = (String) key.get("scopes");
        if (!scopeAllows(scopes, endpointScope)) {
            throw com.labroute.web.ApiException.forbidden("AUTH_SCOPE_DENIED",
                    "credential scope " + scopes + " does not grant " + endpointScope);
        }
        return new VerifiedSource(sourceCode, keyId, scopes, nonce, signedAt, effectiveSig, (String) key.get("secret"));
    }

    public void consumeNonce(Tenant tenant, String sourceCode, String nonce, OffsetDateTime signedAt) {
        int inserted = jdbc.update("""
                insert into lab.used_nonce(tenant_id, source_code, nonce, signed_at)
                values (?::uuid, ?, ?, ?) on conflict do nothing
                """, tenant.tenantId(), sourceCode, nonce, signedAt);
        if (inserted == 0) {
            throw com.labroute.web.ApiException.unauthorized("AUTH_REPLAY",
                    "nonce has already been used for this tenant; exact replays must reuse Idempotency-Key semantics");
        }
    }

    /** Maps an intake path to the scope token stored on credentials: /api/intake/sample -> intake:sample. */
    public static String scopeOf(String endpoint) {
        return endpoint.replaceFirst("^/api/", "").replace('/', ':');
    }

    private boolean scopeAllows(String scopes, String endpointScope) {
        String required = scopeOf(endpointScope);
        for (String s : scopes.split("[,\\s]+")) {
            if (s.equals(required) || s.equals("intake:*") || s.equals("*")) {
                return true;
            }
        }
        return false;
    }

    public record CredentialRecord(String credentialId, String sourceCode, String keyId, String secret,
                                   String scopes, int keyVersion, boolean active) {
    }

    public List<CredentialRecord> credentials(String tenantId) {
        return jdbc.query("""
                select credential_id::text, source_code, key_id, secret, scopes, key_version, active
                from lab.source_credential where tenant_id = ?::uuid order by source_code, key_version
                """, (rs, i) -> new CredentialRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getBoolean(7)), tenantId);
    }

    public void rotate(String tenantId, String credentialId, String reason) {
        int updated = jdbc.update("""
                update lab.source_credential
                set active = false, rotated_at = now(), key_version = key_version + 1
                where tenant_id = ?::uuid and credential_id = ?::uuid and active = true
                """, tenantId, credentialId);
        if (updated == 0) {
            throw com.labroute.web.ApiException.notFound("CREDENTIAL_NOT_FOUND", "no active credential with that id");
        }
    }

    public CredentialRecord addCredential(String tenantId, String sourceCode, String secret, String scopes) {
        String keyId = "key_" + sourceCode + "_" + UUID.randomUUID().toString().substring(0, 8);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into lab.source_credential(credential_id, tenant_id, source_code, key_id, secret, scopes)
                values (?::uuid, ?::uuid, ?, ?, ?, ?)
                """, id, tenantId, sourceCode, keyId, secret, scopes);
        return new CredentialRecord(id, sourceCode, keyId, secret, scopes, 1, true);
    }

    public java.util.Optional<JsonNode> credentialAsJson(String keyId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select secret, scopes from lab.source_credential where key_id = ?", keyId);
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(crypto.mapper().valueToTree(rows.get(0)));
    }
}
