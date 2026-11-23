package com.paperflow.account;

import com.paperflow.account.model.Permission;
import com.paperflow.account.model.PlatformRole;
import com.paperflow.account.model.RoleManagementException;
import com.paperflow.account.model.RoleProfile;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.paperflow.account.model.RoleManagementException.Reason.*;

public final class RoleManagement {
    public record Detail(PlatformRole role, List<Roles.Member> members) {}

    private final Roles roles;
    private final Accounts accounts;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    public RoleManagement(Roles roles, Accounts accounts, AuditLog audit, TransactionTemplate transactions) {
        this.roles = roles;
        this.accounts = accounts;
        this.audit = audit;
        this.transactions = transactions;
    }

    public List<PlatformRole> list() {
        return roles.listRoles();
    }

    public Detail detail(String id) {
        return new Detail(requireRole(id), roles.members(id));
    }

    public Detail create(String actor, RoleProfile profile) {
        return transactions.execute(status -> {
            accounts.lockManagement();
            String id = "CUSTOM_" + UUID.randomUUID();
            requireAvailableName(id, profile);
            roles.createRole(id, profile);
            record(actor, "ROLE_CREATE", id, summary(profile, 0));
            return detail(id);
        });
    }

    public Detail update(String actor, String id, RoleProfile profile, Long expectedUserCount) {
        if (expectedUserCount == null || expectedUserCount < 0) {
            throw new IllegalArgumentException("缺少有效的已分配人数");
        }
        return transactions.execute(status -> {
            accounts.lockManagement();
            var current = requireCustomRole(id);
            if (current.userCount() != expectedUserCount) throw new RoleManagementException(ROLE_ASSIGNMENTS_CHANGED);
            if (!current.profile().equals(profile)) {
                requireAvailableName(id, profile);
                roles.updateRole(id, profile);
                record(actor, "ROLE_UPDATE", id, Map.of(
                        "previousRoleName", current.profile().name(), "roleName", profile.name(),
                        "previousPermissions", permissionCodes(current.profile()), "permissions", permissionCodes(profile),
                        "descriptionChanged", !current.profile().description().equals(profile.description()),
                        "assignedUsers", current.userCount()));
            }
            return detail(id);
        });
    }

    public void delete(String actor, String id) {
        transactions.executeWithoutResult(status -> {
            accounts.lockManagement();
            var current = requireCustomRole(id);
            if (current.userCount() != 0) throw new RoleManagementException(ROLE_IN_USE);
            roles.deleteRole(id);
            record(actor, "ROLE_DELETE", id, summary(current.profile(), 0));
        });
    }

    private PlatformRole requireRole(String id) {
        var role = roles.findRole(id);
        if (role == null) throw new RoleManagementException(ROLE_NOT_FOUND);
        return role;
    }

    private PlatformRole requireCustomRole(String id) {
        var role = requireRole(id);
        if (role.builtIn()) throw new RoleManagementException(BUILT_IN_ROLE);
        return role;
    }

    private void requireAvailableName(String id, RoleProfile profile) {
        if (roles.nameUsed(profile.name(), id)) throw new RoleManagementException(ROLE_NAME_TAKEN);
    }

    private Map<String, Object> summary(RoleProfile profile, long userCount) {
        return Map.of("roleName", profile.name(), "permissions", permissionCodes(profile), "assignedUsers", userCount);
    }

    private List<String> permissionCodes(RoleProfile profile) {
        return profile.permissions().stream().map(Permission::name).sorted().toList();
    }

    private void record(String actor, String action, String id, Map<String, Object> summary) {
        audit.record(actor, action, "ROLE", id, AuditOutcome.SUCCEEDED, summary);
    }
}
