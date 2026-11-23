package com.labroute.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "labroute")
public record LabRouteProperties(
        String operatorToken,
        String masterKey,
        boolean demoMode,
        Auth auth,
        boolean seedDemoTenants
) {
    public record Auth(long standardSkewSeconds, long strictSkewSeconds) {
    }
}
