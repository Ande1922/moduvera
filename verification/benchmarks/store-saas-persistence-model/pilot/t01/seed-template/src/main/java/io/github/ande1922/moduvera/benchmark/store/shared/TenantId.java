package io.github.ande1922.moduvera.benchmark.store.shared;

import java.util.Objects;

public record TenantId(String value) {
  public TenantId {
    value = Objects.requireNonNull(value, "value").trim();
    if (value.isEmpty() || value.length() > 64) {
      throw new IllegalArgumentException("tenant id length must be 1..64");
    }
  }
}
