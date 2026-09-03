DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM catalog_product WHERE char_length(tenant_id) > 64) THEN
        RAISE EXCEPTION 'catalog tenant_id exceeds 64 characters; refusing to narrow persistence contract';
    END IF;
END $$;

ALTER TABLE catalog_product
    ALTER COLUMN tenant_id TYPE VARCHAR(64);
