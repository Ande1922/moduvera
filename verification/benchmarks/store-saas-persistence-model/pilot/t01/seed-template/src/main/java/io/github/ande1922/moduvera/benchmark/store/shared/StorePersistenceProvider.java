package io.github.ande1922.moduvera.benchmark.store.shared;

@FunctionalInterface
public interface StorePersistenceProvider {
  StoreRepository create(StorePersistenceDependencies dependencies);
}
