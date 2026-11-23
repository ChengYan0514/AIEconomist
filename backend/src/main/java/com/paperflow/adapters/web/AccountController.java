package com.paperflow.adapters.web;

import com.paperflow.account.AccountService;
import com.paperflow.account.Accounts;
import com.paperflow.account.model.AccountAccess;
import com.paperflow.account.model.Permission;
import com.paperflow.account.model.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

import static com.paperflow.adapters.web.WorkspaceAccess.CURRENT_ACCOUNT;

@RestController
@RequestMapping("/api")
public class AccountController {
    private final AuthenticationManager authentication;
    private final HttpSessionSecurityContextRepository sessions;
    private final AccountService accounts;
    private final WorkspaceAccess access;

    public AccountController(
            AuthenticationManager authentication, HttpSessionSecurityContextRepository sessions, AccountService accounts,
            WorkspaceAccess access
    ) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.accounts = accounts;
        this.access = access;
    }

    record Login(String username, String password) {}
    record Session(String username, String displayName, Role role, boolean mustChangePassword, Set<Permission> permissions) {
        static Session from(AccountAccess account) {
            return new Session(account.username(), account.displayName(), account.role(),
                    account.mustChangePassword(), account.permissions());
        }
    }
    record Token(String headerName, String token) {}
    record PasswordChange(String currentPassword, String newPassword) {}

    @GetMapping("/csrf")
    Token csrf(CsrfToken csrf) {
        return new Token(csrf.getHeaderName(), csrf.getToken());
    }

    @PostMapping("/session")
    Session login(@RequestBody Login login, HttpServletRequest request, HttpServletResponse response) {
        var user = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                Accounts.normalizeUsername(login.username()), login.password()));
        var account = access.current(user);
        request.getSession();
        request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(user);
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context, request, response);
        return Session.from(account);
    }

    @GetMapping("/session")
    Session session(@RequestAttribute(CURRENT_ACCOUNT) AccountAccess account) {
        return Session.from(account);
    }

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @PostMapping("/session/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@RequestBody PasswordChange change, @RequestAttribute(CURRENT_ACCOUNT) AccountAccess account,
                        HttpServletRequest request) {
        accounts.changePassword(account.username(), account.credentialVersion(), change.currentPassword(), change.newPassword());
        logout(request);
    }
}
