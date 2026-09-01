package io.github.ande1922.moduvera.benchmark.store.shared;

import java.util.Objects;

public final class Store {
  private StoreId id;
  private TenantId tenantId;
  private String code;
  private String name;
  private String timeZoneId;
  private StoreStatus status;
  private long version;
  private AuditMetadata audit;

  private Store() {}

  private Store(
      StoreId id,
      TenantId tenantId,
      String code,
      String name,
      String timeZoneId,
      StoreStatus status,
      long version,
      AuditMetadata audit) {
    this.id = Objects.requireNonNull(id, "id");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.code = Objects.requireNonNull(code, "code");
    this.name = Objects.requireNonNull(name, "name");
    this.timeZoneId = Objects.requireNonNull(timeZoneId, "timeZoneId");
    this.status = Objects.requireNonNull(status, "status");
    this.version = version;
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  public static Store draft(
      StoreId id, TenantId tenantId, String code, String name, String timeZoneId) {
    return new Store(
        id,
        tenantId,
        code,
        name,
        timeZoneId,
        StoreStatus.ACTIVE,
        0,
        AuditMetadata.unpersisted());
  }

  public static Store restored(
      StoreId id,
      TenantId tenantId,
      String code,
      String name,
      String timeZoneId,
      StoreStatus status,
      long version,
      AuditMetadata audit) {
    if (version < 0 || !audit.isPersisted()) {
      throw new IllegalArgumentException("persisted Store requires version and audit");
    }
    return new Store(id, tenantId, code, name, timeZoneId, status, version, audit);
  }

  public StoreId id() {
    return id;
  }

  public TenantId tenantId() {
    return tenantId;
  }

  public String code() {
    return code;
  }

  public String name() {
    return name;
  }

  public String timeZoneId() {
    return timeZoneId;
  }

  public StoreStatus status() {
    return status;
  }

  public long version() {
    return version;
  }

  public AuditMetadata audit() {
    return audit;
  }

  public boolean isPersisted() {
    return audit.isPersisted();
  }

  public Store renamed(String newName) {
    return new Store(id, tenantId, code, newName, timeZoneId, status, version, audit);
  }

  public Store deactivated() {
    return new Store(
        id, tenantId, code, name, timeZoneId, StoreStatus.INACTIVE, version, audit);
  }
}
