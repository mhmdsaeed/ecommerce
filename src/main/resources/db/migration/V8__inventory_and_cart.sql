-- Inventory and carts (SPEC 5, M6). All tenant tables.

-- Stock per variant. A variant without a row is not tracked: it can always be bought. reserved counts units held
-- by open orders (from M8); available = on_hand - reserved. The checks are the last line of defence: whatever
-- the application does, stock never goes negative and never reserves more than is on hand.
CREATE TABLE stock_level
(
    id         UUID                     NOT NULL,
    tenant_id  UUID                     NOT NULL,
    variant_id UUID                     NOT NULL,
    on_hand    INTEGER                  NOT NULL,
    reserved   INTEGER                  NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT stock_level_pk PRIMARY KEY (id),
    CONSTRAINT stock_level_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT stock_level_variant_uk UNIQUE (tenant_id, variant_id),
    CONSTRAINT stock_level_variant_fk FOREIGN KEY (tenant_id, variant_id)
        REFERENCES product_variant (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT stock_level_on_hand_ck CHECK (on_hand >= 0),
    CONSTRAINT stock_level_reserved_ck CHECK (reserved >= 0 AND reserved <= on_hand)
);
SELECT enable_tenant_rls('stock_level');

-- A shopper's cart, found by a random token in a cookie; session_key is the token's SHA-256, so the table alone
-- does not let anyone take over carts. Customer carts (after OTP login) arrive with checkout (M8). Prices are not
-- stored: the cart shows current prices until an order snapshots them.
CREATE TABLE cart
(
    id          UUID                     NOT NULL,
    tenant_id   UUID                     NOT NULL,
    session_key TEXT                     NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT cart_pk PRIMARY KEY (id),
    CONSTRAINT cart_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT cart_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT cart_session_key_uk UNIQUE (tenant_id, session_key)
);
CREATE INDEX cart_expires_idx ON cart (tenant_id, expires_at);
SELECT enable_tenant_rls('cart');

-- One line per variant. Deleting a variant (redefined options) drops it from carts.
CREATE TABLE cart_item
(
    id         UUID                     NOT NULL,
    tenant_id  UUID                     NOT NULL,
    cart_id    UUID                     NOT NULL,
    variant_id UUID                     NOT NULL,
    quantity   INTEGER                  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT cart_item_pk PRIMARY KEY (id),
    CONSTRAINT cart_item_cart_fk FOREIGN KEY (tenant_id, cart_id) REFERENCES cart (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT cart_item_variant_fk FOREIGN KEY (tenant_id, variant_id)
        REFERENCES product_variant (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT cart_item_variant_uk UNIQUE (tenant_id, cart_id, variant_id),
    CONSTRAINT cart_item_quantity_ck CHECK (quantity BETWEEN 1 AND 99)
);
CREATE INDEX cart_item_variant_idx ON cart_item (tenant_id, variant_id);
SELECT enable_tenant_rls('cart_item');
