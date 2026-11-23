package com.paperflow.account.model;

public final class UserManagementException extends RuntimeException {
    public enum Reason {
        USER_NOT_FOUND("没有找到该用户"),
        USERNAME_TAKEN("该用户名已被使用"),
        LAST_SYSTEM_ADMIN("必须保留至少一个启用的系统管理员");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    public UserManagementException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
