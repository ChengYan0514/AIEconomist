package com.paperflow.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.account.AccountService;
import com.paperflow.account.AdminAccounts;
import com.paperflow.adapters.web.ApiErrors;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService adminDirectory(AdminAccounts accounts) {
        return input -> {
            String username = AdminAccounts.normalizeUsername(input);
            String hash = accounts.passwordHash(username);
            if (hash == null) throw new UsernameNotFoundException("Unknown administrator");
            return User.withUsername(username).password(hash).roles("ADMIN").build();
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
            HttpSecurity http, ObjectMapper json, HttpSessionSecurityContextRepository context
    ) throws Exception {
        return http
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/session").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
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
                        .accessDeniedHandler((request, response, cause) ->
                                writeError(response, json, 403, "ACCESS_DENIED", "请求校验失败，请刷新后重试")))
                .build();
    }

    @Bean
    AccountService accountService(AdminAccounts accounts, PasswordEncoder passwords) {
        return new AccountService(accounts, passwords);
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
