package com.paperflow.account;

import com.paperflow.account.model.AccountAccess;
import com.paperflow.account.model.PlatformUser;
import com.paperflow.account.model.Role;

import java.util.List;
import java.util.Locale;

public interface Accounts {
    record Credentials(String passwordHash, boolean enabled, boolean mustChangePassword, long credentialVersion) {}

    Credentials credentials(String username);
    AccountAccess access(String username);
    PlatformUser find(String username);
    List<PlatformUser> list(String query, Role role, Boolean enabled, long offset, int size);
    long count(String query, Role role, Boolean enabled);
    int create(String username, String displayName, String passwordHash, Role role, boolean mustChangePassword);
    int changePassword(String username, long credentialVersion, String passwordHash);
    void resetPassword(String username, String passwordHash);
    boolean lockManagement();
    int enabledAdministrators();
    void updateProfile(String username, String displayName, Role role);
    void setEnabled(String username, boolean enabled);

    static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
