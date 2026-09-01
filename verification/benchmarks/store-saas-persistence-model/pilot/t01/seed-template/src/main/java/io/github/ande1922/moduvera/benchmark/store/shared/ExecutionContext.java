package io.github.ande1922.moduvera.benchmark.store.shared;

import java.util.Objects;
import java.util.Set;

public record ExecutionContext(
    TenantId tenantId, Actor actor, String correlationId, Set<String> permissions) {
  public ExecutionContext {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(actor, "actor");
    correlationId = Objects.requireNonNull(correlationId, "correlationId").trim();
    if (correlationId.isEmpty()) {
      throw new IllegalArgumentException("correlation id must not be empty");
    }
    permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
  }

  public boolean hasPermission(String permission) {
    return permissions.contains(permission);
  }
}
