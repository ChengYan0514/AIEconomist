package com.paperflow.account.model;

public final class RoleManagementException extends RuntimeException {
    public enum Reason {
        ROLE_NOT_FOUND("没有找到该角色"),
        BUILT_IN_ROLE("内置角色的权限固定"),
        ROLE_NAME_TAKEN("该角色名称已被使用"),
        ROLE_IN_USE("请先为所有成员重新分配角色，包括停用的用户"),
        ROLE_ASSIGNMENTS_CHANGED("已分配人数发生变化，请核对后再保存");

        private final String message;

        Reason(String message) { this.message = message; }
    }

    private final Reason reason;

    public RoleManagementException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
