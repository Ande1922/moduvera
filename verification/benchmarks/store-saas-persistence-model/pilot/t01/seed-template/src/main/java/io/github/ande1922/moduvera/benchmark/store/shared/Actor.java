package io.github.ande1922.moduvera.benchmark.store.shared;

import java.util.Objects;

public record Actor(ActorType type, String subjectId) {
  public Actor {
    Objects.requireNonNull(type, "type");
    subjectId = Objects.requireNonNull(subjectId, "subjectId").trim();
    if (subjectId.isEmpty() || subjectId.length() > 128) {
      throw new IllegalArgumentException("actor subject length must be 1..128");
    }
  }
}
