package com.paperflow.adapters.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class PgVarcharArrayTypeHandler extends BaseTypeHandler<String[]> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, String[] parameter, JdbcType jdbcType)
            throws SQLException {
        statement.setArray(index, statement.getConnection().createArrayOf("varchar", parameter));
    }

    @Override
    public String[] getNullableResult(ResultSet rs, String columnName) {
        return null;
    }

    @Override
    public String[] getNullableResult(ResultSet rs, int columnIndex) {
        return null;
    }

    @Override
    public String[] getNullableResult(CallableStatement cs, int columnIndex) {
        return null;
    }
}
