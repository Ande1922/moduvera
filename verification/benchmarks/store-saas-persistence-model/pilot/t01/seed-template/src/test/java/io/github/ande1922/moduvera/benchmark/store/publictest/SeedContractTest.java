package io.github.ande1922.moduvera.benchmark.store.publictest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ande1922.moduvera.benchmark.store.shared.StoreId;
import io.github.ande1922.moduvera.benchmark.store.shared.TenantId;
import io.github.ande1922.moduvera.benchmark.store.shared.Actor;
import io.github.ande1922.moduvera.benchmark.store.shared.ActorType;
import io.github.ande1922.moduvera.benchmark.store.shared.ExecutionContext;
import io.github.ande1922.moduvera.benchmark.store.shared.ScopedExecutionContext;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SeedContractTest {
  @Test
  void tenantIdsAreTrimmedAndCaseSensitive() {
    assertEquals("Acme", new TenantId("  Acme ").value());
    assertEquals("acme", new TenantId("acme").value());
  }

  @Test
  void identifiersFailClosedOnInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> new TenantId("  "));
    assertThrows(IllegalArgumentException.class, () -> new StoreId(0));
  }

  @Test
  void executionContextScopeRestoresAfterSuccessAndFailure() {
    ScopedExecutionContext scope = new ScopedExecutionContext();
    ExecutionContext outer = context("outer");
    ExecutionContext inner = context("inner");

    scope.run(
        outer,
        () -> {
          assertEquals(outer, scope.current().orElseThrow());
          assertThrows(
              IllegalStateException.class,
              () -> scope.run(inner, () -> { throw new IllegalStateException("boom"); }));
          assertEquals(outer, scope.current().orElseThrow());
        });
    assertEquals(true, scope.current().isEmpty());
  }

  private static ExecutionContext context(String tenant) {
    return new ExecutionContext(
        new TenantId(tenant),
        new Actor(ActorType.USER, "test"),
        "corr",
        Set.of("store:read", "store:write"));
  }
}
