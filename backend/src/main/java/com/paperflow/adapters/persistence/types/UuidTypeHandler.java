package com.paperflow.adapters.persistence.types;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@MappedTypes(UUID.class)
public final class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID id, JdbcType type) throws SQLException {
        statement.setObject(index, id);
    }

    @Override
    public UUID getNullableResult(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet result, int index) throws SQLException {
        return result.getObject(index, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement result, int index) throws SQLException {
        return result.getObject(index, UUID.class);
    }
}
