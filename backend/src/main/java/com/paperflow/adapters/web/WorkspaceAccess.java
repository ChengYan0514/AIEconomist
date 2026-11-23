package com.paperflow.adapters.web;

import com.paperflow.account.Accounts;
import com.paperflow.account.model.AccountAccess;
import com.paperflow.account.model.Permission;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.job.JobKind;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Map;

import static com.paperflow.account.model.Permission.*;

public final class WorkspaceAccess {
    public static final String CURRENT_ACCOUNT = "currentAccount";
    private static final AuthorizationDecision ALLOW = new AuthorizationDecision(true);
    private final Accounts accounts;
    private final AuditLog audit;

    public WorkspaceAccess(Accounts accounts, AuditLog audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    public AccountAccess current(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw invalidSession();
        }
        var access = accounts.access(principal.getUsername());
        if (access == null || !access.enabled() || access.credentialVersion() != principal.credentialVersion()) {
            throw invalidSession();
        }
        return access;
    }

    public AuthorizationManager<RequestAuthorizationContext> session() {
        return (authentication, context) -> {
            load(authentication.get(), context.getRequest());
            return ALLOW;
        };
    }

    public AuthorizationManager<RequestAuthorizationContext> require(Permission... permissions) {
        return (authentication, context) -> {
            var access = load(authentication.get(), context.getRequest());
            for (Permission permission : permissions) require(access, permission);
            return ALLOW;
        };
    }

    public AuthorizationManager<RequestAuthorizationContext> requireAction(Permission permission, String action,
                                                                            String objectType, String objectId) {
        return (authentication, context) -> {
            var access = load(authentication.get(), context.getRequest());
            if (access.permissions().contains(permission)) return ALLOW;
            if (objectId != null) throw denied(access, permission, action, objectType, objectId);
            String username = context.getVariables().get("username");
            if (username != null && username.matches("[a-z0-9_.-]{3,50}")) {
                throw denied(access, permission, action, objectType, username);
            }
            throw denied(access, permission, action, "USER_COLLECTION", "users");
        };
    }

    public void requireJob(AccountAccess access, JobKind kind, String action, String objectId) {
        if (kind == null) throw new IllegalArgumentException("缺少任务类型");
        Permission permission = switch (kind) {
            case ORIGINAL_IMPORT -> PAPER_WRITE;
            case PARSE -> PDF_PARSE;
            case PUBLISH -> FULL_TEXT_IMPORT;
            case MATCH, METADATA -> OPENALEX_MANAGE;
        };
        if (!access.permissions().contains(permission)) throw denied(access, permission, action, "JOB", objectId);
    }

    private AccountAccess load(Authentication authentication, HttpServletRequest request) {
        var access = current(authentication);
        request.setAttribute(CURRENT_ACCOUNT, access);
        return access;
    }

    private void require(AccountAccess access, Permission permission) {
        if (!access.permissions().contains(permission)) {
            throw denied(access, permission, "API_ACCESS", "PERMISSION", permission.name());
        }
    }

    private Denied denied(AccountAccess access, Permission permission, String action, String objectType, String objectId) {
        var denial = new Denied(access.mustChangePassword());
        audit.record(access.username(), action, objectType, objectId, AuditOutcome.DENIED,
                Map.of("code", denial.code(), "permission", permission.name()));
        return denial;
    }

    private InsufficientAuthenticationException invalidSession() {
        return new InsufficientAuthenticationException("请先登录管理端");
    }

    public static final class Denied extends AccessDeniedException {
        private final boolean passwordRequired;

        public Denied(boolean passwordRequired) {
            super(passwordRequired ? "请先设置个人密码" : "当前账号无权执行此操作");
            this.passwordRequired = passwordRequired;
        }

        public String code() {
            return passwordRequired ? "PASSWORD_CHANGE_REQUIRED" : "ACCESS_DENIED";
        }
    }
}
