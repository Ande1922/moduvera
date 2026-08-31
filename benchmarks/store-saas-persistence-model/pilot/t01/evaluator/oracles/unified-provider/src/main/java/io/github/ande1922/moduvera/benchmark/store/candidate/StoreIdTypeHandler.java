package io.github.ande1922.moduvera.benchmark.store.candidate;

import io.github.ande1922.moduvera.benchmark.store.shared.StoreId;
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
  public StoreId getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
    return value(resultSet.getLong(columnName), resultSet.wasNull());
  }

  @Override
  public StoreId getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
    return value(resultSet.getLong(columnIndex), resultSet.wasNull());
  }

  @Override
  public StoreId getNullableResult(CallableStatement statement, int columnIndex)
      throws SQLException {
    return value(statement.getLong(columnIndex), statement.wasNull());
  }

  private static StoreId value(long raw, boolean wasNull) {
    return wasNull ? null : new StoreId(raw);
  }
}
