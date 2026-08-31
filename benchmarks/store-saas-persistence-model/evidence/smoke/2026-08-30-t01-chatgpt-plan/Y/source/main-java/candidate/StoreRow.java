package com.gaopc.benchmark.store.candidate;

import java.time.LocalDateTime;

/** Flat database representation of one store_location row. */
final class StoreRow {
  private String tenantId;
  private long storeId;
  private String code;
  private String name;
  private String timeZoneId;
  private String status;
  private long version;
  private LocalDateTime createdAt;
  private String createdByType;
  private String createdById;
  private LocalDateTime updatedAt;
  private String updatedByType;
  private String updatedById;

  StoreRow() {}

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

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
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

  public String getCreatedByType() {
    return createdByType;
  }

  public void setCreatedByType(String createdByType) {
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

  public String getUpdatedByType() {
    return updatedByType;
  }

  public void setUpdatedByType(String updatedByType) {
    this.updatedByType = updatedByType;
  }

  public String getUpdatedById() {
    return updatedById;
  }

  public void setUpdatedById(String updatedById) {
    this.updatedById = updatedById;
  }
}
