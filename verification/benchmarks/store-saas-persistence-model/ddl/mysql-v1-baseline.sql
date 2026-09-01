-- Frozen MySQL 8.4 logical/physical DDL for the first MP+MySQL Pilot.
-- Each Business Service applies only its owned section. Cross-service IDs are
-- scalar snapshots/references and deliberately have no foreign keys.

CREATE TABLE store_location (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    store_id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by_type VARCHAR(32) NOT NULL,
    created_by_id VARCHAR(128) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by_type VARCHAR(32) NOT NULL,
    updated_by_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (store_id),
    UNIQUE KEY uk_store_tenant_id (tenant_id, store_id),
    UNIQUE KEY uk_store_tenant_code (tenant_id, code),
    CONSTRAINT ck_store_version CHECK (version >= 0),
    CONSTRAINT ck_store_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_product (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    product_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by_type VARCHAR(32) NOT NULL,
    created_by_id VARCHAR(128) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by_type VARCHAR(32) NOT NULL,
    updated_by_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (product_id),
    UNIQUE KEY uk_product_tenant_id (tenant_id, product_id),
    CONSTRAINT ck_product_price CHECK (unit_price >= 0),
    CONSTRAINT ck_product_version CHECK (version >= 0),
    CONSTRAINT ck_product_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sales_order (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    order_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    total_amount DECIMAL(19,4) NOT NULL,
    placed_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by_type VARCHAR(32) NOT NULL,
    created_by_id VARCHAR(128) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by_type VARCHAR(32) NOT NULL,
    updated_by_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_order_tenant_id (tenant_id, order_id),
    KEY ix_order_search (tenant_id, store_id, status, placed_at, order_id),
    CONSTRAINT ck_order_total CHECK (total_amount >= 0),
    CONSTRAINT ck_order_version CHECK (version >= 0),
    CONSTRAINT ck_order_status CHECK (status IN ('PENDING_STOCK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sales_order_line (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    order_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    quantity BIGINT NOT NULL,
    line_total DECIMAL(19,4) NOT NULL,
    PRIMARY KEY (tenant_id, order_id, line_no),
    UNIQUE KEY uk_order_line_product (tenant_id, order_id, product_id),
    CONSTRAINT fk_order_line_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES sales_order (tenant_id, order_id),
    CONSTRAINT ck_order_line_number CHECK (line_no BETWEEN 1 AND 2),
    CONSTRAINT ck_order_line_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_line_total CHECK (line_total >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_stock (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    store_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    available_quantity BIGINT NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by_type VARCHAR(32) NOT NULL,
    created_by_id VARCHAR(128) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by_type VARCHAR(32) NOT NULL,
    updated_by_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, store_id, product_id),
    CONSTRAINT ck_stock_quantity CHECK (available_quantity >= 0),
    CONSTRAINT ck_stock_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_adjustment (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    command_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    store_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity_delta BIGINT NOT NULL,
    expected_version BIGINT NOT NULL,
    resulting_quantity BIGINT NOT NULL,
    resulting_version BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64) NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, command_id),
    KEY ix_adjustment_stock (tenant_id, store_id, product_id),
    CONSTRAINT ck_adjustment_result_quantity CHECK (resulting_quantity >= 0),
    CONSTRAINT ck_adjustment_result_version CHECK (resulting_version >= 0),
    CONSTRAINT ck_adjustment_outcome CHECK (outcome IN ('APPLIED', 'REJECTED')),
    CONSTRAINT ck_adjustment_failure CHECK (
        (outcome = 'APPLIED' AND failure_code IS NULL)
        OR (outcome = 'REJECTED' AND failure_code IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_reservation (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    command_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    order_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    result_message_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (tenant_id, command_id),
    UNIQUE KEY uk_reservation_order (tenant_id, order_id),
    UNIQUE KEY uk_reservation_result_message (tenant_id, result_message_id),
    CONSTRAINT ck_reservation_outcome CHECK (outcome IN ('RESERVED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_reservation_line (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    command_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    product_id BIGINT NOT NULL,
    requested_quantity BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    PRIMARY KEY (tenant_id, command_id, product_id),
    CONSTRAINT fk_reservation_line_reservation FOREIGN KEY (tenant_id, command_id)
        REFERENCES inventory_reservation (tenant_id, command_id),
    CONSTRAINT ck_reservation_line_quantity CHECK (requested_quantity > 0),
    CONSTRAINT ck_reservation_line_outcome CHECK (outcome IN ('RESERVED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- This table is instantiated unchanged inside each asynchronous Business Service.
CREATE TABLE message_outbox (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    message_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    message_type VARCHAR(128) NOT NULL,
    source VARCHAR(255) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    causation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    initiator_type VARCHAR(32) NOT NULL,
    initiator_id VARCHAR(128) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    last_failure VARCHAR(1024) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, message_id),
    KEY ix_outbox_dispatch (status, next_attempt_at, lease_until, created_at),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'LEASED', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- This table is instantiated unchanged inside each message-consuming Business Service.
CREATE TABLE message_inbox (
    tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    message_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    message_type VARCHAR(128) NOT NULL,
    source VARCHAR(255) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
