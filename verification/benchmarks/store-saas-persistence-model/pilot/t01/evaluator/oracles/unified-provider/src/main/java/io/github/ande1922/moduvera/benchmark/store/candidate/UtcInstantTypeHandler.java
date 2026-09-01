package io.github.ande1922.moduvera.benchmark.store.candidate;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public final class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  @Override
  public void setNonNullParameter(
      PreparedStatement statement, int index, Instant parameter, JdbcType jdbcType)
      throws SQLException {
    statement.setTimestamp(index, Timestamp.from(parameter), utcCalendar());
  }

  @Override
  public Instant getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
    return value(resultSet.getTimestamp(columnName, utcCalendar()));
  }

  @Override
  public Instant getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
    return value(resultSet.getTimestamp(columnIndex, utcCalendar()));
  }

  @Override
  public Instant getNullableResult(CallableStatement statement, int columnIndex)
      throws SQLException {
    return value(statement.getTimestamp(columnIndex, utcCalendar()));
  }

  private static Calendar utcCalendar() {
    return Calendar.getInstance(UTC);
  }

  private static Instant value(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
