-- Pricing (SPEC 7).

-- How converted Syrian pound prices are rounded, and which digits the store shows numbers in. Defaults: round
-- conversions to the nearest 5 pounds (unrounded conversions show odd amounts like 532.13), Western digits.
ALTER TABLE store
    ADD COLUMN rounding_rule TEXT NOT NULL DEFAULT 'NEAREST_5',
    ADD COLUMN number_digits TEXT NOT NULL DEFAULT 'WESTERN',
    ADD CONSTRAINT store_rounding_rule_ck CHECK (rounding_rule IN ('NONE', 'NEAREST_5', 'NEAREST_10', 'NEAREST_100')),
    ADD CONSTRAINT store_number_digits_ck CHECK (number_digits IN ('WESTERN', 'ARABIC_INDIC'));

-- Lets other tenant tables reference staff with a composite (tenant_id, staff) foreign key.
ALTER TABLE staff_user ADD CONSTRAINT staff_user_tenant_id_uk UNIQUE (tenant_id, id);

-- The merchant's exchange rates, append-only: a new rate is a new row, so history is kept and orders can
-- snapshot the rate they used. rate = units of to_currency per 1 from_currency (for USD -> SYP: pounds per dollar).
CREATE TABLE exchange_rate
(
    id            UUID                     NOT NULL,
    tenant_id     UUID                     NOT NULL,
    from_currency TEXT                     NOT NULL,
    to_currency   TEXT                     NOT NULL,
    rate          NUMERIC(18, 6)           NOT NULL,
    effective_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    set_by        UUID,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    CONSTRAINT exchange_rate_pk PRIMARY KEY (id),
    CONSTRAINT exchange_rate_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT exchange_rate_set_by_fk FOREIGN KEY (tenant_id, set_by) REFERENCES staff_user (tenant_id, id),
    CONSTRAINT exchange_rate_currencies_ck CHECK (from_currency IN ('SYP', 'USD') AND to_currency IN ('SYP', 'USD')
        AND from_currency <> to_currency),
    CONSTRAINT exchange_rate_rate_ck CHECK (rate > 0)
);
CREATE INDEX exchange_rate_current_idx ON exchange_rate (tenant_id, from_currency, to_currency, effective_at DESC);
SELECT enable_tenant_rls('exchange_rate');
