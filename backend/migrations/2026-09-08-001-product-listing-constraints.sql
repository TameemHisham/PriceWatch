-- 2026-09-08-001-product-listing-constraints.sql
--
-- Realigns the two CHECK constraints on product_listing so storefronts other than
-- Amazon can be tracked at all. Hibernate's ddl-auto=update creates tables and columns
-- but never revisits a constraint it generated earlier, so neither of these can fix
-- itself — without this migration, tracking a Newegg or B&H URL fails at INSERT with
-- "violates check constraint", after a successful scrape.
--
-- Re-runnable: every DROP is guarded, and the ADD follows a DROP of the same name.

BEGIN;

-- 1. Drop the marketplace whitelist outright, rather than extending it.
--
-- product_listing.marketplace is a plain String column on the entity (@Column, not
-- @Enumerated) — it holds configured marketplace ids like AMAZON_UK, NEWEGG, BH_PHOTO.
-- The constraint is a leftover from when that field was an enum, and it still pinned the
-- column to the three Amazon ids.
--
-- Validation belongs to MarketplaceRegistry, which resolves a URL against the
-- pricewatch.scrape.marketplaces.* configuration and throws UnsupportedMarketplaceException
-- for anything unknown. Adding a storefront is meant to be configuration, so a second
-- hardcoded list in the database would block every future store and could only ever drift
-- out of sync with the properties file.
ALTER TABLE product_listing DROP CONSTRAINT IF EXISTS product_listing_marketplace_check;

-- 2. Rebuild the store whitelist to match the current Store enum.
--
-- product_listing.store IS still @Enumerated(EnumType.STRING), so a CHECK here is correct
-- and Hibernate generated the original. It just predates Store.BH_PHOTO. Keep this list in
-- step with com.tameem.pricewatch.entity.Store whenever a value is added.
ALTER TABLE product_listing DROP CONSTRAINT IF EXISTS product_listing_store_check;
ALTER TABLE product_listing ADD CONSTRAINT product_listing_store_check
    CHECK (store::text = ANY (ARRAY[
        'AMAZON',
        'EBAY',
        'NEWEGG',
        'BH_PHOTO',
        'ALIEXPRESS',
        'WALMART',
        'NOON',
        'CARREFOUR'
    ]::text[]));

COMMIT;
