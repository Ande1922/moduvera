package io.github.ande1922.moduvera.benchmark.store.shared;

import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

public record StorePersistenceDependencies(
    DataSource dataSource, ExecutionContextAccessor contextAccessor, Clock clock) {
  public StorePersistenceDependencies {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(contextAccessor, "contextAccessor");
    Objects.requireNonNull(clock, "clock");
  }
}
