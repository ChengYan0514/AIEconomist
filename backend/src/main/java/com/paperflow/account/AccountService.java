package com.paperflow.account;

import com.paperflow.account.model.Role;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;

public final class AccountService {
    private final Accounts accounts;
    private final PasswordEncoder passwords;

    public AccountService(Accounts accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public void bootstrap(String rawUsername, String password) {
        String username = Accounts.normalizeUsername(rawUsername);
        if (username.isEmpty() && password.isEmpty()) return;
        validateUsername(username);
        if (accounts.credentials(username) == null) {
            validatePassword(password);
            accounts.create(username, username, passwords.encode(password), Role.SYSTEM_ADMIN.name(), false);
        }
    }

    public void changePassword(String username, long credentialVersion, String currentPassword, String newPassword) {
        if (currentPassword == null) throw new IllegalArgumentException("请输入当前密码");
        validatePassword(newPassword);
        var credentials = accounts.credentials(username);
        if (credentials == null || !credentials.enabled() || credentials.credentialVersion() != credentialVersion) {
            throw new IllegalArgumentException("账号已发生变更，请重新登录");
        }
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(currentPassword, credentials.passwordHash())) {
            throw new IllegalArgumentException("当前密码有误");
        }
        if (credentials.mustChangePassword() && currentPassword.equals(newPassword)) {
            throw new IllegalArgumentException("请设置不同于临时密码的个人密码");
        }
        if (accounts.changePassword(username, credentialVersion, passwords.encode(newPassword)) != 1) {
            throw new IllegalArgumentException("账号已发生变更，请重新登录");
        }
    }

    static void validateUsername(String username) {
        if (!username.matches("[a-z0-9_.-]{3,50}")) {
            throw new IllegalArgumentException("用户名需为 3–50 位小写字母、数字或 ._-");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码至少 12 个字符且 UTF-8 编码长度最多 72 字节");
        }
    }
}
