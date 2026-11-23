package com.paperflow.adapters.web;

import com.paperflow.account.AccountService;
import com.paperflow.account.AdminAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api")
public class AccountController {
    private final AuthenticationManager authentication;
    private final HttpSessionSecurityContextRepository sessions;
    private final AccountService accounts;

    public AccountController(
            AuthenticationManager authentication, HttpSessionSecurityContextRepository sessions, AccountService accounts
    ) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.accounts = accounts;
    }

    record Login(String username, String password) {}
    record Session(String username) {}
    record Token(String headerName, String token) {}
    record PasswordChange(String currentPassword, String newPassword) {}

    @GetMapping("/csrf")
    Token csrf(CsrfToken csrf) {
        return new Token(csrf.getHeaderName(), csrf.getToken());
    }

    @PostMapping("/session")
    Session login(@RequestBody Login login, HttpServletRequest request, HttpServletResponse response) {
        var admin = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                AdminAccounts.normalizeUsername(login.username()), login.password()));
        request.getSession();
        request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(admin);
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context, request, response);
        return new Session(admin.getName());
    }

    @GetMapping("/session")
    Session session(Principal admin) {
        return new Session(admin.getName());
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
    void changePassword(@RequestBody PasswordChange change, Principal admin, HttpServletRequest request) {
        accounts.changePassword(admin.getName(), change.currentPassword(), change.newPassword());
        logout(request);
    }
}
