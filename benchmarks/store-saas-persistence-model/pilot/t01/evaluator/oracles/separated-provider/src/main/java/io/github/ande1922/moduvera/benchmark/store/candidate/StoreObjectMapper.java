package io.github.ande1922.moduvera.benchmark.store.candidate;

import io.github.ande1922.moduvera.benchmark.store.shared.Actor;
import io.github.ande1922.moduvera.benchmark.store.shared.AuditMetadata;
import io.github.ande1922.moduvera.benchmark.store.shared.Store;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreId;
import io.github.ande1922.moduvera.benchmark.store.shared.TenantId;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class StoreObjectMapper {
  public StoreRow forInsert(Store draft, Actor actor, Instant now) {
    Objects.requireNonNull(draft, "draft");
    Objects.requireNonNull(actor, "actor");
    LocalDateTime persistedAt = persistedTime(now);

    StoreRow row = commonFields(draft);
    row.setVersion(0);
    row.setCreatedAt(persistedAt);
    row.setCreatedByType(actor.type());
    row.setCreatedById(actor.subjectId());
    row.setUpdatedAt(persistedAt);
    row.setUpdatedByType(actor.type());
    row.setUpdatedById(actor.subjectId());
    return row;
  }

  public StoreRow forUpdate(Store candidate, Actor actor, Instant now) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(actor, "actor");

    StoreRow row = commonFields(candidate);
    row.setVersion(Math.addExact(candidate.version(), 1));
    row.setUpdatedAt(persistedTime(now));
    row.setUpdatedByType(actor.type());
    row.setUpdatedById(actor.subjectId());
    return row;
  }

  public Store toDomain(StoreRow row) {
    Objects.requireNonNull(row, "row");
    AuditMetadata audit =
        new AuditMetadata(
            row.getCreatedAt().toInstant(ZoneOffset.UTC),
            new Actor(row.getCreatedByType(), row.getCreatedById()),
            row.getUpdatedAt().toInstant(ZoneOffset.UTC),
            new Actor(row.getUpdatedByType(), row.getUpdatedById()));
    return Store.restored(
        new StoreId(row.getStoreId()),
        new TenantId(row.getTenantId()),
        row.getCode(),
        row.getName(),
        row.getTimeZoneId(),
        row.getStatus(),
        row.getVersion(),
        audit);
  }

  private static StoreRow commonFields(Store store) {
    StoreRow row = new StoreRow();
    row.setTenantId(store.tenantId().value());
    row.setStoreId(store.id().value());
    row.setCode(store.code());
    row.setName(store.name());
    row.setTimeZoneId(store.timeZoneId());
    row.setStatus(store.status());
    return row;
  }

  private static LocalDateTime persistedTime(Instant instant) {
    Instant normalized =
        Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.MICROS);
    return LocalDateTime.ofInstant(normalized, ZoneOffset.UTC);
  }
}
