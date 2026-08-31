CREATE TABLE demo_note (
    id BIGINT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    content VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL
);

CREATE INDEX idx_demo_note_tenant_id ON demo_note (tenant_id, id);

CREATE TABLE demo_note_receipt (
    message_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    note_id BIGINT NOT NULL,
    received_at TIMESTAMPTZ(6) NOT NULL
);
