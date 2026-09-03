DO $$
BEGIN
    IF EXISTS (
        SELECT tenant_id FROM identity_tenant_membership WHERE char_length(tenant_id) > 64
        UNION ALL
        SELECT tenant_id FROM identity_permission_assignment WHERE char_length(tenant_id) > 64
        UNION ALL
        SELECT tenant_id FROM identity_browser_session WHERE char_length(tenant_id) > 64
    ) THEN
        RAISE EXCEPTION 'identity tenant_id exceeds 64 characters; refusing to narrow persistence contract';
    END IF;
END $$;

ALTER TABLE identity_permission_assignment
    DROP CONSTRAINT identity_permission_assignment_user_id_tenant_id_fkey;
ALTER TABLE identity_browser_session
    DROP CONSTRAINT identity_browser_session_user_id_tenant_id_fkey;

ALTER TABLE identity_tenant_membership
    ALTER COLUMN tenant_id TYPE VARCHAR(64);
ALTER TABLE identity_permission_assignment
    ALTER COLUMN tenant_id TYPE VARCHAR(64);
ALTER TABLE identity_browser_session
    ALTER COLUMN tenant_id TYPE VARCHAR(64);

ALTER TABLE identity_permission_assignment
    ADD CONSTRAINT identity_permission_assignment_user_id_tenant_id_fkey
        FOREIGN KEY (user_id, tenant_id)
        REFERENCES identity_tenant_membership(user_id, tenant_id);
ALTER TABLE identity_browser_session
    ADD CONSTRAINT identity_browser_session_user_id_tenant_id_fkey
        FOREIGN KEY (user_id, tenant_id)
        REFERENCES identity_tenant_membership(user_id, tenant_id);
