package io.github.ande1922.moduvera.reference.inventory.infrastructure.persistence;

public class InventoryStockRow {

    private long productId;
    private int available;
    private long version;

    public long getProductId() {
        return productId;
    }

    public void setProductId(long productId) {
        this.productId = productId;
    }

    public int getAvailable() {
        return available;
    }

    public void setAvailable(int available) {
        this.available = available;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
