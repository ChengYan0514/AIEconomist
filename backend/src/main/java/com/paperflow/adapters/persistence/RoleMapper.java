package com.paperflow.adapters.persistence;

import com.paperflow.account.Roles;
import com.paperflow.account.model.Permission;
import com.paperflow.account.model.PlatformRole;
import com.paperflow.account.model.Role;
import com.paperflow.account.model.RoleProfile;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public interface RoleMapper extends Roles {
    String COLUMNS = "SELECT role_id AS id, name, description, array_to_string(permissions, ',') AS codes, "
            + "(SELECT count(*) FROM v2_admin WHERE role = r.role_id) AS user_count FROM v2_role r";

    record RoleRow(String id, String name, String description, String codes, long userCount) {
        PlatformRole role() {
            return new PlatformRole(id, new RoleProfile(name, description, permissions(id, codes)), userCount);
        }
    }

    @Select(COLUMNS + " ORDER BY CASE role_id WHEN 'SYSTEM_ADMIN' THEN 0 WHEN 'DATA_MAINTAINER' THEN 1 "
            + "WHEN 'READ_ONLY' THEN 2 WHEN 'RESEARCHER' THEN 3 ELSE 4 END, lower(name), role_id")
    List<RoleRow> roleRows();

    @Select(COLUMNS + " WHERE r.role_id = #{id}")
    RoleRow roleRow(String id);

    @Override
    default List<PlatformRole> listRoles() {
        return roleRows().stream().map(RoleRow::role).toList();
    }

    @Override
    default PlatformRole findRole(String id) {
        RoleRow row = roleRow(id);
        return row == null ? null : row.role();
    }

    @Override
    @Select("SELECT username, display_name, enabled FROM v2_admin WHERE role = #{id} ORDER BY username")
    List<Member> members(String id);

    @Override
    @Select("SELECT EXISTS (SELECT 1 FROM v2_role WHERE role_id = #{id})")
    boolean roleExists(String id);

    @Override
    @Select("SELECT EXISTS (SELECT 1 FROM v2_role WHERE lower(name) = lower(#{name}) AND role_id <> #{exceptRole})")
    boolean nameUsed(String name, String exceptRole);

    @Override
    default void createRole(String id, RoleProfile profile) {
        insertRole(id, profile.name(), profile.description(), codes(profile));
    }

    @Insert("INSERT INTO v2_role (role_id, name, description, permissions) "
            + "VALUES (#{id}, #{name}, #{description}, string_to_array(#{codes}, ','))")
    void insertRole(String id, String name, String description, String codes);

    @Override
    default void updateRole(String id, RoleProfile profile) {
        updateRoleRow(id, profile.name(), profile.description(), codes(profile));
    }

    @Update("UPDATE v2_role SET name = #{name}, description = #{description}, "
            + "permissions = string_to_array(#{codes}, ',') WHERE role_id = #{id}")
    void updateRoleRow(String id, String name, String description, String codes);

    @Override
    @Delete("DELETE FROM v2_role WHERE role_id = #{id}")
    void deleteRole(String id);

    static Set<Permission> permissions(String id, String codes) {
        Role builtIn = Role.builtIn(id);
        if (builtIn != null) return builtIn.permissions();
        if (codes.isEmpty()) return Set.of();
        return Arrays.stream(codes.split(",")).map(Permission::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    private static String codes(RoleProfile profile) {
        return profile.permissions().stream().map(Permission::name).sorted().collect(Collectors.joining(","));
    }
}
