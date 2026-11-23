package com.paperflow.account.model;

import java.time.OffsetDateTime;

public record PlatformUser(String username, String displayName, String role, boolean enabled, OffsetDateTime createdAt) {
    public enum Status {
        ACTIVE, DISABLED
    }
}
