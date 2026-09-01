package io.github.ande1922.moduvera.benchmark.store.shared;

public record StoreView(
    StoreId storeId,
    String code,
    String name,
    String timeZoneId,
    StoreStatus status,
    long version,
    AuditMetadata audit) {

  public static StoreView from(Store store) {
    return new StoreView(
        store.id(),
        store.code(),
        store.name(),
        store.timeZoneId(),
        store.status(),
        store.version(),
        store.audit());
  }
}
