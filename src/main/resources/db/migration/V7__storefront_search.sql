-- Storefront search (SPEC 9.2): substring search on product names with a trigram index, over text normalized so
-- Arabic spelling variants match: hamza forms of alef become alef, alef maksura becomes ya, ta marbuta becomes ha,
-- and diacritics (tashkeel) and tatweel are dropped. ProductSearch in the catalog module normalizes queries the
-- same way; keep the two in sync.
--
-- pg_trgm is a trusted extension, so the database owner (app_owner, who runs Flyway) may create it.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE product DROP COLUMN search_text;
ALTER TABLE product ADD COLUMN search_text TEXT GENERATED ALWAYS AS (
    translate(
        regexp_replace(
            lower(coalesce(name ->> 'ar', '') || ' ' || coalesce(name ->> 'en', '') || ' ' || slug),
            '[ً-ٰٟـ]', '', 'g'),
        'أإآٱىة', 'اااايه')) STORED;

CREATE INDEX product_search_trgm_idx ON product USING gin (search_text gin_trgm_ops);
