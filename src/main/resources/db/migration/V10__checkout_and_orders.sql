-- Checkout and orders (SPEC 5, 6, M8).

-- Checkout settings per store (SPEC 6.4): phone verification by OTP, and the largest order a new customer (no
-- completed order yet) may place, in the display currency; null means no limit.
ALTER TABLE store
    ADD COLUMN otp_required           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN new_customer_max_total NUMERIC(19, 2),
    ADD CONSTRAINT store_new_customer_max_total_ck CHECK (new_customer_max_total > 0);

-- How shoppers can pay (SPEC 6.2). A store without a COD row accepts cash on delivery; every other method is off
-- until the merchant enables it with the account to pay into.
CREATE TABLE payment_method_config
(
    id           UUID                     NOT NULL,
    tenant_id    UUID                     NOT NULL,
    method       TEXT                     NOT NULL,
    enabled      BOOLEAN                  NOT NULL,
    display_name JSONB,
    account_id   TEXT,
    account_name TEXT,
    qr_image_key TEXT,
    instructions JSONB,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    CONSTRAINT payment_method_config_pk PRIMARY KEY (id),
    CONSTRAINT payment_method_config_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT payment_method_config_method_uk UNIQUE (tenant_id, method),
    CONSTRAINT payment_method_config_method_ck CHECK (method IN ('COD', 'SYRIATEL_CASH', 'SHAM_CASH', 'MTN_CASH',
        'BANK_TRANSFER'))
);
SELECT enable_tenant_rls('payment_method_config');

-- Phone verification codes (SPEC 6.4). Only hashes are stored. A verified challenge hands out a verification
-- token (hash stored too) that checkout accepts for a short time instead of asking for a code again.
CREATE TABLE otp_challenge
(
    id                 UUID                     NOT NULL,
    tenant_id          UUID                     NOT NULL,
    phone              TEXT                     NOT NULL,
    code_hash          TEXT                     NOT NULL,
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts           INTEGER                  NOT NULL DEFAULT 0,
    verified_at        TIMESTAMP WITH TIME ZONE,
    verification_hash  TEXT,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT                   NOT NULL,
    CONSTRAINT otp_challenge_pk PRIMARY KEY (id),
    CONSTRAINT otp_challenge_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT otp_challenge_verification_uk UNIQUE (tenant_id, verification_hash),
    CONSTRAINT otp_challenge_phone_e164_ck CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT otp_challenge_attempts_ck CHECK (attempts >= 0)
);
CREATE INDEX otp_challenge_phone_idx ON otp_challenge (tenant_id, phone, created_at DESC);
SELECT enable_tenant_rls('otp_challenge');

-- The last order number per store, so numbers run 1001, 1002, ... in each store separately. Bumped with an
-- upsert in the placing transaction; the row lock serializes concurrent orders of one store.
CREATE TABLE order_counter
(
    tenant_id   UUID    NOT NULL,
    last_number INTEGER NOT NULL,
    CONSTRAINT order_counter_pk PRIMARY KEY (tenant_id),
    CONSTRAINT order_counter_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id)
);
SELECT enable_tenant_rls('order_counter');

-- Orders (SPEC 5, 6). Everything a past order shows is a snapshot: totals in the currency charged, the rate used,
-- the address, and (in order_item) the products. Later catalog, price, rate or zone changes never alter it.
-- The tracking link carries a token; only its hash is stored. idempotency_key makes a repeated checkout submit
-- return the first order.
CREATE TABLE shop_order
(
    id                  UUID                     NOT NULL,
    tenant_id           UUID                     NOT NULL,
    number              INTEGER                  NOT NULL,
    tracking_token_hash TEXT                     NOT NULL,
    idempotency_key     UUID                     NOT NULL,
    customer_id         UUID                     NOT NULL,
    customer_name       TEXT                     NOT NULL,
    customer_phone      TEXT                     NOT NULL,
    status              TEXT                     NOT NULL,
    payment_status      TEXT                     NOT NULL,
    payment_method      TEXT                     NOT NULL,
    currency            TEXT                     NOT NULL,
    display_currency    TEXT                     NOT NULL,
    rate_snapshot       NUMERIC(18, 6),
    subtotal            NUMERIC(19, 2)           NOT NULL,
    delivery_fee        NUMERIC(19, 2)           NOT NULL,
    total               NUMERIC(19, 2)           NOT NULL,
    total_display       NUMERIC(19, 2)           NOT NULL,
    address             JSONB                    NOT NULL,
    notes               TEXT,
    zone_id             UUID,
    driver_id           UUID,
    placed_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at        TIMESTAMP WITH TIME ZONE,
    delivered_at        TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL,
    CONSTRAINT shop_order_pk PRIMARY KEY (id),
    CONSTRAINT shop_order_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT shop_order_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT shop_order_number_uk UNIQUE (tenant_id, number),
    CONSTRAINT shop_order_idempotency_uk UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT shop_order_customer_fk FOREIGN KEY (tenant_id, customer_id) REFERENCES customer (tenant_id, id),
    CONSTRAINT shop_order_zone_fk FOREIGN KEY (tenant_id, zone_id)
        REFERENCES delivery_zone (tenant_id, id) ON DELETE SET NULL (zone_id),
    CONSTRAINT shop_order_driver_fk FOREIGN KEY (tenant_id, driver_id) REFERENCES staff_user (tenant_id, id),
    CONSTRAINT shop_order_status_ck CHECK (status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'PREPARING',
        'OUT_FOR_DELIVERY', 'DELIVERED', 'COMPLETED', 'CANCELLED', 'REFUSED', 'RETURNED')),
    CONSTRAINT shop_order_payment_status_ck CHECK (payment_status IN ('UNPAID', 'AWAITING_VERIFICATION', 'PAID',
        'REJECTED')),
    CONSTRAINT shop_order_payment_method_ck CHECK (payment_method IN ('COD', 'SYRIATEL_CASH', 'SHAM_CASH',
        'MTN_CASH', 'BANK_TRANSFER')),
    CONSTRAINT shop_order_currencies_ck CHECK (currency IN ('SYP', 'USD') AND display_currency IN ('SYP', 'USD')),
    CONSTRAINT shop_order_amounts_ck CHECK (number > 0 AND subtotal >= 0 AND delivery_fee >= 0
        AND total = subtotal + delivery_fee AND total_display >= 0 AND rate_snapshot > 0)
);
CREATE INDEX shop_order_placed_idx ON shop_order (tenant_id, placed_at DESC);
CREATE INDEX shop_order_customer_idx ON shop_order (tenant_id, customer_id, status);
SELECT enable_tenant_rls('shop_order');

