CREATE TABLE identity_user (
    user_id VARCHAR(128) PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE identity_tenant_membership (
    user_id VARCHAR(128) NOT NULL REFERENCES identity_user(user_id),
    tenant_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (user_id, tenant_id)
);

CREATE TABLE identity_permission_assignment (
    user_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    permission VARCHAR(160) NOT NULL,
    PRIMARY KEY (user_id, tenant_id, permission),
    FOREIGN KEY (user_id, tenant_id)
        REFERENCES identity_tenant_membership(user_id, tenant_id)
);

CREATE TABLE identity_service (
    service_id VARCHAR(128) PRIMARY KEY,
    secret_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE identity_service_permission (
    service_id VARCHAR(128) NOT NULL REFERENCES identity_service(service_id),
    audience VARCHAR(128) NOT NULL,
    permission VARCHAR(160) NOT NULL,
    PRIMARY KEY (service_id, audience, permission)
);

CREATE TABLE identity_browser_session (
    token_hash CHAR(64) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    issued_at TIMESTAMPTZ(6) NOT NULL,
    expires_at TIMESTAMPTZ(6) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (user_id, tenant_id)
        REFERENCES identity_tenant_membership(user_id, tenant_id)
);

CREATE INDEX identity_session_expiry_idx
    ON identity_browser_session (expires_at)
    WHERE NOT revoked;
