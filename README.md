# PriceWatch

Tracks a product's price across multiple retailers over time, stores the full price
history, and surfaces meaningful drops.

Core loop: **scrape → store → serve → display**.

The interesting engineering problem is not the scraping. It is running many price-check
jobs concurrently and on schedule without one slow store blocking the rest, while
keeping dashboard reads fast.

---

## Status

Working end to end today:

- Add a product by URL — scraped live, saved with its first price observation
- Dashboard with search, grid and list views
- Product detail with per-store pricing
- Duplicate detection: the same product submitted twice returns the existing record

**One scraper (Amazon) is implemented.** Additional retailers, the price-history chart,
scheduled re-scraping, and alerts are in progress. The UI deliberately shows only what
the data supports rather than placeholder values.

---

## Stack

| Layer | Choice |
|---|---|
| API | Spring Boot 4.1, Java 21 |
| Persistence | PostgreSQL 16, Spring Data JPA |
| Scraping | Jsoup |
| Frontend | React 19, TypeScript, Vite, React Router |
| Local infra | Docker |

---

## Data model

```
tracked_product  1───many  product_listing  1───many  price_point
   (the thing)              (per store)                (over time)
```

`price_point` is append-only — every check writes a row rather than updating one.

**Current price, lowest-across-stores, and all-time low are derived, never stored.** A
`current_price` column would eventually disagree with the history table, and the history
is the source of truth.

---

## Running it

**1. Postgres**

```bash
docker run --name pricewatch-db \
  -e POSTGRES_DB=pricewatch \
  -e POSTGRES_PASSWORD=<your-password> \
  -p 5432:5432 -d postgres
```

Already created it once? `docker start pricewatch-db`.

**2. Secrets**

`backend/src/main/resources/application-secrets.properties` is gitignored. Create it:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/pricewatch
spring.datasource.username=postgres
spring.datasource.password=<your-password>
```

**3. Backend** — starts on `:8080`

```bash
cd backend && ./mvnw spring-boot:run
```

**4. Frontend** — starts on `:5173`

```bash
cd frontend && npm install && npm run dev
```

Vite proxies `/api` to `:8080`, so the browser only ever talks to one origin and there
is no CORS configuration to maintain.

---

## API

Base path `/api`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/tracked-products` | Track a URL. `201` when scraped and created, `200` when already tracked |
| `GET` | `/tracked-products` | Dashboard list |
| `GET` | `/tracked-products/{id}` | One product with its per-store listings |
| `POST` | `/tracked-products/{id}/refresh` | Re-scrape now, append a price point |
| `DELETE` | `/tracked-products/{id}` | Stop tracking; cascades to listings and price points |

---

## Notes on some decisions

**URLs are normalised before storage.** Amazon appends per-visit tracking parameters, so
the same product yields a different URL string every time you copy the link. Stripping
the query string and trailing slash makes duplicate detection work; a `UNIQUE` constraint
on the column closes the race that the application-level check alone leaves open.

**Currency is recorded as observed, not inferred.** Retailers localise prices by visitor
IP, so the same `.co.uk` link can return GBP or AED depending on where the request comes
from. The price and its currency are always stored together and consistent with each
other. Cross-currency comparison is deferred until multiple stores make it meaningful.

**The service layer does not know about HTTP.** Tracking returns whether a record was
created; the controller translates that into a status code. The same method can be called
from a message consumer later without change.

**Heavy infrastructure arrives with the feature that needs it.** Queueing, caching, and
model-based drop detection are sequenced to follow the problem that justifies them rather
than being built upfront.
