package com.labroute.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OperatorAuth {

    private final String operatorToken;

    public OperatorAuth(@Value("${labroute.operator-token}") String operatorToken) {
        this.operatorToken = operatorToken;
    }

    /** Constant-time compare so the control plane cannot be timing-probed. */
    public boolean authorised(HttpServletRequest request) {
        String provided = request.getHeader("X-Operator-Token");
        if (provided == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                provided = auth.substring(7);
            }
        }
        if (provided == null) {
            return false;
        }
        byte[] a = provided.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = operatorToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
