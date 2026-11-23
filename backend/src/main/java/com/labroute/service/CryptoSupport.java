package com.labroute.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class CryptoSupport {
    private final ObjectMapper mapper;

    public CryptoSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /** Deterministic JSON with recursively sorted object keys, used for hashing and signatures. */
    public String canonicalJson(Object value) {
        try {
            JsonNode node = value instanceof JsonNode n ? n : mapper.valueToTree(value);
            return mapper.writeValueAsString(sortNode(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot canonicalise json", e);
        }
    }

    private JsonNode sortNode(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), sortNode(e.getValue()));
            }
            ObjectNode out = mapper.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            node.forEach(child -> out.add(sortNode(child)));
            return out;
        }
        if (node.isNumber()) {
            // Rebuild from the plain string: stripTrailingZeros() can produce a negative scale
            // whose BigDecimal.toString() is scientific notation (100 -> "1E+2"), which would
            // diverge from the canonical JSON other platforms sign over ("100").
            java.math.BigDecimal stripped = node.decimalValue().stripTrailingZeros();
            java.math.BigDecimal plain = new java.math.BigDecimal(stripped.toPlainString());
            return mapper.getNodeFactory().numberNode(plain);
        }
        return node;
    }

    public String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String sha256Canonical(Object value) {
        return sha256Hex(canonicalJson(value));
    }

    public String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    /** Canonical string signed by sample source systems. */
    public String signingString(String method, String path, String tenant, String sourceCode,
                                String keyId, String nonce, String signedAt, String bodyCanonical) {
        return String.join("\n", method.toUpperCase(), path, tenant, sourceCode, keyId,
                nonce, signedAt, sha256Hex(bodyCanonical));
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid json payload", e);
        }
    }

    public List<String> sortedKeys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        keys.sort(String::compareTo);
        return keys;
    }
}