-- One line per variant. The snapshot keeps name, SKU and options as they were; variant_id is cleared if the
-- variant is deleted later, and the line stays.
CREATE TABLE order_item
(
    id         UUID                     NOT NULL,
    tenant_id  UUID                     NOT NULL,
    order_id   UUID                     NOT NULL,
    position   INTEGER                  NOT NULL,
    variant_id UUID,
    product    JSONB                    NOT NULL,
    unit_price NUMERIC(19, 2)           NOT NULL,
    quantity   INTEGER                  NOT NULL,
    line_total NUMERIC(19, 2)           NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT order_item_pk PRIMARY KEY (id),
    CONSTRAINT order_item_order_fk FOREIGN KEY (tenant_id, order_id) REFERENCES shop_order (tenant_id, id),
    CONSTRAINT order_item_variant_fk FOREIGN KEY (tenant_id, variant_id)
        REFERENCES product_variant (tenant_id, id) ON DELETE SET NULL (variant_id),
    CONSTRAINT order_item_amounts_ck CHECK (quantity > 0 AND unit_price >= 0 AND line_total = unit_price * quantity)
);
CREATE INDEX order_item_order_idx ON order_item (tenant_id, order_id, position);
SELECT enable_tenant_rls('order_item');

-- What happened to an order, append-only (SPEC 5, 6.1): every status change, and other facts such as a submitted
-- payment proof. position numbers an order's events from 0 (timestamps can tie); writers hold the order's row lock.
-- The trigger refuses updates and deletes, whoever asks.
CREATE TABLE order_event
(
    id          UUID                     NOT NULL,
    tenant_id   UUID                     NOT NULL,
    order_id    UUID                     NOT NULL,
    position    INTEGER                  NOT NULL,
    type        TEXT                     NOT NULL,
    from_status TEXT,
    to_status   TEXT,
    actor       TEXT                     NOT NULL,
    payload     JSONB,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT order_event_pk PRIMARY KEY (id),
    CONSTRAINT order_event_order_fk FOREIGN KEY (tenant_id, order_id) REFERENCES shop_order (tenant_id, id),
    CONSTRAINT order_event_position_uk UNIQUE (tenant_id, order_id, position)
);
SELECT enable_tenant_rls('order_event');

CREATE FUNCTION order_event_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'order_event is append-only';
END;
$$;

CREATE TRIGGER order_event_append_only
    BEFORE UPDATE OR DELETE ON order_event
    FOR EACH ROW EXECUTE FUNCTION order_event_append_only();

-- What a shopper sent as proof of a wallet or bank payment (SPEC 6.2); the merchant reviews it (M9).
CREATE TABLE payment_proof
(
    id              UUID                     NOT NULL,
    tenant_id       UUID                     NOT NULL,
    order_id        UUID                     NOT NULL,
    method          TEXT                     NOT NULL,
    transaction_ref TEXT,
    image_key       TEXT,
    submitted_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    status          TEXT                     NOT NULL,
    reviewed_by     UUID,
    review_note     TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL,
    CONSTRAINT payment_proof_pk PRIMARY KEY (id),
    CONSTRAINT payment_proof_order_fk FOREIGN KEY (tenant_id, order_id) REFERENCES shop_order (tenant_id, id),
    CONSTRAINT payment_proof_reviewer_fk FOREIGN KEY (tenant_id, reviewed_by) REFERENCES staff_user (tenant_id, id),
    CONSTRAINT payment_proof_status_ck CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT payment_proof_given_ck CHECK (transaction_ref IS NOT NULL OR image_key IS NOT NULL)
);
CREATE INDEX payment_proof_order_idx ON payment_proof (tenant_id, order_id, submitted_at);
SELECT enable_tenant_rls('payment_proof');
