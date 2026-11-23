package com.paperflow.account;

import com.paperflow.account.model.PlatformUser;
import com.paperflow.account.model.PlatformUser.Status;
import com.paperflow.account.model.Role;
import com.paperflow.account.model.UserManagementException;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.paperflow.account.model.UserManagementException.Reason.*;

public final class UserManagement {
    public record UserPage(List<PlatformUser> items, long total) {}
    public record IssuedPassword(PlatformUser user, String temporaryPassword) {}

    private final Accounts accounts;
    private final PasswordEncoder passwords;
    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public UserManagement(Accounts accounts, PasswordEncoder passwords, AuditLog audit, TransactionTemplate transactions) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.audit = audit;
        this.transactions = transactions;
    }

    public UserPage list(String query, Role role, PlatformUser.Status status, long offset, int size) {
        String search = query == null ? "" : query.strip();
        if (search.length() > 100) throw new IllegalArgumentException("搜索词最多 100 个字符");
        Boolean enabled = status == null ? null : status == PlatformUser.Status.ACTIVE;
        return new UserPage(accounts.list(search, role, enabled, offset, size), accounts.count(search, role, enabled));
    }

    public IssuedPassword create(String actor, String rawUsername, String rawName, Role role) {
        String username = Accounts.normalizeUsername(rawUsername);
        AccountService.validateUsername(username);
        String name = validateProfile(rawName, role);
        String temporaryPassword = temporaryPassword();
        String hash = passwords.encode(temporaryPassword);
        return transactions.execute(status -> {
            if (accounts.create(username, name, hash, role, true) != 1) {
                throw new UserManagementException(USERNAME_TAKEN);
            }
            record(actor, "USER_CREATE", username, Map.of("displayName", name, "role", role));
            return new IssuedPassword(accounts.find(username), temporaryPassword);
        });
    }

    public PlatformUser update(String actor, String username, String rawName, Role role) {
        String name = validateProfile(rawName, role);
        return transactions.execute(status -> {
            var current = lockedUser(username);
            if (role != Role.SYSTEM_ADMIN) requireOtherAdministrator(current);
            accounts.updateProfile(username, name, role);
            if (!current.displayName().equals(name)) {
                record(actor, "USER_DISPLAY_NAME_CHANGE", username,
                        Map.of("previousDisplayName", current.displayName(), "displayName", name));
            }
            if (current.role() != role) {
                record(actor, "USER_ROLE_CHANGE", username, Map.of("previousRole", current.role(), "role", role));
            }
            return accounts.find(username);
        });
    }

    public PlatformUser setStatus(String actor, String username, Status status) {
        if (status == null) throw new IllegalArgumentException("请选择账号状态");
        boolean enabling = status == Status.ACTIVE;
        return transactions.execute(transaction -> {
            var current = lockedUser(username);
            if (current.enabled() == enabling) return current;
            if (!enabling) requireOtherAdministrator(current);
            accounts.setEnabled(username, enabling);
            record(actor, enabling ? "USER_ENABLE" : "USER_DISABLE", username,
                    Map.of("previousAccountStatus", enabling ? Status.DISABLED : Status.ACTIVE, "accountStatus", status));
            return accounts.find(username);
        });
    }

    public IssuedPassword resetPassword(String actor, String username) {
        String temporaryPassword = temporaryPassword();
        String hash = passwords.encode(temporaryPassword);
        return transactions.execute(status -> {
            var current = lockedUser(username);
            accounts.resetPassword(username, hash);
            record(actor, "USER_PASSWORD_RESET", username, Map.of("mustChangePassword", true));
            return new IssuedPassword(current, temporaryPassword);
        });
    }

    private PlatformUser lockedUser(String username) {
        accounts.lockManagement();
        var user = accounts.find(username);
        if (user == null) throw new UserManagementException(USER_NOT_FOUND);
        return user;
    }

    private void requireOtherAdministrator(PlatformUser user) {
        if (user.enabled() && user.role() == Role.SYSTEM_ADMIN && accounts.enabledAdministrators() <= 1) {
            throw new UserManagementException(LAST_SYSTEM_ADMIN);
        }
    }

    private String temporaryPassword() {
        byte[] secret = new byte[18];
        random.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private void record(String actor, String action, String username, Map<String, Object> summary) {
        audit.record(actor, action, "USER", username, AuditOutcome.SUCCEEDED, summary);
    }

    private String validateProfile(String rawName, Role role) {
        if (role == null) throw new IllegalArgumentException("请选择角色");
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty() || name.length() > 100) {
            throw new IllegalArgumentException("显示名称需为 1–100 个字符");
        }
        return name;
    }
}
