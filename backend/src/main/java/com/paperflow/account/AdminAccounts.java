package com.paperflow.account;

import java.util.Locale;

public interface AdminAccounts {
    String passwordHash(String username);
    int create(String username, String passwordHash);
    int change(String username, String oldHash, String passwordHash);

    static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
