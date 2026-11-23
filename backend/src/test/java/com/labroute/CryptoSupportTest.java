package com.labroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labroute.service.CryptoSupport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoSupportTest {

    private final CryptoSupport crypto = new CryptoSupport(new ObjectMapper());

    @Test
    void canonicalJsonSortsKeysRecursively() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 2);
        a.put("a", Map.of("z", 1, "y", 2));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Map.of("y", 2, "z", 1));
        b.put("b", 2);
        assertThat(crypto.canonicalJson(a)).isEqualTo(crypto.canonicalJson(b));
        assertThat(crypto.canonicalJson(a)).isEqualTo("{\"a\":{\"y\":2,\"z\":1},\"b\":2}");
    }

    @Test
    void canonicalJsonNeverUsesScientificNotationForWholeNumbers() {
        // Cross-language signing (e.g. Python json.dumps) renders 100 as "100", never "1E+2".
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("patientAgeDays", 100);
        a.put("b", 3650);
        assertThat(crypto.canonicalJson(a)).isEqualTo("{\"b\":3650,\"patientAgeDays\":100}");

        Map<String, Object> decimal = new LinkedHashMap<>();
        decimal.put("low", new java.math.BigDecimal("3.90"));
        decimal.put("high", new java.math.BigDecimal("6.10"));
        assertThat(crypto.canonicalJson(decimal)).isEqualTo("{\"high\":6.1,\"low\":3.9}");

        Map<String, Object> big = new LinkedHashMap<>();
        big.put("n", new java.math.BigDecimal("123456789012345"));
        assertThat(crypto.canonicalJson(big)).isEqualTo("{\"n\":123456789012345}");
    }

    @Test
    void hmacIsStableAndKeySensitive() {
        String sig1 = crypto.hmacSha256Hex("secret", "message");
        String sig2 = crypto.hmacSha256Hex("secret", "message");
        String sig3 = crypto.hmacSha256Hex("other", "message");
        assertThat(sig1).isEqualTo(sig2).hasSize(64);
        assertThat(sig1).isNotEqualTo(sig3);
    }

    @Test
    void signingStringBindsEveryComponent() {
        String base = crypto.signingString("POST", "/api/intake/sample", "t1", "src", "k1",
                "nonce12345", "2026-01-01T00:00:00Z", "{}");
        String differentTenant = crypto.signingString("POST", "/api/intake/sample", "t2", "src", "k1",
                "nonce12345", "2026-01-01T00:00:00Z", "{}");
        assertThat(base).isNotEqualTo(differentTenant);
        assertThat(base.split("\n")).hasSize(8);
    }

    @Test
    void constantTimeEqualsRejectsNulls() {
        assertThat(CryptoSupport.constantTimeEquals(null, "x")).isFalse();
        assertThat(CryptoSupport.constantTimeEquals("x", null)).isFalse();
        assertThat(CryptoSupport.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(CryptoSupport.constantTimeEquals("abc", "abd")).isFalse();
    }
}
