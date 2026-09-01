package io.github.ande1922.moduvera.benchmark.store.shared;

import java.time.Instant;
import java.util.Objects;

public final class AuditMetadata {
  private Instant createdAt;
  private ActorType createdByType;
  private String createdById;
  private Instant updatedAt;
  private ActorType updatedByType;
  private String updatedById;

  private AuditMetadata() {}

  public AuditMetadata(Instant createdAt, Actor createdBy, Instant updatedAt, Actor updatedBy) {
    this.createdAt = createdAt;
    this.createdByType = createdBy == null ? null : createdBy.type();
    this.createdById = createdBy == null ? null : createdBy.subjectId();
    this.updatedAt = updatedAt;
    this.updatedByType = updatedBy == null ? null : updatedBy.type();
    this.updatedById = updatedBy == null ? null : updatedBy.subjectId();
  }

  public static AuditMetadata unpersisted() {
    return new AuditMetadata();
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Actor createdBy() {
    return createdByType == null ? null : new Actor(createdByType, createdById);
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Actor updatedBy() {
    return updatedByType == null ? null : new Actor(updatedByType, updatedById);
  }

  public boolean isPersisted() {
    return createdAt != null
        && createdByType != null
        && createdById != null
        && updatedAt != null
        && updatedByType != null
        && updatedById != null;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AuditMetadata that)) {
      return false;
    }
    return Objects.equals(createdAt, that.createdAt)
        && createdByType == that.createdByType
        && Objects.equals(createdById, that.createdById)
        && Objects.equals(updatedAt, that.updatedAt)
        && updatedByType == that.updatedByType
        && Objects.equals(updatedById, that.updatedById);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        createdAt, createdByType, createdById, updatedAt, updatedByType, updatedById);
  }

  @Override
  public String toString() {
    return "AuditMetadata[createdAt="
        + createdAt
        + ", createdBy="
        + createdBy()
        + ", updatedAt="
        + updatedAt
        + ", updatedBy="
        + updatedBy()
        + "]";
  }
}
