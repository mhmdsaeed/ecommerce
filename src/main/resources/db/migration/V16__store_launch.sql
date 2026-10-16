-- Going live (SPEC 9.3 setup wizard, M13). A new store is set up first and opens to shoppers when its owner
-- launches it; until then its storefront says "opening soon", except through the owner's preview link.
ALTER TABLE store
    ADD COLUMN launched_at   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN preview_token TEXT;

-- Stores from before the wizard are already open.
UPDATE store SET launched_at = created_at;

-- 64 hex characters from two random UUIDs (gen_random_uuid draws from a strong random source).
UPDATE store
SET preview_token = replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');

ALTER TABLE store
    ALTER COLUMN preview_token SET DEFAULT replace(gen_random_uuid()::text, '-', '')
        || replace(gen_random_uuid()::text, '-', ''),
    ALTER COLUMN preview_token SET NOT NULL;
