package io.github.ande1922.moduvera.reference.inventory.infrastructure.persistence;

import java.time.Instant;

public class InventoryResultRow {

    private String commandId;
    private long orderId;
    private String resultType;
    private String unavailableProductIds;
    private Instant decidedAt;

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public String getResultType() {
        return resultType;
    }

    public void setResultType(String resultType) {
        this.resultType = resultType;
    }

    public String getUnavailableProductIds() {
        return unavailableProductIds;
    }

    public void setUnavailableProductIds(String unavailableProductIds) {
        this.unavailableProductIds = unavailableProductIds;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
