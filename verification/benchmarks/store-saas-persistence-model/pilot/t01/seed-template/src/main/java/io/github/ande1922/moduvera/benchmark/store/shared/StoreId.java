package io.github.ande1922.moduvera.benchmark.store.shared;

public record StoreId(long value) {
  public StoreId {
    if (value <= 0) {
      throw new IllegalArgumentException("store id must be positive");
    }
  }
}
