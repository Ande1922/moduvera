package io.github.ande1922.moduvera.benchmark.store.shared;

import java.util.Optional;

@FunctionalInterface
public interface ExecutionContextAccessor {
  Optional<ExecutionContext> current();
}
