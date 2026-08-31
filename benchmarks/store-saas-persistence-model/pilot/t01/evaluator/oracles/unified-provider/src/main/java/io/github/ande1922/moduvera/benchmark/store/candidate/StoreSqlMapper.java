package io.github.ande1922.moduvera.benchmark.store.candidate;

import io.github.ande1922.moduvera.benchmark.store.shared.Actor;
import io.github.ande1922.moduvera.benchmark.store.shared.Store;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreId;
import io.github.ande1922.moduvera.benchmark.store.shared.TenantId;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

interface StoreSqlMapper {
  Store selectById(
      @Param("tenantId") TenantId tenantId, @Param("storeId") StoreId storeId);

  int insert(
      @Param("store") Store store,
      @Param("auditAt") Instant auditAt,
      @Param("actor") Actor actor);

  int compareAndSet(
      @Param("store") Store store,
      @Param("auditAt") Instant auditAt,
      @Param("actor") Actor actor);
}
