CREATE TABLE catalog_product (
    tenant_id VARCHAR(128) NOT NULL,
    product_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    unit_price NUMERIC(19, 2) NOT NULL CHECK (unit_price >= 0),
    currency CHAR(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ(6) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ(6) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, product_id)
);
