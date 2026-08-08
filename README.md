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

- Scheduled re-scraping — a background sweep re-checks listings on a fixed interval
  and appends a price observation

**One scraper (Amazon) is implemented.** Additional retailers, the price-history chart,
and alerts are in progress. The UI deliberately shows only what the data supports
rather than placeholder values.

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

## Measurements

Each phase of infrastructure in this project is preceded by a measurement, so the
before/after is recorded rather than asserted. Numbers below are from this machine
against live retailer pages, so they include real network latency.

### Scheduled sweep (sequential)

The scheduler selects listings whose `last_checked` is older than the interval, then
scrapes them one at a time on a single thread. A deliberate 2s pause sits between
requests — the interval alone is not politeness if the sweep fires N requests back to
back.

| Date | Listings swept | Wall clock | Notes |
|---|---|---|---|
| 2026-08-06 | 2 | 6s | 2 scrapes + one 2s inter-request pause |
| 2026-08-07 | 18 | 73s | 8 priced, 10 with no offer at this location; 17 x 2s pause |

Sequential cost is roughly `N × (scrape latency + pause)`, so it grows linearly with
listings and is bounded by the slowest store in the set. This table is the baseline the
queueing work is measured against; it is expected to become the argument for it once the
tracked set is large enough for the linear growth to hurt.

---

## Notes on some decisions

**URLs are normalised before storage.** Amazon appends per-visit tracking parameters, so
the same product yields a different URL string every time you copy the link. Stripping
the query string and trailing slash makes duplicate detection work; a `UNIQUE` constraint
on the column closes the race that the application-level check alone leaves open.

**Currency is stored per observation, not per listing.** Retailers localise by visitor
IP, so the same `.co.uk` link can return GBP or AED depending on where the request comes
from. Currency therefore belongs on the price point, beside the amount it describes —
holding it on the listing means one change of location silently re-denominates the entire
history, and a chart would draw a line whose y-axis changes meaning halfway along. The
listing keeps a "most recently observed" currency for display only.

**A marketplace is a storefront plus a delivery country, and it is configuration.**
"Amazon" is not a market: `amazon.co.uk` shipping to GB and `amazon.ae` shipping to AE
are different catalogues, currencies, and offers for the same product, so prices are only
comparable within one. Marketplaces live in `application.properties` rather than an enum,
because adding a storefront is a deployment concern, not a code change — and because
store x country grows combinatorially once more retailers arrive.

**Scraper egress is an input, not an accident.** Retailers price and stock by the
requesting IP's country, so where a request leaves from is part of the observation. Each
marketplace can be given a proxy in its own country; unset, prices reflect whatever
country the host happens to sit in. This is the deployment decision too — in production
the scraper runs in the marketplace's region rather than behind a third-party proxy.

**Unavailability is data, not an error.** A product with no purchasable offer at the
requesting location is a successful observation: the check happened, and there was no
price. It is recorded as such, distinct from a scrape failure (network error, changed
markup). Conflating the two is what allowed wrong prices into the database — see below.

**Prices are selected by the retailer's semantics, never by position.** A product page
carries several prices, and the real one is not reliably first.

---

## Problems worth writing down

Most of the engineering in this project has been in scraping, and none of it was
predictable from the outside. These are the failures that shaped the current design.

### A page shows several prices, and the first one is usually wrong

A single Amazon product page contains:

- the price you pay (`.apex-pricetopay-value`)
- a per-unit price (`.apex-priceperunit-value`) - AED 44.68 "per count" on an 11-piece set
- a crossed-out RRP (`.apex-basisprice-value`)
- prices for entirely different products, in recommendation carousels

Selecting `.a-price .a-offscreen` and taking the first match returns whichever of these
renders earliest. A cookware set was recorded at 44.68 instead of 491.68 for exactly this
reason. The fix was to stop reasoning about position: Amazon marks non-payable prices
with `.a-text-price`, so excluding that class makes document order irrelevant.

### On discounted listings the accessible price is empty

The screen-reader span inside the real price is normally the cleanest source. On a
discounted item it is empty, and the price exists only as separate symbol, whole and
fraction elements - while the crossed-out RRP *does* populate its span. So a parser that
reads only `.a-offscreen` silently records the RRP as the current price. The reader now
falls back to assembling the parts.

### A missing price usually means "no offer here", not "scraper broken"

Ten of eighteen tracked products return no price, and the split is stable across sweeps.
The pages are not broken and the markup has not changed: Amazon shows no price when an
item cannot ship to the requesting location, because there is no offer to show. Tracking
`.co.uk` links from outside the UK therefore prices whichever products happen to ship
internationally and silently finds nothing for the rest.

With no explicit availability check, the scraper fell through to the carousel prices
above and recorded confident, wrong numbers - one product produced 84.03, 12.79, 133.39
and 7.06 across four consecutive sweeps.

Availability is now decided before the price is read, from `#outOfStock` and the presence
of a buy button. Two products recorded the same price to the penny is the signature of
this bug, and worth checking for in any scraped dataset.

The deeper point is that a tracked price is only meaningful relative to a market. Where
the request comes from must be a property of the system, not of whoever happens to be
running it - otherwise the same listing yields different history on a laptop, on a
colleague's laptop, and in production. That is why a listing records its marketplace and
why egress is configurable per marketplace: results should not move when the operator
does.

### Amazon's own API is not a way out

The Product Advertising API requires an Associates account, and access is revoked without
qualifying sales. A portfolio project built on it stops working a few months after being
written. Scraping is the honest choice here; where a retailer offers a real public API,
using it is the better one, and the `ProductScraper` interface exists so both can coexist.

### Verify a retailer renders prices server-side before writing a scraper for it

One `curl` and a search for the price is enough. If it is not in the HTML, the choice is
between the JSON endpoint the page itself calls and a headless browser - a decision worth
making before writing selectors rather than after.

**The service layer does not know about HTTP.** Tracking returns whether a record was
created; the controller translates that into a status code. The same method can be called
from a message consumer later without change.

**Heavy infrastructure arrives with the feature that needs it.** Queueing, caching, and
model-based drop detection are sequenced to follow the problem that justifies them rather
than being built upfront.
