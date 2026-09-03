DO $$
BEGIN
    IF EXISTS (
        SELECT tenant_id FROM moduvera_message_outbox WHERE char_length(tenant_id) > 64
        UNION ALL
        SELECT tenant_id FROM moduvera_message_inbox WHERE char_length(tenant_id) > 64
    ) THEN
        RAISE EXCEPTION 'messaging tenant_id exceeds 64 characters; refusing to narrow persistence contract';
    END IF;
END $$;

ALTER TABLE moduvera_message_outbox
    ALTER COLUMN tenant_id TYPE VARCHAR(64);
ALTER TABLE moduvera_message_inbox
    ALTER COLUMN tenant_id TYPE VARCHAR(64);
