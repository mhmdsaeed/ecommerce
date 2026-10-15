-- Merchant order management (SPEC 9.3, M9).

-- When a proof was reviewed; reviewed_by and review_note exist since V10.
ALTER TABLE payment_proof
    ADD COLUMN reviewed_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT payment_proof_reviewed_ck CHECK ((status = 'PENDING') = (reviewed_at IS NULL));

-- The order list filters by status and payment status, newest first.
CREATE INDEX shop_order_status_idx ON shop_order (tenant_id, status, placed_at DESC);
CREATE INDEX shop_order_payment_status_idx ON shop_order (tenant_id, payment_status, placed_at DESC);
-- Orders assigned to a driver (the driver's list, M10).
CREATE INDEX shop_order_driver_idx ON shop_order (tenant_id, driver_id, status);
