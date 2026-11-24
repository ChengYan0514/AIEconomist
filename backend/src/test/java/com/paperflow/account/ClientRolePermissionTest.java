package com.paperflow.account;

import com.paperflow.account.model.Permission;
import com.paperflow.account.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.paperflow.account.model.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

class ClientRolePermissionTest {
    @Test
    void definesClientAccessPermissionAndResearcherBuiltInRole() {
        assertThat(Permission.Group.CLIENT.label()).isEqualTo("客户端");
        assertThat(Permission.Group.CLIENT.permissions()).containsExactly(CLIENT_ACCESS);
        assertThat(Permission.businessPermissions(List.of("CLIENT_ACCESS"))).containsExactly(CLIENT_ACCESS);

        assertThat(Role.builtIn("RESEARCHER")).isEqualTo(Role.RESEARCHER);
        assertThat(Role.validId("RESEARCHER")).isTrue();
        assertThat(Role.RESEARCHER.permissions()).containsExactly(CLIENT_ACCESS);
    }

    @Test
    void grantsClientAccessToAllExistingAdminBuiltInRoles() {
        assertThat(Role.SYSTEM_ADMIN.permissions()).contains(CLIENT_ACCESS, SYSTEM_MANAGE);
        assertThat(Role.DATA_MAINTAINER.permissions()).contains(CLIENT_ACCESS, PAPER_WRITE);
        assertThat(Role.READ_ONLY.permissions()).containsExactlyInAnyOrder(
                PAPER_READ, CONTENT_READ, CAUSAL_READ, CLIENT_ACCESS);
    }
}
