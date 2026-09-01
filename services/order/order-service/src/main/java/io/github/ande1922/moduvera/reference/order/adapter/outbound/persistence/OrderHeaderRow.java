package io.github.ande1922.moduvera.reference.order.adapter.outbound.persistence;

import java.time.Instant;

public class OrderHeaderRow {

    private String tenantId;
    private long orderId;
    private String status;
    private String currency;
    private Instant placedAt;
    private long version;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public void setPlacedAt(Instant placedAt) {
        this.placedAt = placedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
