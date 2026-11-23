package com.paperflow.account;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;

public final class AccountService {
    private final AdminAccounts accounts;
    private final PasswordEncoder passwords;

    public AccountService(AdminAccounts accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public void bootstrap(String rawUsername, String password) {
        String username = AdminAccounts.normalizeUsername(rawUsername);
        if (username.isEmpty() && password.isEmpty()) return;
        if (!username.matches("[a-z0-9_.-]{3,50}")) {
            throw new IllegalArgumentException("管理员用户名需为 3–50 位字母、数字或 ._-");
        }
        if (accounts.passwordHash(username) == null) {
            validatePassword(password);
            accounts.create(username, passwords.encode(password));
        }
    }

    public void changePassword(String username, String currentPassword, String newPassword) {
        if (currentPassword == null) throw new IllegalArgumentException("请输入当前密码");
        validatePassword(newPassword);
        String oldHash = accounts.passwordHash(username);
        if (oldHash == null || !passwords.matches(currentPassword, oldHash)) {
            throw new IllegalArgumentException("当前密码有误");
        }
        if (accounts.change(username, oldHash, passwords.encode(newPassword)) != 1) {
            throw new IllegalArgumentException("账号已发生变更，请重新登录");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码至少 12 个字符且 UTF-8 编码长度最多 72 字节");
        }
    }
}
