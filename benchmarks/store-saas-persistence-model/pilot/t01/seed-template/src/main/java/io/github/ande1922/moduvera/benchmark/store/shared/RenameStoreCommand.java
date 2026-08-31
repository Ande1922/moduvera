package io.github.ande1922.moduvera.benchmark.store.shared;

public record RenameStoreCommand(StoreId storeId, String name, long expectedVersion) {}
