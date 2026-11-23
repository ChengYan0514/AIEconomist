package com.paperflow.adapters.persistence;

import com.paperflow.account.Accounts;
import com.paperflow.account.model.AccountAccess;
import com.paperflow.account.model.PlatformUser;
import com.paperflow.account.model.Role;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AccountMapper extends Accounts {
    String USER_COLUMNS = "username, display_name, role, enabled, created_at";
    String USER_FILTER = """
             WHERE (#{query} = '' OR strpos(lower(username), lower(#{query})) > 0
                     OR strpos(lower(display_name), lower(#{query})) > 0)
              <if test="role != null">AND role = #{role}</if>
              <if test="enabled != null">AND enabled = #{enabled}</if>
            """;

    @Override
    @Select("SELECT password_hash, enabled, must_change_password, credential_version FROM v2_admin WHERE username = #{username}")
    Credentials credentials(String username);

    @Override
    @Select("SELECT username, display_name, role, enabled, must_change_password, credential_version FROM v2_admin WHERE username = #{username}")
    AccountAccess access(String username);

    @Override
    @Select("SELECT " + USER_COLUMNS + " FROM v2_admin WHERE username = #{username}")
    PlatformUser find(String username);

    @Override
    @Select("<script>SELECT " + USER_COLUMNS + " FROM v2_admin " + USER_FILTER
            + " ORDER BY username LIMIT #{size} OFFSET #{offset}</script>")
    List<PlatformUser> list(String query, Role role, Boolean enabled, long offset, int size);

    @Override
    @Select("<script>SELECT count(*) FROM v2_admin " + USER_FILTER + "</script>")
    long count(String query, Role role, Boolean enabled);

    @Override
    @Insert("""
            INSERT INTO v2_admin (username, display_name, password_hash, role, must_change_password)
            VALUES (#{username}, #{displayName}, #{passwordHash}, #{role}, #{mustChangePassword})
            ON CONFLICT (username) DO NOTHING
            """)
    int create(String username, String displayName, String passwordHash, Role role, boolean mustChangePassword);

    @Override
    @Update("""
            UPDATE v2_admin SET password_hash = #{passwordHash}, must_change_password = false,
                credential_version = credential_version + 1
            WHERE username = #{username} AND enabled AND credential_version = #{credentialVersion}
            """)
    int changePassword(String username, long credentialVersion, String passwordHash);

    @Override
    @Update("""
            UPDATE v2_admin SET password_hash = #{passwordHash}, must_change_password = true,
                credential_version = credential_version + 1 WHERE username = #{username}
            """)
    void resetPassword(String username, String passwordHash);

    @Override
    @Select("SELECT true FROM pg_advisory_xact_lock(hashtext(current_schema()), 1178512460)")
    boolean lockManagement();

    @Override
    @Select("SELECT count(*) FROM v2_admin WHERE role = 'SYSTEM_ADMIN' AND enabled")
    int enabledAdministrators();

    @Override
    @Update("UPDATE v2_admin SET display_name = #{displayName}, role = #{role} WHERE username = #{username}")
    void updateProfile(String username, String displayName, Role role);

    @Override
    @Update("""
            UPDATE v2_admin SET enabled = #{enabled}, credential_version = credential_version + 1
            WHERE username = #{username}
            """)
    void setEnabled(String username, boolean enabled);
}
