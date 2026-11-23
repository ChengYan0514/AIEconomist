package com.paperflow.adapters.web;

import com.paperflow.account.Accounts;
import org.springframework.security.core.userdetails.User;

import java.util.List;

public final class SessionPrincipal extends User {
    private final long credentialVersion;

    public SessionPrincipal(String username, Accounts.Credentials credentials) {
        super(username, credentials.passwordHash(), credentials.enabled(), true, true, true, List.of());
        this.credentialVersion = credentials.credentialVersion();
    }

    public long credentialVersion() {
        return credentialVersion;
    }
}
