package io.github.ande1922.moduvera.benchmark.store.shared;

public record DeactivateStoreCommand(StoreId storeId, long expectedVersion) {}
