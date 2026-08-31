CREATE TABLE inventory_stock (
    tenant_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL CHECK (product_id > 0),
    available INTEGER NOT NULL CHECK (available >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, product_id)
);

CREATE TABLE inventory_reservation_result (
    tenant_id VARCHAR(64) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL CHECK (order_id > 0),
    result_type VARCHAR(16) NOT NULL CHECK (result_type IN ('RESERVED', 'REJECTED')),
    unavailable_product_ids VARCHAR(2000) NOT NULL DEFAULT '',
    decided_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, command_id),
    UNIQUE (tenant_id, order_id)
);
