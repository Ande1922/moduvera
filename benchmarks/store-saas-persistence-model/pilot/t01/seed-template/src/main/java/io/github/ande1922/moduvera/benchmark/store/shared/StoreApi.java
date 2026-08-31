package io.github.ande1922.moduvera.benchmark.store.shared;

public interface StoreApi {
  StoreView create(CreateStoreCommand command);

  StoreView rename(RenameStoreCommand command);

  StoreView deactivate(DeactivateStoreCommand command);

  StoreView get(GetStoreQuery query);
}
