package com.paperflow.account.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public enum Permission {
    PAPER_READ(Group.PAPERS, "查阅论文"),
    CONTENT_READ(Group.PAPERS, "查看原件和全文", PAPER_READ),
    PAPER_CSV_EXPORT(Group.PAPERS, "导出论文 CSV"),
    PAPER_WRITE(Group.PAPERS, "维护论文", PAPER_READ),
    JOB_READ(Group.JOBS, "查阅任务"),
    PDF_PARSE(Group.JOBS, "执行文件解析", JOB_READ),
    FULL_TEXT_IMPORT(Group.JOBS, "执行解析内容导入", JOB_READ),
    OPENALEX_MANAGE(Group.OPENALEX, "导入元数据、匹配和复核候选", PAPER_READ, JOB_READ),
    SYSTEM_MANAGE(null, "系统管理");

    public enum Group {
        PAPERS("论文库"), JOBS("导入与处理"), OPENALEX("OpenAlex");

        private final String label;

        Group(String label) { this.label = label; }
        public String label() { return label; }

        public List<Permission> permissions() {
            return Arrays.stream(Permission.values()).filter(permission -> permission.group == this).toList();
        }
    }

    private final Group group;
    private final String label;
    private final List<Permission> requires;

    Permission(Group group, String label, Permission... requires) {
        this.group = group;
        this.label = label;
        this.requires = List.of(requires);
    }

    public String label() { return label; }
    public List<Permission> requires() { return requires; }

    public static Set<Permission> businessPermissions(List<String> codes) {
        if (codes == null) throw new IllegalArgumentException("请选择要保存的业务权限");
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String code : codes) {
            Permission permission = fromCode(code);
            if (permission.group == null) throw new IllegalArgumentException("系统管理权限由内置系统管理员持有");
            if (!permissions.add(permission)) throw new IllegalArgumentException("业务权限不能重复");
        }
        for (Permission permission : permissions) {
            if (!permissions.containsAll(permission.requires)) {
                var labels = permission.requires.stream().map(Permission::label).toList();
                throw new IllegalArgumentException(permission.label + "依赖" + String.join("、", labels));
            }
        }
        return permissions;
    }

    private static Permission fromCode(String code) {
        if (code != null) {
            for (Permission permission : values()) {
                if (permission.name().equals(code)) return permission;
            }
        }
        throw new IllegalArgumentException("存在无效权限");
    }
}
