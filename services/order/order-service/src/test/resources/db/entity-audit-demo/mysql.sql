CREATE TABLE entity_audit_order (
    tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by_type VARCHAR(16) NOT NULL,
    created_by_id VARCHAR(128) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by_type VARCHAR(16) NOT NULL,
    updated_by_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_entity_audit_order_tenant_id (tenant_id, order_id)
);
