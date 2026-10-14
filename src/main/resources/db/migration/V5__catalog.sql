-- Catalog (SPEC 5): categories, products, options, variants, images. All tenant tables.
--
-- References between tenant tables use composite foreign keys on (tenant_id, <parent>_id) against a
-- UNIQUE (tenant_id, id) on the parent, so the database itself rejects a row that points into another store,
-- whatever the application does.

CREATE TABLE category
(
    id         UUID                     NOT NULL,
    tenant_id  UUID                     NOT NULL,
    name       JSONB                    NOT NULL,
    slug       TEXT                     NOT NULL,
    parent_id  UUID,
    sort_order INTEGER                  NOT NULL DEFAULT 0,
    active     BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT category_pk PRIMARY KEY (id),
    CONSTRAINT category_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT category_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT category_slug_uk UNIQUE (tenant_id, slug),
    CONSTRAINT category_parent_fk FOREIGN KEY (tenant_id, parent_id) REFERENCES category (tenant_id, id),
    CONSTRAINT category_not_own_parent_ck CHECK (parent_id <> id),
    CONSTRAINT category_name_ck CHECK (jsonb_typeof(name) = 'object')
);
CREATE INDEX category_parent_idx ON category (tenant_id, parent_id);
SELECT enable_tenant_rls('category');

CREATE TABLE product
(
    id             UUID                     NOT NULL,
    tenant_id      UUID                     NOT NULL,
    name           JSONB                    NOT NULL,
    description    JSONB,
    slug           TEXT                     NOT NULL,
    category_id    UUID,
    status         TEXT                     NOT NULL DEFAULT 'DRAFT',
    price_currency TEXT                     NOT NULL,
    -- Admin search; storefront search gets a trigram index on it (M5).
    search_text    TEXT GENERATED ALWAYS AS (
        lower(coalesce(name ->> 'ar', '') || ' ' || coalesce(name ->> 'en', '') || ' ' || slug)) STORED,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    version        BIGINT                   NOT NULL,
    CONSTRAINT product_pk PRIMARY KEY (id),
    CONSTRAINT product_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT product_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT product_slug_uk UNIQUE (tenant_id, slug),
    CONSTRAINT product_category_fk FOREIGN KEY (tenant_id, category_id) REFERENCES category (tenant_id, id),
    CONSTRAINT product_status_ck CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT product_price_currency_ck CHECK (price_currency IN ('SYP', 'USD')),
    CONSTRAINT product_name_ck CHECK (jsonb_typeof(name) = 'object')
);
CREATE INDEX product_category_idx ON product (tenant_id, category_id);
CREATE INDEX product_updated_idx ON product (tenant_id, updated_at DESC);
SELECT enable_tenant_rls('product');

-- Up to three options per product (e.g. Size, Colour); choices is a JSON array of {"ar", "en"} labels.
CREATE TABLE product_option
(
    id         UUID                     NOT NULL,
    tenant_id  UUID                     NOT NULL,
    product_id UUID                     NOT NULL,
    position   INTEGER                  NOT NULL,
    name       JSONB                    NOT NULL,
    choices    JSONB                    NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT product_option_pk PRIMARY KEY (id),
    CONSTRAINT product_option_product_fk FOREIGN KEY (tenant_id, product_id)
        REFERENCES product (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT product_option_position_uk UNIQUE (tenant_id, product_id, position),
    CONSTRAINT product_option_position_ck CHECK (position BETWEEN 1 AND 3),
    CONSTRAINT product_option_choices_ck CHECK (jsonb_typeof(choices) = 'array')
);
SELECT enable_tenant_rls('product_option');

-- Every product has at least one variant; a product without options has one with option_values = [].
-- option_values holds one label per option, in option order. Prices are in the product's price_currency.
CREATE TABLE product_variant
(
    id               UUID                     NOT NULL,
    tenant_id        UUID                     NOT NULL,
    product_id       UUID                     NOT NULL,
    position         INTEGER                  NOT NULL,
    sku              TEXT,
    option_values    JSONB                    NOT NULL DEFAULT '[]',
    price            NUMERIC(19, 2)           NOT NULL,
    compare_at_price NUMERIC(19, 2),
    active           BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT                   NOT NULL,
    CONSTRAINT product_variant_pk PRIMARY KEY (id),
    CONSTRAINT product_variant_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT product_variant_product_fk FOREIGN KEY (tenant_id, product_id)
        REFERENCES product (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT product_variant_sku_uk UNIQUE (tenant_id, sku),
    CONSTRAINT product_variant_options_uk UNIQUE (tenant_id, product_id, option_values),
    CONSTRAINT product_variant_price_ck CHECK (price >= 0),
    CONSTRAINT product_variant_compare_at_ck CHECK (compare_at_price IS NULL OR compare_at_price > price),
    CONSTRAINT product_variant_option_values_ck CHECK (jsonb_typeof(option_values) = 'array')
);
SELECT enable_tenant_rls('product_variant');

-- storage_key points at the media module's WebP renditions. variant_id optionally ties a photo to one variant.
CREATE TABLE product_image
(
    id          UUID                     NOT NULL,
    tenant_id   UUID                     NOT NULL,
    product_id  UUID                     NOT NULL,
    variant_id  UUID,
    storage_key TEXT                     NOT NULL,
    width       INTEGER                  NOT NULL,
    height      INTEGER                  NOT NULL,
    sort_order  INTEGER                  NOT NULL,
    alt         JSONB,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT product_image_pk PRIMARY KEY (id),
    CONSTRAINT product_image_product_fk FOREIGN KEY (tenant_id, product_id)
        REFERENCES product (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT product_image_variant_fk FOREIGN KEY (tenant_id, variant_id)
        REFERENCES product_variant (tenant_id, id) ON DELETE SET NULL (variant_id),
    CONSTRAINT product_image_key_uk UNIQUE (tenant_id, storage_key),
    CONSTRAINT product_image_size_ck CHECK (width > 0 AND height > 0)
);
CREATE INDEX product_image_product_idx ON product_image (tenant_id, product_id, sort_order);
SELECT enable_tenant_rls('product_image');
