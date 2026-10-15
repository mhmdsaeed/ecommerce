-- Cash on delivery: what was collected and what drivers handed over (SPEC 5, 8, M10).

-- The cash taken for one order, once. driver_id is the driver who holds it until they hand it over, or null when
-- the store collected it itself (no driver), which leaves nothing to settle.
CREATE TABLE cod_collection
(
    id           UUID                     NOT NULL,
    tenant_id    UUID                     NOT NULL,
    order_id     UUID                     NOT NULL,
    driver_id    UUID,
    amount       NUMERIC(19, 2)           NOT NULL,
    currency     TEXT                     NOT NULL,
    collected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    CONSTRAINT cod_collection_pk PRIMARY KEY (id),
    CONSTRAINT cod_collection_order_uk UNIQUE (tenant_id, order_id),
    CONSTRAINT cod_collection_order_fk FOREIGN KEY (tenant_id, order_id) REFERENCES shop_order (tenant_id, id),
    CONSTRAINT cod_collection_driver_fk FOREIGN KEY (tenant_id, driver_id) REFERENCES staff_user (tenant_id, id),
    CONSTRAINT cod_collection_amount_ck CHECK (amount > 0),
    CONSTRAINT cod_collection_currency_ck CHECK (currency IN ('SYP', 'USD'))
);
CREATE INDEX cod_collection_driver_idx ON cod_collection (tenant_id, driver_id, collected_at);
SELECT enable_tenant_rls('cod_collection');

-- Cash a driver handed over to the store, recorded by the staff member who received it. Append-only in practice:
-- a mistake is corrected with another entry, so the history stays readable.
CREATE TABLE cod_remittance
(
    id          UUID                     NOT NULL,
    tenant_id   UUID                     NOT NULL,
    driver_id   UUID                     NOT NULL,
    amount      NUMERIC(19, 2)           NOT NULL,
    currency    TEXT                     NOT NULL,
    remitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_by UUID                     NOT NULL,
    note        TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT cod_remittance_pk PRIMARY KEY (id),
    CONSTRAINT cod_remittance_driver_fk FOREIGN KEY (tenant_id, driver_id) REFERENCES staff_user (tenant_id, id),
    CONSTRAINT cod_remittance_receiver_fk FOREIGN KEY (tenant_id, received_by) REFERENCES staff_user (tenant_id, id),
    CONSTRAINT cod_remittance_amount_ck CHECK (amount > 0),
    CONSTRAINT cod_remittance_currency_ck CHECK (currency IN ('SYP', 'USD'))
);
CREATE INDEX cod_remittance_driver_idx ON cod_remittance (tenant_id, driver_id, remitted_at);
SELECT enable_tenant_rls('cod_remittance');
