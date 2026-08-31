CREATE TABLE moduvera_message_outbox (
    message_id VARCHAR(128) PRIMARY KEY,
    message_kind VARCHAR(32) NOT NULL CHECK (message_kind IN ('EVENT', 'ASYNC_COMMAND')),
    message_type VARCHAR(256) NOT NULL,
    source VARCHAR(512) NOT NULL,
    destination VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMPTZ(6) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_subject VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    causation_id VARCHAR(128),
    initiator_type VARCHAR(32) NOT NULL,
    initiator_subject VARCHAR(128) NOT NULL,
    partition_key VARCHAR(256) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    payload BYTEA NOT NULL,
    status VARCHAR(32) NOT NULL,
    next_attempt_at TIMESTAMPTZ(6) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    claim_token VARCHAR(64),
    claim_expires_at TIMESTAMPTZ(6),
    published_at TIMESTAMPTZ(6),
    terminal_at TIMESTAMPTZ(6),
    last_failure VARCHAR(256)
);

CREATE INDEX idx_moduvera_message_outbox_pending
    ON moduvera_message_outbox (next_attempt_at, occurred_at, message_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_moduvera_message_outbox_scope_order
    ON moduvera_message_outbox (destination, partition_key, occurred_at, message_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_moduvera_message_outbox_published_cleanup
    ON moduvera_message_outbox (published_at, message_id)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_moduvera_message_outbox_terminal_redrive
    ON moduvera_message_outbox (terminal_at, message_id)
    WHERE status = 'TERMINAL';

CREATE TABLE moduvera_message_inbox (
    tenant_id VARCHAR(128) NOT NULL,
    consumer_id VARCHAR(128) NOT NULL,
    message_id VARCHAR(128) NOT NULL,
    processed_at TIMESTAMPTZ(6) NOT NULL,
    PRIMARY KEY (tenant_id, consumer_id, message_id)
);
