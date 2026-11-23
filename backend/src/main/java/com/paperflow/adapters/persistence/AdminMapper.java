package com.paperflow.adapters.persistence;

import com.paperflow.account.AdminAccounts;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminMapper extends AdminAccounts {
    @Override
    @Select("SELECT password_hash FROM v2_admin WHERE username = #{username}")
    String passwordHash(String username);

    @Override
    @Insert("INSERT INTO v2_admin (username, password_hash) VALUES (#{username}, #{passwordHash}) ON CONFLICT DO NOTHING")
    int create(String username, String passwordHash);

    @Override
    @Update("UPDATE v2_admin SET password_hash = #{passwordHash} WHERE username = #{username} AND password_hash = #{oldHash}")
    int change(String username, String oldHash, String passwordHash);
}
