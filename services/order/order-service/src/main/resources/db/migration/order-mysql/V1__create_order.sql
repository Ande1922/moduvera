CREATE TABLE order_header (
    tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING_STOCK', 'CONFIRMED', 'REJECTED')),
    currency CHAR(3) NOT NULL,
    placed_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, order_id)
);

CREATE TABLE order_line (
    tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    line_number INTEGER NOT NULL CHECK (line_number > 0),
    product_id BIGINT NOT NULL CHECK (product_id > 0),
    product_name VARCHAR(200) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price DECIMAL(19, 2) NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (tenant_id, order_id, line_number),
    UNIQUE (tenant_id, order_id, product_id),
    FOREIGN KEY (tenant_id, order_id)
        REFERENCES order_header (tenant_id, order_id) ON DELETE CASCADE
);
