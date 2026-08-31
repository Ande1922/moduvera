package com.gaopc.benchmark.store.candidate;

import com.gaopc.benchmark.store.shared.StoreId;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public final class StoreIdTypeHandler extends BaseTypeHandler<StoreId> {
  @Override
  public void setNonNullParameter(
      PreparedStatement statement, int index, StoreId parameter, JdbcType jdbcType)
      throws SQLException {
    statement.setLong(index, parameter.value());
  }

  @Override
  public StoreId getNullableResult(ResultSet results, String columnName) throws SQLException {
    long value = results.getLong(columnName);
    return results.wasNull() ? null : new StoreId(value);
  }

  @Override
  public StoreId getNullableResult(ResultSet results, int columnIndex) throws SQLException {
    long value = results.getLong(columnIndex);
    return results.wasNull() ? null : new StoreId(value);
  }

  @Override
  public StoreId getNullableResult(CallableStatement statement, int columnIndex)
      throws SQLException {
    long value = statement.getLong(columnIndex);
    return statement.wasNull() ? null : new StoreId(value);
  }
}
