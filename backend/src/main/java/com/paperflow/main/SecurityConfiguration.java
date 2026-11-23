package com.paperflow.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.account.AccountService;
import com.paperflow.account.Accounts;
import com.paperflow.account.UserManagement;
import com.paperflow.adapters.web.ApiErrors;
import com.paperflow.adapters.web.SessionPrincipal;
import com.paperflow.adapters.web.WorkspaceAccess;
import com.paperflow.audit.AuditLog;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;

import static com.paperflow.account.model.Permission.*;
import static org.springframework.http.HttpMethod.*;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService accountDirectory(Accounts accounts) {
        return input -> {
            String username = Accounts.normalizeUsername(input);
            var credentials = accounts.credentials(username);
            if (credentials == null) throw new UsernameNotFoundException("Unknown user");
            return new SessionPrincipal(username, credentials);
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    HttpSessionSecurityContextRepository sessionContext() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http, ObjectMapper json, HttpSessionSecurityContextRepository context, WorkspaceAccess access
    ) throws Exception {
        return http
                .authorizeHttpRequests(rules -> configureRoutes(rules, access))
                .securityContext(config -> config.securityContextRepository(context))
                .csrf(config -> config.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, cause) ->
                                writeError(response, json, 401, "AUTH_REQUIRED", "请先登录管理端"))
                        .accessDeniedHandler((request, response, cause) -> {
                            if (cause instanceof WorkspaceAccess.Denied denial) {
                                writeError(response, json, 403, denial.code(), denial.getMessage());
                            } else {
                                writeError(response, json, 403, "ACCESS_DENIED", "请求校验失败，请刷新后重试");
                            }
                        }))
                .build();
    }

    private void configureRoutes(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<HttpSecurity>
            .AuthorizationManagerRequestMatcherRegistry rules, WorkspaceAccess access) {
        String[] auditPaths = {"/api/audit-events", "/api/audit-events/**"};
        String[] contentPaths = {"/api/papers/{paperId}/file", "/api/papers/{paperId}/blocks", "/api/papers/{paperId}/blocks/{sequence}/asset"};
        String[] paperPaths = {"/api/papers", "/api/papers/{paperId}", "/api/sources"};
        var csvAccess = access.requireAction(PAPER_CSV_EXPORT, "PAPER_CSV_EXPORT", "PAPER_COLLECTION", "papers");
        rules.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                .requestMatchers(GET, "/api/csrf").permitAll()
                .requestMatchers(POST, "/api/session").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(GET, "/api/session").access(access.session())
                .requestMatchers(HEAD, "/api/session").access(access.session())
                .requestMatchers(DELETE, "/api/session").access(access.session())
                .requestMatchers(POST, "/api/session/password").access(access.session())
                .requestMatchers("/api/system-status").access(access.require(SYSTEM_MANAGE))
                .requestMatchers(GET, auditPaths).access(access.require(SYSTEM_MANAGE))
                .requestMatchers(HEAD, auditPaths).access(access.require(SYSTEM_MANAGE))
                .requestMatchers(auditPaths).denyAll()
                .requestMatchers(POST, "/api/users").access(access.requireAction(SYSTEM_MANAGE, "USER_CREATE", "USER_COLLECTION", "users"))
                .requestMatchers(PATCH, "/api/users/{username}").access(access.requireAction(SYSTEM_MANAGE, "USER_UPDATE", "USER", null))
                .requestMatchers("/api/users", "/api/users/**").access(access.require(SYSTEM_MANAGE))
                .requestMatchers(GET, "/api/papers/export").access(csvAccess)
                .requestMatchers(HEAD, "/api/papers/export").access(csvAccess)
                .requestMatchers(GET, contentPaths).access(access.require(CONTENT_READ, PAPER_READ))
                .requestMatchers(HEAD, contentPaths).access(access.require(CONTENT_READ, PAPER_READ))
                .requestMatchers(GET, paperPaths).access(access.require(PAPER_READ))
                .requestMatchers(HEAD, paperPaths).access(access.require(PAPER_READ))
                .requestMatchers(POST, "/api/papers/{paperId}/match").access(access.require(OPENALEX_MANAGE, PAPER_READ, JOB_READ))
                .requestMatchers(POST, "/api/imports", "/api/imports/{jobId}/confirm").access(access.require(PAPER_WRITE, JOB_READ))
                .requestMatchers("/api/jobs", "/api/jobs/**").access(access.require(JOB_READ))
                .requestMatchers("/api/papers", "/api/papers/**").access(access.require(PAPER_WRITE))
                .anyRequest().access(access.require(PAPER_READ));
    }

    @Bean
    AccountService accountService(Accounts accounts, PasswordEncoder passwords) {
        return new AccountService(accounts, passwords);
    }

    @Bean
    UserManagement userManagement(Accounts accounts, PasswordEncoder passwords, AuditLog audit, TransactionTemplate transactions) {
        return new UserManagement(accounts, passwords, audit, transactions);
    }

    @Bean
    WorkspaceAccess workspaceAccess(Accounts accounts, AuditLog audit) {
        return new WorkspaceAccess(accounts, audit);
    }

    @Bean
    ApplicationRunner bootstrapAdmin(AccountService service, WorkspaceProperties config) {
        return args -> service.bootstrap(config.adminUsername(), config.adminPassword());
    }

    private void writeError(HttpServletResponse response, ObjectMapper json, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), new ApiErrors.Error(code, message));
    }
}
