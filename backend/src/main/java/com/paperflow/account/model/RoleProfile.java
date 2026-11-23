package com.paperflow.account.model;

import java.util.List;
import java.util.Set;

public record RoleProfile(String name, String description, Set<Permission> permissions) {
    public RoleProfile {
        permissions = Set.copyOf(permissions);
    }

    public static RoleProfile from(String name, String description, List<String> permissions) {
        String title = name == null ? "" : name.strip();
        if (title.isEmpty() || title.length() > 100 || title.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("角色名称需为 1–100 个字符");
        }
        if (description == null || description.length() > 500 || description.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("角色说明最多 500 个字符");
        }
        return new RoleProfile(title, description.strip(), Permission.businessPermissions(permissions));
    }
}
