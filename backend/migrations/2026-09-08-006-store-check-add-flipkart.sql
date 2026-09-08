-- 2026-09-08-006-store-check-add-flipkart.sql
--
-- Adds FLIPKART to the product_listing.store whitelist.
--
-- ddl-auto=update never revisits an existing CHECK, so every new Store enum value needs a
-- migration like this one or the first INSERT for that retailer fails after a successful
-- scrape. Keep the list below in step with com.tameem.pricewatch.entity.Store.
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
