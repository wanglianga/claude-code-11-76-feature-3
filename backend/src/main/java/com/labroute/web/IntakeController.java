package com.labroute.web;

import com.labroute.service.IntakeService;
import com.labroute.model.Tenant;
import com.labroute.service.CryptoSupport;
import com.labroute.service.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sample receipt gateway. Open during the entire migration: the only things that can
 * make it return non-2xx are authentication/authorisation failures, a genuinely
 * unroutable request, or a semantic rejection that has still been persisted.
 */
@RestController
@RequestMapping("/api/tenant/{tenantCode}/intake")
public class IntakeController {

    private final TenantService tenants;
    private final IntakeService intake;

    public IntakeController(TenantService tenants, IntakeService intake) {
        this.tenants = tenants;
        this.intake = intake;
    }

    @PostMapping("/sample")
    public ResponseEntity<Map<String, Object>> sample(
            @org.springframework.web.bind.annotation.PathVariable String tenantCode,
            @RequestHeader(value = "X-Source-Code", required = false) String sourceCode,
            @RequestHeader(value = "X-Key-Id", required = false) String keyId,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signed-At", required = false) String signedAt,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) String body) {
        return handle(tenantCode, "/api/intake/sample", sourceCode, keyId, nonce, signedAt,
                signature, idempotencyKey, body);
    }

    @PostMapping("/result")
    public ResponseEntity<Map<String, Object>> result(
            @org.springframework.web.bind.annotation.PathVariable String tenantCode,
            @RequestHeader(value = "X-Source-Code", required = false) String sourceCode,
            @RequestHeader(value = "X-Key-Id", required = false) String keyId,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signed-At", required = false) String signedAt,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) String body) {
        return handle(tenantCode, "/api/intake/result", sourceCode, keyId, nonce, signedAt,
                signature, idempotencyKey, body);
    }

    @PostMapping("/report")
    public ResponseEntity<Map<String, Object>> report(
            @org.springframework.web.bind.annotation.PathVariable String tenantCode,
            @RequestHeader(value = "X-Source-Code", required = false) String sourceCode,
            @RequestHeader(value = "X-Key-Id", required = false) String keyId,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signed-At", required = false) String signedAt,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) String body) {
        return handle(tenantCode, "/api/intake/report", sourceCode, keyId, nonce, signedAt,
                signature, idempotencyKey, body);
    }

    @PostMapping("/object")
    public ResponseEntity<Map<String, Object>> object(
            @org.springframework.web.bind.annotation.PathVariable String tenantCode,
            @RequestHeader(value = "X-Source-Code", required = false) String sourceCode,
            @RequestHeader(value = "X-Key-Id", required = false) String keyId,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signed-At", required = false) String signedAt,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) String body) {
        return handle(tenantCode, "/api/intake/object", sourceCode, keyId, nonce, signedAt,
                signature, idempotencyKey, body);
    }

    private ResponseEntity<Map<String, Object>> handle(String tenantCode, String endpoint, String sourceCode,
                                                       String keyId, String nonce, String signedAt,
                                                       String signature, String idempotencyKey, String body) {
        try {
            Map<String, Object> receipt = intake.intake(new IntakeService.IntakeRequest(
                    tenantCode, endpoint, null, sourceCode, keyId, nonce, signedAt, signature,
                    signature, idempotencyKey, body));
            return ResponseEntity.ok(receipt);
        } catch (IntakeService.PersistedRejection rej) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(rej.receipt());
        }
    }
}
