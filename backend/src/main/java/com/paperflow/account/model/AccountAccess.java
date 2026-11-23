package com.paperflow.account.model;

import java.util.Set;

public record AccountAccess(String username, String displayName, Role role, boolean enabled,
                            boolean mustChangePassword, long credentialVersion) {
    public Set<Permission> permissions() {
        return mustChangePassword ? Set.of() : role.permissions();
    }
}
