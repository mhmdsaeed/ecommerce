-- Delivery zones, customers and their addresses (SPEC 5, 8, M7). All tenant tables.

-- Where the store delivers and for how much. A zone covers a whole governorate (no city), a city (no area) or one
-- area of a city; the most specific active zone matching an address wins. city_key and area_key are the names
-- folded for matching (case, hamza forms, ta marbuta, diacritics, spacing), set by the application, so one
-- destination has one zone whatever the spelling. fee 0 is free delivery; min_order and free_over are optional and
-- in the zone's currency.
CREATE TABLE delivery_zone
(
    id          UUID                     NOT NULL,
    tenant_id   UUID                     NOT NULL,
    governorate TEXT                     NOT NULL,
    city        TEXT,
    city_key    TEXT,
    area        TEXT,
    area_key    TEXT,
    fee         NUMERIC(19, 2)           NOT NULL,
    currency    TEXT                     NOT NULL,
    min_order   NUMERIC(19, 2),
    free_over   NUMERIC(19, 2),
    eta_text    JSONB,
    active      BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT delivery_zone_pk PRIMARY KEY (id),
    CONSTRAINT delivery_zone_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT delivery_zone_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT delivery_zone_destination_uk UNIQUE NULLS NOT DISTINCT (tenant_id, governorate, city_key, area_key),
    CONSTRAINT delivery_zone_governorate_ck CHECK (governorate IN ('DAMASCUS', 'RIF_DIMASHQ', 'ALEPPO', 'HOMS',
        'HAMA', 'LATAKIA', 'TARTUS', 'IDLIB', 'DARAA', 'AS_SUWAYDA', 'QUNEITRA', 'DEIR_EZ_ZOR', 'AL_HASAKAH',
        'RAQQA')),
    CONSTRAINT delivery_zone_city_ck CHECK ((city IS NULL) = (city_key IS NULL) AND city_key <> ''),
    CONSTRAINT delivery_zone_area_ck CHECK ((area IS NULL) = (area_key IS NULL) AND area_key <> ''
        AND (area IS NULL OR city IS NOT NULL)),
    CONSTRAINT delivery_zone_currency_ck CHECK (currency IN ('SYP', 'USD')),
    CONSTRAINT delivery_zone_amounts_ck CHECK (fee >= 0 AND (min_order IS NULL OR min_order > 0)
        AND (free_over IS NULL OR free_over > 0))
);
SELECT enable_tenant_rls('delivery_zone');

-- Shoppers, known by phone within a store (SPEC 5). Created at checkout or OTP login (M8). The counters feed the
-- new-customer limits and the refusal history (SPEC 6.4).
CREATE TABLE customer
(
    id            UUID                     NOT NULL,
    tenant_id     UUID                     NOT NULL,
    phone         TEXT                     NOT NULL,
    name          TEXT,
    blocked       BOOLEAN                  NOT NULL DEFAULT FALSE,
    block_reason  TEXT,
    order_count   INTEGER                  NOT NULL DEFAULT 0,
    refused_count INTEGER                  NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    CONSTRAINT customer_pk PRIMARY KEY (id),
    CONSTRAINT customer_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT customer_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT customer_phone_uk UNIQUE (tenant_id, phone),
    CONSTRAINT customer_phone_e164_ck CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT customer_counts_ck CHECK (order_count >= 0 AND refused_count >= 0)
);
SELECT enable_tenant_rls('customer');

-- A customer's saved delivery addresses, in the governorate / city / area terms zones match on. Syrian addresses
-- lean on landmarks ("near the Al-Nour pharmacy"), so landmark and details are both free text; at least one is
-- required. phone is an optional contact for this address (someone else receiving the order).
CREATE TABLE customer_address
(
    id          UUID                     NOT NULL,
    tenant_id   UUID                     NOT NULL,
    customer_id UUID                     NOT NULL,
    governorate TEXT                     NOT NULL,
    city        TEXT                     NOT NULL,
    area        TEXT,
    landmark    TEXT,
    details     TEXT,
    lat         NUMERIC(9, 6),
    lng         NUMERIC(9, 6),
    phone       TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT customer_address_pk PRIMARY KEY (id),
    CONSTRAINT customer_address_customer_fk FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customer (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT customer_address_governorate_ck CHECK (governorate IN ('DAMASCUS', 'RIF_DIMASHQ', 'ALEPPO', 'HOMS',
        'HAMA', 'LATAKIA', 'TARTUS', 'IDLIB', 'DARAA', 'AS_SUWAYDA', 'QUNEITRA', 'DEIR_EZ_ZOR', 'AL_HASAKAH',
        'RAQQA')),
    CONSTRAINT customer_address_city_ck CHECK (city <> ''),
    CONSTRAINT customer_address_described_ck CHECK (landmark IS NOT NULL OR details IS NOT NULL),
    CONSTRAINT customer_address_location_ck CHECK ((lat IS NULL) = (lng IS NULL)
        AND lat BETWEEN -90 AND 90 AND lng BETWEEN -180 AND 180),
    CONSTRAINT customer_address_phone_e164_ck CHECK (phone ~ '^\+[1-9][0-9]{7,14}$')
);
CREATE INDEX customer_address_customer_idx ON customer_address (tenant_id, customer_id);
SELECT enable_tenant_rls('customer_address');
