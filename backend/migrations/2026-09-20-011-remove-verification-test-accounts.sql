-- One-time dev cleanup: remove the two throwaway accounts created while verifying
-- cross-store discovery and the matcher fix, and everything they own.
--
--   users.id 31  verify-discovery@example.com          -> products 107,108,109,115,116,117,118,119
--   users.id 32  matcher-verify-1789712386@example.com -> products 110,111
--
-- Scoped by user_id so no product is orphaned and the user rows can be removed; the two
-- accounts provably own exactly those 10 products. Deletes run child-first
-- (price_point -> product_listing -> tracked_product -> users) so no FK constraint fails.
-- Wrapped in a transaction: all-or-nothing.
--
-- Leaves test@example.com (id 1) and tameem@gmail.com (id 3) untouched.

BEGIN;

DELETE FROM price_point
WHERE product_listing_id IN (
    SELECT l.id FROM product_listing l
    JOIN tracked_product p ON p.id = l.tracked_product_id
    WHERE p.user_id IN (31, 32)
);

DELETE FROM product_listing
WHERE tracked_product_id IN (
    SELECT id FROM tracked_product WHERE user_id IN (31, 32)
);

DELETE FROM tracked_product WHERE user_id IN (31, 32);

DELETE FROM users WHERE id IN (31, 32);

COMMIT;
