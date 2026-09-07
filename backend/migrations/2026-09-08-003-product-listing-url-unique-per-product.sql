-- 2026-09-08-003-product-listing-url-unique-per-product.sql
--
-- Replaces the global UNIQUE(url) on product_listing with UNIQUE(tracked_product_id, url).
--
-- Why: trackProduct's two dedupe lookups were not scoped to the caller. Tracking a URL that
-- another user already tracked returned *their* product — a read leak, and the detail
-- endpoint then correctly refused it, so the product appeared to track and then vanished.
-- The id-based lookup was worse: it could attach the new listing to another user's
-- TrackedProduct, feeding one user's price points into another's history.
--
-- Scoping those lookups is the fix, but it cannot stand alone: with UNIQUE(url) global, the
-- second user to track a URL passes the scoped lookup and then fails at INSERT. Ownership
-- lives on tracked_product.user_id, so uniqueness belongs per product, not per table.
--
-- Tradeoff this locks in (Option A): each user owns their own listing rows, so one product
-- watched by N users is scraped N times per sweep. Option B — shared products with a
-- user_watch join table, the original Phase 4.5 design — removes that cost and is the real
-- long-term answer; see TASKS.md Phase 4.5.
--
-- Safe on existing data: url was globally unique, so every (tracked_product_id, url) pair is
-- already distinct and the new index cannot conflict.
--
-- Re-runnable: the DROP is guarded and the ADD follows a DROP of the same name.

BEGIN;

ALTER TABLE product_listing DROP CONSTRAINT IF EXISTS uk_product_listing_url;

ALTER TABLE product_listing DROP CONSTRAINT IF EXISTS uk_product_listing_product_url;
ALTER TABLE product_listing ADD CONSTRAINT uk_product_listing_product_url
    UNIQUE (tracked_product_id, url);

COMMIT;
