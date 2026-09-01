package io.github.ande1922.moduvera.benchmark.store.shared;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class ScopedExecutionContext implements ExecutionContextAccessor {
  private final ThreadLocal<ExecutionContext> local = new ThreadLocal<>();

  @Override
  public Optional<ExecutionContext> current() {
    return Optional.ofNullable(local.get());
  }

  public <T> T call(ExecutionContext context, Supplier<T> work) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(work, "work");
    ExecutionContext previous = local.get();
    local.set(context);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        local.remove();
      } else {
        local.set(previous);
      }
    }
  }

  public void run(ExecutionContext context, Runnable work) {
    call(
        context,
        () -> {
          work.run();
          return null;
        });
  }
}
