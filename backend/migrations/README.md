# Migrations

This project has no migration runner. Schema comes from
`spring.jpa.hibernate.ddl-auto=update`, which creates missing tables and columns but
**never alters an existing constraint**. Anything Hibernate cannot do for itself lives
here, as plain SQL, applied by hand.

## Applying

In order, oldest first:

```bash
docker exec -i pricewatch-db psql -U postgres -d pricewatch -v ON_ERROR_STOP=1 \
  < backend/migrations/<file>.sql
```

Every file is written to be re-runnable — `DROP ... IF EXISTS` before `ADD` — so
re-applying one is safe if you are unsure whether it has already run.

## Naming

`YYYY-MM-DD-NNN-short-description.sql`, where `NNN` orders files written on the same day.

## Applied

| File | Applied to local dev | Notes |
|---|---|---|
| `2026-09-08-001-product-listing-constraints.sql` | 2026-09-08 | Needed before any non-Amazon storefront can be tracked |
| `2026-09-08-002-store-check-add-currys.sql` | 2026-09-08 | Adds CURRYS to the store whitelist |
| `2026-09-08-003-product-listing-url-unique-per-product.sql` | 2026-09-08 | URL unique per product, not globally — lets two users track the same URL |
| `2026-09-08-004-store-check-add-jarir.sql` | 2026-09-08 | Adds JARIR to the store whitelist |

## If a migration tool is adopted later

Flyway would take these nearly as-is: rename to `V1__product_listing_constraints.sql`,
put them on the classpath under `db/migration`, and baseline the existing schema. The
`IF EXISTS` guards mean re-running against an already-migrated database is a no-op.
