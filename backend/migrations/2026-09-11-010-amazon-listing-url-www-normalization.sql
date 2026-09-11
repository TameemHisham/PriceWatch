-- 2026-09-11-010-amazon-listing-url-www-normalization.sql
--
-- Rewrites bare-host Amazon listing URLs to the "www." form, so one page has one stored
-- string.
--
-- Why: AmazonScraper.canonicalUrl used to echo back the host it was given, while sibling
-- fan-out built its URLs from the configured host (amazon.co.uk, no "www."). The same page
-- therefore reached the table under two spellings. trackProduct dedupes by exact URL match,
-- so it missed across them, resolved the request onto the same product by ASIN, and tried to
-- insert a second listing for a marketplace that product already had — the 500 fixed at the
-- app layer in 648a477. canonicalUrl now derives the host from configuration and qualifies
-- it with "www.", matching what every other scraper already emits; this migration brings the
-- rows written before that change into the same form.
--
-- Collision analysis, run against the live table before writing this:
--
--   37 Amazon rows, 12 of them bare-host (7 AMAZON_UK, 5 AMAZON_US), all already https.
--   Grouping by (tracked_product_id, normalized url) returned 0 groups with more than one
--   row, and the expression was confirmed non-vacuous: it rewrites exactly those 12.
--
-- That is not luck, and it is not specific to this data. Two rows differing only by "www."
-- describe the same host, so they carry the same marketplace, and UNIQUE
-- (tracked_product_id, marketplace) has always forbidden a product from holding two rows on
-- one marketplace. The constraint that surfaced the original bug is the same one that makes
-- this backfill safe. So there is nothing to deduplicate, and no keep-the-newer-row rule is
-- needed here; if a future dataset ever did collide, it would mean that constraint had been
-- dropped, and the merge rule would have to be decided then.
--
-- Scoped to store = 'AMAZON' because it is the only store with drift: every BH_PHOTO,
-- CURRYS, FLIPKART, IKEA, JARIR and NEWEGG row is already "www.", their scrapers having
-- qualified the host from the start.
--
-- Safe on existing data: no row's identity changes, only the spelling of its URL, and the
-- UNIQUE (tracked_product_id, url) index cannot conflict for the reason above.
--
-- Re-runnable: the predicate only matches URLs that are not already "www.", so a second run
-- updates nothing.

BEGIN;

UPDATE product_listing
SET url = regexp_replace(url, '^https://(?!www\.)', 'https://www.')
WHERE store = 'AMAZON'
  AND url ~ '^https://(?!www\.)';

COMMIT;
