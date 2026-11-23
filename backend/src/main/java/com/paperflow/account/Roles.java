package com.paperflow.account;

import com.paperflow.account.model.PlatformRole;
import com.paperflow.account.model.RoleProfile;
import com.paperflow.account.model.PlatformUser.Status;

import java.util.List;

public interface Roles {
    record Member(String username, String displayName, boolean enabled) {
        public Status status() { return enabled ? Status.ACTIVE : Status.DISABLED; }
    }

    List<PlatformRole> listRoles();
    PlatformRole findRole(String id);
    List<Member> members(String id);
    boolean roleExists(String id);
    boolean nameUsed(String name, String exceptRole);
    void createRole(String id, RoleProfile profile);
    void updateRole(String id, RoleProfile profile);
    void deleteRole(String id);
}
