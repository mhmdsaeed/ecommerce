-- Identity (SPEC 5, 10).

-- Platform administrators: the SaaS operators. Platform table, no tenant_id, no RLS.
CREATE TABLE platform_user
(
    id            UUID                     NOT NULL,
    email         TEXT                     NOT NULL,
    password_hash TEXT                     NOT NULL,
    role          TEXT                     NOT NULL,
    active        BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    CONSTRAINT platform_user_pk PRIMARY KEY (id),
    CONSTRAINT platform_user_email_uk UNIQUE (email),
    CONSTRAINT platform_user_email_lowercase_ck CHECK (email = lower(email)),
    CONSTRAINT platform_user_role_ck CHECK (role IN ('PLATFORM_ADMIN'))
);

-- A store's staff: owner, managers, staff and drivers. Tenant table.
-- Staff log in with the store slug plus email or phone, so both are unique per store, not globally.
-- Emails are stored lowercase and phones in E.164; the application normalizes both.
CREATE TABLE staff_user
(
    id            UUID                     NOT NULL,
    tenant_id     UUID                     NOT NULL,
    name          TEXT                     NOT NULL,
    email         TEXT,
    phone         TEXT,
    password_hash TEXT                     NOT NULL,
    role          TEXT                     NOT NULL,
    active        BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    CONSTRAINT staff_user_pk PRIMARY KEY (id),
    CONSTRAINT staff_user_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT staff_user_email_uk UNIQUE (tenant_id, email),
    CONSTRAINT staff_user_phone_uk UNIQUE (tenant_id, phone),
    CONSTRAINT staff_user_login_ck CHECK (email IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT staff_user_email_lowercase_ck CHECK (email = lower(email)),
    CONSTRAINT staff_user_phone_e164_ck CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT staff_user_role_ck CHECK (role IN ('OWNER', 'MANAGER', 'STAFF', 'DRIVER')),
    CONSTRAINT staff_user_name_ck CHECK (length(btrim(name)) > 0)
);

SELECT enable_tenant_rls('staff_user');

COMMENT ON COLUMN store.created_by IS 'staff_user.id of the owner who signed the store up';
