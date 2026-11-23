package com.paperflow.account.model;

public record PlatformRole(String id, RoleProfile profile, long userCount) {
    public boolean builtIn() {
        return Role.builtIn(id) != null;
    }
}
