-- 2026-09-08-007-store-check-remove-namshi.sql
--
-- Rebuilds the product_listing.store whitelist WITHOUT NAMSHI.
--
-- Background: a Namshi scraper was built and then abandoned when live testing showed the
-- storefront rejects two of the three rotated user agents at the HTTP/2 layer
-- (RST_STREAM), so it would have failed roughly two runs in three. The scraper was never
-- committed, but an earlier NAMSHI-adding variant of this migration had already been
-- applied to the local development database, leaving a value in the constraint that no
-- Store enum member and no code will ever write.
--
-- On a database migrated 001-006 this is a harmless restatement of the same list, because
-- every store-check migration drops and re-adds the whole whitelist rather than amending
-- it. On a database where the abandoned NAMSHI variant was applied, this removes it.
--
-- Keep the list below in step with com.tameem.pricewatch.entity.Store.
--
-- Re-runnable: the DROP is guarded and the ADD follows a DROP of the same name.

BEGIN;

ALTER TABLE product_listing DROP CONSTRAINT IF EXISTS product_listing_store_check;
ALTER TABLE product_listing ADD CONSTRAINT product_listing_store_check
    CHECK (store::text = ANY (ARRAY[
        'AMAZON',
        'EBAY',
        'NEWEGG',
        'BH_PHOTO',
        'CURRYS',
        'JARIR',
        'IKEA',
        'FLIPKART',
        'ALIEXPRESS',
        'WALMART',
        'NOON',
        'CARREFOUR'
    ]::text[]));

COMMIT;
