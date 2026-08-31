package io.github.ande1922.moduvera.benchmark.store.candidate;

import io.github.ande1922.moduvera.benchmark.store.shared.ActorType;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreStatus;
import java.time.LocalDateTime;

public final class StoreRow {
  private String tenantId;
  private long storeId;
  private String code;
  private String name;
  private String timeZoneId;
  private StoreStatus status;
  private long version;
  private LocalDateTime createdAt;
  private ActorType createdByType;
  private String createdById;
  private LocalDateTime updatedAt;
  private ActorType updatedByType;
  private String updatedById;

  public StoreRow() {}

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public long getStoreId() {
    return storeId;
  }

  public void setStoreId(long storeId) {
    this.storeId = storeId;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getTimeZoneId() {
    return timeZoneId;
  }

  public void setTimeZoneId(String timeZoneId) {
    this.timeZoneId = timeZoneId;
  }

  public StoreStatus getStatus() {
    return status;
  }

  public void setStatus(StoreStatus status) {
    this.status = status;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public ActorType getCreatedByType() {
    return createdByType;
  }

  public void setCreatedByType(ActorType createdByType) {
    this.createdByType = createdByType;
  }

  public String getCreatedById() {
    return createdById;
  }

  public void setCreatedById(String createdById) {
    this.createdById = createdById;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public ActorType getUpdatedByType() {
    return updatedByType;
  }

  public void setUpdatedByType(ActorType updatedByType) {
    this.updatedByType = updatedByType;
  }

  public String getUpdatedById() {
    return updatedById;
  }

  public void setUpdatedById(String updatedById) {
    this.updatedById = updatedById;
  }
}
