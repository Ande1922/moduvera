package com.gaopc.benchmark.store.candidate;

import com.gaopc.benchmark.store.shared.Actor;
import com.gaopc.benchmark.store.shared.ActorType;
import com.gaopc.benchmark.store.shared.AuditMetadata;
import com.gaopc.benchmark.store.shared.Store;
import com.gaopc.benchmark.store.shared.StoreId;
import com.gaopc.benchmark.store.shared.StoreStatus;
import com.gaopc.benchmark.store.shared.TenantId;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Explicit boundary mapper between the database row and framework-free aggregate. */
final class StoreObjectMapper {
  StoreRow forInsert(Store store, TenantId tenantId, Actor actor, Instant persistedAt) {
    StoreRow row = common(store, tenantId);
    LocalDateTime databaseTime = toDatabaseTime(persistedAt);
    row.setVersion(0);
    row.setCreatedAt(databaseTime);
    row.setCreatedByType(actor.type().name());
    row.setCreatedById(actor.subjectId());
    row.setUpdatedAt(databaseTime);
    row.setUpdatedByType(actor.type().name());
    row.setUpdatedById(actor.subjectId());
    return row;
  }

  StoreRow forUpdate(Store store, TenantId tenantId, Actor actor, Instant persistedAt) {
    StoreRow row = common(store, tenantId);
    row.setVersion(store.version());
    row.setUpdatedAt(toDatabaseTime(persistedAt));
    row.setUpdatedByType(actor.type().name());
    row.setUpdatedById(actor.subjectId());
    return row;
  }

  Store toDomain(StoreRow row) {
    Actor createdBy =
        new Actor(ActorType.valueOf(row.getCreatedByType()), row.getCreatedById());
    Actor updatedBy =
        new Actor(ActorType.valueOf(row.getUpdatedByType()), row.getUpdatedById());
    AuditMetadata audit =
        new AuditMetadata(
            toInstant(row.getCreatedAt()),
            createdBy,
            toInstant(row.getUpdatedAt()),
            updatedBy);
    return Store.restored(
        new StoreId(row.getStoreId()),
        new TenantId(row.getTenantId()),
        row.getCode(),
        row.getName(),
        row.getTimeZoneId(),
        StoreStatus.valueOf(row.getStatus()),
        row.getVersion(),
        audit);
  }

  private static StoreRow common(Store store, TenantId tenantId) {
    StoreRow row = new StoreRow();
    row.setTenantId(tenantId.value());
    row.setStoreId(store.id().value());
    row.setCode(store.code());
    row.setName(store.name());
    row.setTimeZoneId(store.timeZoneId());
    row.setStatus(store.status().name());
    return row;
  }

  private static LocalDateTime toDatabaseTime(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static Instant toInstant(LocalDateTime value) {
    return value.toInstant(ZoneOffset.UTC);
  }
}
