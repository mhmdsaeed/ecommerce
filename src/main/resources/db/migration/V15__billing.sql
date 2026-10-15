-- SaaS billing (SPEC 5, 8, M12): plans, one subscription per store, invoices and manual payments.
-- Platform tables: no tenant_id, no RLS. Merchants only ever see their own store's rows, filtered by the billing
-- module; the platform admin sees everything.

CREATE TABLE plan
(
    id                UUID                     NOT NULL,
    code              TEXT                     NOT NULL,
    name              JSONB                    NOT NULL,
    monthly_price_usd NUMERIC(19, 2)           NOT NULL,
    -- {"products": 100, "staff": 3, "ordersPerMonth": 300}; a missing key means no limit.
    limits            JSONB                    NOT NULL DEFAULT '{}',
    active            BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT                   NOT NULL,
    CONSTRAINT plan_pk PRIMARY KEY (id),
    CONSTRAINT plan_code_uk UNIQUE (code),
    CONSTRAINT plan_code_format_ck CHECK (code ~ '^[a-z0-9][a-z0-9-]{0,30}$'),
    CONSTRAINT plan_name_ck CHECK (jsonb_typeof(name) = 'object'),
    CONSTRAINT plan_price_ck CHECK (monthly_price_usd >= 0),
    CONSTRAINT plan_limits_ck CHECK (jsonb_typeof(limits) = 'object')
);

-- Starting plans; the platform admin edits prices and limits.
INSERT INTO plan (id, code, name, monthly_price_usd, limits, created_at, updated_at, version)
VALUES (gen_random_uuid(), 'basic', '{"ar": "الأساسية", "en": "Basic"}', 10.00,
        '{"products": 100, "staff": 3, "ordersPerMonth": 300}', now(), now(), 0),
       (gen_random_uuid(), 'pro', '{"ar": "الاحترافية", "en": "Pro"}', 25.00,
        '{"products": 1000, "staff": 10, "ordersPerMonth": 3000}', now(), now(), 0);

-- Periods and deadlines are calendar days in Syrian time (SPEC 18): the daily job compares them with today there.
CREATE TABLE subscription
(
    id                   UUID                     NOT NULL,
    store_id             UUID                     NOT NULL,
    plan_code            TEXT                     NOT NULL,
    status               TEXT                     NOT NULL,
    current_period_start DATE                     NOT NULL,
    current_period_end   DATE                     NOT NULL,
    grace_until          DATE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT                   NOT NULL,
    CONSTRAINT subscription_pk PRIMARY KEY (id),
    CONSTRAINT subscription_store_uk UNIQUE (store_id),
    CONSTRAINT subscription_store_fk FOREIGN KEY (store_id) REFERENCES store (id),
    CONSTRAINT subscription_plan_fk FOREIGN KEY (plan_code) REFERENCES plan (code),
    CONSTRAINT subscription_status_ck CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')),
    CONSTRAINT subscription_period_ck CHECK (current_period_end > current_period_start)
);
CREATE INDEX subscription_period_end_idx ON subscription (current_period_end);

-- Invoice numbers are platform-wide and never reused, so a merchant can quote one in a transfer note.
CREATE SEQUENCE invoice_number_seq START WITH 1001;

CREATE TABLE invoice
(
    id           UUID                     NOT NULL,
    store_id     UUID                     NOT NULL,
    number       BIGINT                   NOT NULL,
    description  TEXT                     NOT NULL,
    plan_code    TEXT,
    amount       NUMERIC(19, 2)           NOT NULL,
    currency     TEXT                     NOT NULL,
    status       TEXT                     NOT NULL,
    issued_on    DATE                     NOT NULL,
    due_date     DATE                     NOT NULL,
    -- The subscription month it bills; null for invoices the platform admin writes by hand.
    period_start DATE,
    period_end   DATE,
    paid_at      TIMESTAMP WITH TIME ZONE,
    voided_at    TIMESTAMP WITH TIME ZONE,
    void_reason  TEXT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    CONSTRAINT invoice_pk PRIMARY KEY (id),
    CONSTRAINT invoice_number_uk UNIQUE (number),
    -- A month is billed once, however often the job runs.
    CONSTRAINT invoice_period_uk UNIQUE (store_id, period_start),
    CONSTRAINT invoice_store_fk FOREIGN KEY (store_id) REFERENCES store (id),
    CONSTRAINT invoice_amount_ck CHECK (amount > 0),
    CONSTRAINT invoice_currency_ck CHECK (currency IN ('SYP', 'USD')),
    CONSTRAINT invoice_status_ck CHECK (status IN ('OPEN', 'PAID', 'VOID')),
    CONSTRAINT invoice_period_ck CHECK ((period_start IS NULL) = (period_end IS NULL))
);
CREATE INDEX invoice_store_idx ON invoice (store_id, issued_on);
CREATE INDEX invoice_status_idx ON invoice (status, due_date);

-- Money received for an invoice, recorded by a platform admin (SPEC 8: no payment gateway).
CREATE TABLE invoice_payment
(
    id          UUID                     NOT NULL,
    invoice_id  UUID                     NOT NULL,
    method      TEXT                     NOT NULL,
    reference   TEXT,
    amount      NUMERIC(19, 2)           NOT NULL,
    recorded_by TEXT                     NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT invoice_payment_pk PRIMARY KEY (id),
    CONSTRAINT invoice_payment_invoice_fk FOREIGN KEY (invoice_id) REFERENCES invoice (id),
    CONSTRAINT invoice_payment_method_ck CHECK (method IN ('WALLET', 'BANK', 'CASH')),
    CONSTRAINT invoice_payment_amount_ck CHECK (amount > 0)
);
CREATE INDEX invoice_payment_invoice_idx ON invoice_payment (invoice_id, recorded_at);

-- The subscription holds the plan; store.plan_code was a placeholder for it (V2).
ALTER TABLE store DROP COLUMN plan_code;

-- Sanctions and KYC screening, recorded when the platform admin activates a store (SPEC 9.4), and why a store
-- is suspended. suspension_kind tells billing's suspensions (lifted by payment) from the platform admin's own; a
-- suspended store without one counts as suspended by hand.
ALTER TABLE store
    ADD COLUMN screened_at       TIMESTAMP WITH TIME ZONE,
    ADD COLUMN screened_by       TEXT,
    ADD COLUMN screening_notes   TEXT,
    ADD COLUMN suspension_kind   TEXT,
    ADD COLUMN suspension_reason TEXT,
    ADD CONSTRAINT store_suspension_kind_ck CHECK (suspension_kind IN ('BILLING', 'MANUAL'));
