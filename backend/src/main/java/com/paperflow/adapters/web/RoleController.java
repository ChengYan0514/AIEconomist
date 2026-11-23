package com.paperflow.adapters.web;

import com.paperflow.account.RoleManagement;
import com.paperflow.account.Roles;
import com.paperflow.account.model.Permission;
import com.paperflow.account.model.PlatformRole;
import com.paperflow.account.model.PlatformUser.Status;
import com.paperflow.account.model.RoleProfile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/roles")
public class RoleController {
    private final RoleManagement roles;

    public RoleController(RoleManagement roles) {
        this.roles = roles;
    }

    record RoleSummary(String id, String name, String description, boolean builtIn, long userCount) {
        static RoleSummary from(PlatformRole role) {
            return new RoleSummary(role.id(), role.profile().name(), role.profile().description(), role.builtIn(), role.userCount());
        }
    }

    record RoleMember(String username, String displayName, Status status) {
        static RoleMember from(Roles.Member member) {
            return new RoleMember(member.username(), member.displayName(), member.status());
        }
    }

    record RoleDetail(String id, String name, String description, boolean builtIn, long userCount,
                      Set<Permission> permissions, List<RoleMember> members) {
        static RoleDetail from(RoleManagement.Detail detail) {
            var role = detail.role();
            var members = detail.members().stream().map(RoleMember::from).toList();
            return new RoleDetail(role.id(), role.profile().name(), role.profile().description(),
                    role.builtIn(), members.size(), role.profile().permissions(), members);
        }
    }

    record PermissionOption(Permission code, String label, List<Permission> requires) {}

    record PermissionGroup(String label, List<PermissionOption> permissions) {
        static PermissionGroup from(Permission.Group group) {
            return new PermissionGroup(group.label(), group.permissions().stream()
                    .map(permission -> new PermissionOption(permission, permission.label(), permission.requires())).toList());
        }
    }

    record RoleCatalog(List<RoleSummary> items, List<PermissionGroup> permissionGroups) {}

    record SaveRole(String name, String description, List<String> permissions, Long expectedUserCount) {
        RoleProfile profile() { return RoleProfile.from(name, description, permissions); }
    }

    @GetMapping
    RoleCatalog list() {
        return new RoleCatalog(roles.list().stream().map(RoleSummary::from).toList(),
                Arrays.stream(Permission.Group.values()).map(PermissionGroup::from).toList());
    }

    @GetMapping("/{roleId}")
    RoleDetail get(@PathVariable String roleId) {
        return RoleDetail.from(roles.detail(roleId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RoleDetail create(@RequestBody SaveRole request, Principal actor) {
        return RoleDetail.from(roles.create(actor.getName(), request.profile()));
    }

    @PutMapping("/{roleId}")
    RoleDetail update(@PathVariable String roleId, @RequestBody SaveRole request, Principal actor) {
        return RoleDetail.from(roles.update(actor.getName(), roleId, request.profile(), request.expectedUserCount()));
    }

    @DeleteMapping("/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String roleId, Principal actor) {
        roles.delete(actor.getName(), roleId);
    }
}
