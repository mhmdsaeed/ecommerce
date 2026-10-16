-- Staff passwords after signup (deferred from M2): owners and managers set a new password for staff, everyone can
-- change their own, and a forgotten one is reset through a link sent by email or text.

-- Goes up with every password change. Sessions remember the version they logged in with and end when it moves on,
-- so a new password locks out whoever knew the old one.
ALTER TABLE staff_user ADD COLUMN credentials_version INTEGER NOT NULL DEFAULT 0;

-- One row per reset link. Only the token's SHA-256 is kept, so the table does not hold usable links. A link works
-- once, until expires_at.
CREATE TABLE password_reset
(
    id         UUID                     NOT NULL,
    tenant_id  UUID                     NOT NULL,
    staff_id   UUID                     NOT NULL,
    token_hash TEXT                     NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at    TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT password_reset_pk PRIMARY KEY (id),
    CONSTRAINT password_reset_staff_fk FOREIGN KEY (tenant_id, staff_id) REFERENCES staff_user (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT password_reset_token_uk UNIQUE (tenant_id, token_hash),
    CONSTRAINT password_reset_token_ck CHECK (token_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX password_reset_staff_idx ON password_reset (tenant_id, staff_id);
SELECT enable_tenant_rls('password_reset');
