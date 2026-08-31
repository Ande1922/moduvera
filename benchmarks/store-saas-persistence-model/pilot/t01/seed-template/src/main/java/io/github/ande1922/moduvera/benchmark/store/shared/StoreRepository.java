package io.github.ande1922.moduvera.benchmark.store.shared;

import java.util.Optional;

public interface StoreRepository {
  Optional<Store> findById(TenantId tenantId, StoreId storeId);

  Store save(Store candidate);
}
