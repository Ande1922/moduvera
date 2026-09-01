-- Frozen T04 versioned migration, applied after mysql-v1-baseline.sql.
-- Idempotency is supplied by the shared migration ledger: a second migrate()
-- observes this version as applied and must not execute these statements again.

ALTER TABLE store_location
    ADD COLUMN business_day_cutoff TIME(6) NULL AFTER time_zone_id;

UPDATE store_location
    SET business_day_cutoff = '04:00:00.000000'
    WHERE business_day_cutoff IS NULL;

ALTER TABLE store_location
    MODIFY business_day_cutoff TIME(6) NOT NULL;
