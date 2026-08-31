package io.github.ande1922.moduvera.benchmark.store.candidate;

import io.github.ande1922.moduvera.benchmark.store.shared.TenantId;
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
  public TenantId getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
    return value(resultSet.getString(columnName));
  }

  @Override
  public TenantId getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
    return value(resultSet.getString(columnIndex));
  }

  @Override
  public TenantId getNullableResult(CallableStatement statement, int columnIndex)
      throws SQLException {
    return value(statement.getString(columnIndex));
  }

  private static TenantId value(String raw) {
    return raw == null ? null : new TenantId(raw);
  }
}
