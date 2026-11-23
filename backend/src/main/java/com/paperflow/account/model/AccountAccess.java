package com.paperflow.account.model;

import java.util.Set;

public record AccountAccess(String username, String displayName, String role, boolean enabled,
                            boolean mustChangePassword, long credentialVersion, Set<Permission> granted) {
    public Set<Permission> permissions() {
        return mustChangePassword ? Set.of() : granted;
    }
}
