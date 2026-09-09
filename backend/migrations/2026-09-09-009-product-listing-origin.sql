-- 2026-09-09-009-product-listing-origin.sql
--
-- Adds product_listing.origin: how a listing came to be attached.
--
-- Three values, because two of them are auto-attached but differ in confidence:
--   USER_SUBMITTED        the URL the user pasted
--   SIBLING_MARKETPLACE   same ASIN on another Amazon storefront — exact by construction
--   CROSS_STORE_DISCOVERY found by search plus an LLM attribute gate — inferred
--
-- Only the third is surfaced to the user as "found for you": it is the one that involved a
-- judgement call, so it is the one worth being able to see and distrust.
--
-- Applied ahead of the code change on purpose. The field is NOT NULL, and ddl-auto=update
-- cannot add a NOT NULL column to a table that already has rows — so the column is created
-- nullable, backfilled, and only then constrained. Every pre-existing listing predates
-- discovery, so USER_SUBMITTED is the truthful backfill.
--
-- Re-runnable: the ADD is guarded and the constraint is dropped before being re-added.

BEGIN;

ALTER TABLE product_listing ADD COLUMN IF NOT EXISTS origin VARCHAR(32);

UPDATE product_listing SET origin = 'USER_SUBMITTED' WHERE origin IS NULL;

ALTER TABLE product_listing ALTER COLUMN origin SET NOT NULL;

ALTER TABLE product_listing DROP CONSTRAINT IF EXISTS product_listing_origin_check;
ALTER TABLE product_listing ADD CONSTRAINT product_listing_origin_check
    CHECK (origin::text = ANY (ARRAY[
        'USER_SUBMITTED',
        'SIBLING_MARKETPLACE',
        'CROSS_STORE_DISCOVERY'
    ]::text[]));

COMMIT;
