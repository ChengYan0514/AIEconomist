package com.paperflow.adapters.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class PgBigintArrayTypeHandler extends BaseTypeHandler<Long[]> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Long[] parameter, JdbcType jdbcType)
            throws SQLException {
        statement.setArray(index, statement.getConnection().createArrayOf("bigint", parameter));
    }

    @Override
    public Long[] getNullableResult(ResultSet rs, String columnName) {
        return null;
    }

    @Override
    public Long[] getNullableResult(ResultSet rs, int columnIndex) {
        return null;
    }

    @Override
    public Long[] getNullableResult(CallableStatement cs, int columnIndex) {
        return null;
    }
}
