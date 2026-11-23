package com.labroute.model;

import java.time.Instant;

public record Tenant(
        String tenantId,
        String code,
        String name,
        String status,
        String strategy,
        String isolationLevel,
        int policyVersion,
        String readSpace,
        String writeSpace,
        String phase,
        long generation,
        String dedicatedRole,
        Instant isolationChangedAt,
        Instant createdAt,
        Instant updatedAt,
        String oldSchema,
        String newSchema
) {
    public boolean writeBlocked() {
        return "BLOCKED".equals(writeSpace) || "BLOCKED".equals(phase);
    }
}
