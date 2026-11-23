package com.paperflow.account.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.paperflow.account.model.Permission.*;

public enum Role {
    SYSTEM_ADMIN(EnumSet.allOf(Permission.class)),
    DATA_MAINTAINER(EnumSet.of(PAPER_READ, CONTENT_READ, PAPER_WRITE, JOB_READ, PDF_PARSE, FULL_TEXT_IMPORT, OPENALEX_MANAGE)),
    READ_ONLY(EnumSet.of(PAPER_READ, CONTENT_READ));

    private static final Map<String, Role> BUILT_IN = Map.of(
            SYSTEM_ADMIN.name(), SYSTEM_ADMIN, DATA_MAINTAINER.name(), DATA_MAINTAINER, READ_ONLY.name(), READ_ONLY);
    private static final Pattern CUSTOM_ID = Pattern.compile("CUSTOM_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public static Role builtIn(String id) {
        return BUILT_IN.get(id);
    }

    public static boolean validId(String id) {
        return id != null && (builtIn(id) != null || CUSTOM_ID.matcher(id).matches());
    }
}
