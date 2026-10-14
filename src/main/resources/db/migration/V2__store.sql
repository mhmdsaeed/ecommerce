-- Stores are the tenants (SPEC 4, 5): store.id is the tenant_id carried by every tenant table.
-- Platform table: no tenant_id, no RLS.
CREATE TABLE store
(
    id               UUID                     NOT NULL,
    slug             TEXT                     NOT NULL,
    name             JSONB                    NOT NULL,
    status           TEXT                     NOT NULL,
    plan_code        TEXT,
    default_locale   TEXT                     NOT NULL DEFAULT 'ar',
    base_currency    TEXT                     NOT NULL DEFAULT 'SYP',
    display_currency TEXT                     NOT NULL DEFAULT 'SYP',
    theme            JSONB                    NOT NULL DEFAULT '{}',
    contact_phone    TEXT,
    whatsapp         TEXT,
    created_by       UUID,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT                   NOT NULL,
    CONSTRAINT store_pk PRIMARY KEY (id),
    CONSTRAINT store_slug_uk UNIQUE (slug),
    -- The slug is the storefront subdomain: one lowercase DNS label, 3 to 32 characters.
    CONSTRAINT store_slug_format_ck CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$'),
    CONSTRAINT store_status_ck CHECK (status IN ('TRIAL', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT store_default_locale_ck CHECK (default_locale IN ('ar', 'en')),
    CONSTRAINT store_base_currency_ck CHECK (base_currency IN ('SYP', 'USD')),
    CONSTRAINT store_display_currency_ck CHECK (display_currency IN ('SYP', 'USD')),
    CONSTRAINT store_name_ck CHECK (jsonb_typeof(name) = 'object')
);

COMMENT ON COLUMN store.plan_code IS 'References plan(code) once billing (M12) creates the plan table';
COMMENT ON COLUMN store.created_by IS 'User who created the store; set by merchant signup (M2)';
