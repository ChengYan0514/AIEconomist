package com.paperflow.account.model;

import java.util.EnumSet;
import java.util.Set;

import static com.paperflow.account.model.Permission.*;

public enum Role {
    SYSTEM_ADMIN(EnumSet.allOf(Permission.class)),
    DATA_MAINTAINER(EnumSet.of(PAPER_READ, CONTENT_READ, PAPER_WRITE, JOB_READ, PDF_PARSE, FULL_TEXT_IMPORT, OPENALEX_MANAGE)),
    READ_ONLY(EnumSet.of(PAPER_READ, CONTENT_READ));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
