package com.gaopc.benchmark.store.candidate;

import com.gaopc.benchmark.store.shared.TenantId;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public final class TenantIdTypeHandler extends BaseTypeHandler<TenantId> {
  @Override
  public void setNonNullParameter(
      PreparedStatement statement, int index, TenantId parameter, JdbcType jdbcType)
      throws SQLException {
    statement.setString(index, parameter.value());
  }

  @Override
  public TenantId getNullableResult(ResultSet results, String columnName) throws SQLException {
    String value = results.getString(columnName);
    return value == null ? null : new TenantId(value);
  }

  @Override
  public TenantId getNullableResult(ResultSet results, int columnIndex) throws SQLException {
    String value = results.getString(columnIndex);
    return value == null ? null : new TenantId(value);
  }

  @Override
  public TenantId getNullableResult(CallableStatement statement, int columnIndex)
      throws SQLException {
    String value = statement.getString(columnIndex);
    return value == null ? null : new TenantId(value);
  }
}
