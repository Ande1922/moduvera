package io.github.ande1922.moduvera.benchmark.store.shared;

import java.util.Objects;

public final class StoreError extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public StoreError(String code) {
    this(code, null);
  }

  public StoreError(String code, Throwable cause) {
    super(Objects.requireNonNull(code, "code"), cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
