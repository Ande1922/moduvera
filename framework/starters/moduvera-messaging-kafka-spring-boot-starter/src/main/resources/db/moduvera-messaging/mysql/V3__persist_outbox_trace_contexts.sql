ALTER TABLE moduvera_message_outbox
    ADD COLUMN creation_traceparent VARCHAR(512),
    ADD COLUMN creation_tracestate VARCHAR(512),
    ADD COLUMN publication_traceparent VARCHAR(512),
    ADD COLUMN publication_tracestate VARCHAR(512),
    ADD COLUMN publication_generation BIGINT NOT NULL DEFAULT 0;
