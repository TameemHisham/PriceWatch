# PriceWatch — Architecture & Development Plan

> **Purpose:** the single source of truth for what PriceWatch is, how it's structured, and the order we build it in. Reopen this after any break to re-orient.

---

## 1. What PriceWatch Does (one paragraph)

A user adds a product they care about (by URL now, by name later). The system checks that product's price across five retailers — Amazon, Noon, Best Buy, Newegg, eBay — on a schedule, stores the full price history over time, and alerts the user on a meaningful price drop. Core flow: **scrape → store → serve → display/alert**. The interesting engineering problem is not the scraping — it's running thousands of price-check jobs concurrently and on schedule, without one slow store blocking the rest, while keeping dashboard reads fast.

---

## 1a. The UI North Star (from mockups)

Three mockups define the target UX. They are the **end state**, not Phase 1 — the UI degrades gracefully as features land (a product tracked from a single URL honestly shows "1 store" until cross-store matching exists).

- **Dashboard** — grid of product cards, each showing: brand + category, best current price + which store, change % (↓16.9%), a mini price-history sparkline, store count, and target price. Top strip: products tracked, active price drops, money saved, avg. drop. Sidebar: Dashboard / Alerts (with count badge) / Add product / this-month savings / dark toggle.
- **Add product** — one input for a URL *or* a product name, single "Find stores" action, list of supported stores shown as chips. Empty state: "paste a link to get started."
- **Product detail** — big "lowest right now" price + all-time low + which store, a "dropped X% this week" banner, a **multi-line 90-day price-history chart (one line per store, lowest shaded)** with a date-hover tooltip, a "current price by store" table with per-store *vs. lowest* deltas and a LOWEST badge + Visit links, and a **target-price alert card with a reached/not-reached state**.

### Store list — DECIDED: international stores
**Direction locked: big international retailers** (operate/ship across multiple regions), so the app isn't tied to one market. Amazon is the anchor (US, UAE, UK…); likely companions include eBay, Newegg, and others with international reach. The exact final set is picked at **Phase 3**, because the real constraint is *1 store = 1 scraper to write and maintain*, and international sites often serve different HTML/currency per region — which stresses the locale price-parsing logic. So: direction decided now, specific list finalized when you've felt the effort of scraper #2. `store` stays a Java enum, so adding/removing is cheap.

---

## 2. The Guiding Principle (read this before every phase)

**Introduce each technology when the feature that needs it is being built — not before. DECIDED.**

Building Kafka, Redis, and the ML service upfront is the classic way a portfolio project ends up *broad but shallow* — many technologies, none of them deep, none with a story. Instead:

- Build a working, demoable vertical slice first (synchronous, single store).
- Each heavy piece arrives **with the feature that requires it**, and you record the before/after pain that justified it.
- Each addition becomes an interview story about **judgment**, not tutorial-following.

This is not "cutting scope" — every technology in the vision still ships. It's *sequencing* them so each one is understood and defensible. This sequencing is **confirmed**, not tentative.

---

## 3. Database Schema (the foundation)

The current flat `products` table is wrong for this vision — it can't hold history or multiple stores per product. Replace it with a normalized 3-table model.

### `tracked_product` — the thing the user wants to watch
| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | generated |
| `name` | VARCHAR | canonical/scraped title ("Sony WH-1000XM5") |
| `brand` | VARCHAR nullable | shown on cards ("SONY") — scraped or parsed |
| `category` | VARCHAR nullable | shown as a tag ("Headphones") |
| `image_url` | VARCHAR nullable | product image |
| `target_price` | NUMERIC(10,2) nullable | user's alert threshold ("target $313") |
| `created_at` | TIMESTAMP | when tracking started |

*(A `user_id` FK gets added later, when auth exists — deferred. `brand`/`category` are nullable because the scraper may not always find them; they're display niceties, not core.)*

### `product_listing` — one product ON one store
| Column               | Type                                   | Notes                                          |
| -------------------- | -------------------------------------- | ---------------------------------------------- |
| `id`                 | BIGINT PK                              | generated                                      |
| `tracked_product_id` | BIGINT FK → `tracked_product`          | which product                                  |
| `store`              | VARCHAR                                | `"AMAZON"`, `"NOON"`, … (Java enum, see below) |
| `url`                | VARCHAR                                | the specific product-page URL                  |
| `currency`           | VARCHAR                                | `NOT NULL`, `"UNKNOWN"` fallback allowed       |
| `last_checked`       | TIMESTAMP nullable                     | last successful scrape                         |
| `created_at`         | TIMESTAMP                              |                                                |
| —                    | UNIQUE (`tracked_product_id`, `store`) | one listing per store per product              |

### `price_point` — a single price observation over time (the heart of the app)
| Column               | Type                                       | Notes                    |
| -------------------- | ------------------------------------------ | ------------------------ |
| `id`                 | BIGINT PK                                  | generated                |
| `product_listing_id` | BIGINT FK → `product_listing`              | which listing            |
| `price`              | NUMERIC(10,2)                              | the observed price       |
| `checked_at`         | TIMESTAMP                                  | when this price was seen |
| —                    | INDEX (`product_listing_id`, `checked_at`) | fast history queries     |

### `alert` — a target-price watch with a status *(added Phase 7)*
| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | generated |
| `tracked_product_id` | BIGINT FK → `tracked_product` | which product |
| `target_price` | NUMERIC(10,2) | "alert me at or below" |
| `reached` | BOOLEAN | evaluated on each scrape; drives the "✓ Reached" state |
| `created_at` | TIMESTAMP | |

*(An alert isn't just a threshold — it has a **status** the backend recomputes each scrape by comparing the current lowest price against `target_price`. Note `target_price` also lives on `tracked_product` for the simple single-target case shown in the mockup; the separate `alert` table is only needed if one product ever has multiple alert rules. **Start with the field on `tracked_product`; promote to the table only if multi-alert is needed.**)*

### Relationships
```
tracked_product  1───many  product_listing  1───many  price_point
   (a product)              (per store)                 (over time)
```

### Derived values (computed, never stored as columns)
The mockups lean heavily on values that are **calculated from `price_point`**, not persisted — storing them would invite drift:
- **Current price per listing** — latest `price_point` for that listing.
- **Best/lowest price + which store** — min current price across a product's listings (drives the card price, the "LOWEST" badge, "at Amazon").
- **All-time low** — `MIN(price)` across all of a product's price points.
- **Change % (↓16.9%)** — current vs. a prior reference point (e.g. last week).
- **vs. lowest per store (+$59.70)** — each store's current price minus the lowest current price.
- **Aggregate metrics** ("$145 saved", "avg drop -11.9%", "5 drops active") — computed across all tracked products for the dashboard header.

These are the backend's real work on the read path — and exactly what Redis caches later (Phase 6).

### Two design rules worth keeping
- **`store` is a Java enum for now**, not a table. The set is fixed (~6) and rarely changes. Promote it to a `store` table only if per-store config (base URL, scraper flags) grows enough to justify it. *(Which 6 stores is the open US-vs-UAE decision noted in §1a.)*
- **"Current price" is derived, never stored as a column.** It's the latest `price_point` for a listing. Storing a `current_price` column invites drift (the column disagreeing with history). Later, Redis caches this derived value — but the source of truth stays the history table.

### How the scraper output maps in *(you'll build the scraper in Phase 1)*
The scraper will return something like `ProductData(title, price, currency, imageUrl)` — it maps cleanly:
- `title` → `tracked_product.name` (first time) + used to create the `product_listing`
- `imageUrl` → `tracked_product.image_url`
- `currency` → `product_listing.currency`
- `price` + now → a new `price_point`

### What happened to the old code
The flat `Product` com.tameem.pricewatch.entity, `ProductService`, the 5 hand-typed CRUD endpoints, **and the earlier scraper** were all removed at the `restart` commit. The CRUD endpoints were learning scaffolding (a user never types a price). The scraper is being rewritten from scratch by choice. Nothing is lost — it all still lives in Git history before `restart`.

---

## 4. REST API (backend contract)

Base path `/api`. Built incrementally, not all at once.

| Method | Path | Purpose | Phase |
|---|---|---|---|
| `POST` | `/tracked-products` | Add a product to track (body: `{ url }`, later `{ name }`) | 1 |
| `GET` | `/tracked-products` | Dashboard list | 1 |
| `GET` | `/tracked-products/{id}` | One product + its listings + current prices | 1 |
| `POST` | `/tracked-products/{id}/refresh` | Manually re-scrape now | 1 |
| `DELETE` | `/tracked-products/{id}` | Stop tracking | 1 |
| `GET` | `/tracked-products/{id}/history` | Per-store price series for the 90-day chart | 4 |
| `PUT` | `/tracked-products/{id}/target` | Set/update target price (the "Set alert" button) | 4 |
| `GET` | `/alerts` | Alerts list + reached state (sidebar badge) | 7 |
| `GET` | `/dashboard/summary` | Header aggregates (tracked, drops, saved, avg drop) | 4 |

**`history` shape:** returns series **keyed by store** (`{ "AMAZON": [{date, price}, …], "BEST_BUY": [...] }`), because the chart draws one line per store — not one flat list.

**`GET /tracked-products/{id}` returns the derived values** the detail view needs: lowest-now + store, all-time low, per-store current price + vs-lowest delta + the listing `url` (for "Visit"), and target/reached state.

**Validation:** the incoming DTO is tiny — `TrackRequest(url | name)`. Price/currency/brand/category are **outputs the scraper discovers**, never fields the user submits. `target_price` is the one user-supplied number, set via its own endpoint, not at creation.

---

## 5. Where Each Heavy Technology Fits — and WHEN

| Tech | Role | Introduced in | Trigger (the "pain" that justifies it) |
|---|---|---|---|
| **Spring Boot + Postgres** | Core API + relational store | Now | Foundation |
| **Docker** | Local Postgres (then Kafka/Redis) | Now | Already in use |
| **@Scheduled** | Periodic re-scrape, in-process | Phase 4 | History needs regular data points |
| **Kafka** | Decouple "job needed" from "job run"; scale consumers | Phase 5 | Scheduled scraping across N products × 5 stores starts blocking / slowing |
| **Redis** | Cache current-price reads | Phase 6 | Dashboard hammering Postgres for the same hot products |
| **Python ML (FastAPI)** | Trend prediction / drop detection | Phase 7 | Enough history exists to model a trend |
| **AWS** | Real deployment | Phase 8 | Turn localhost demo into a clickable link |

**End-to-end trace (final system):** scheduler publishes a "check listing X" job → Kafka → consumer scrapes → writes `price_point` to Postgres + updates Redis → React dashboard reads Redis (fast path) / Postgres (history) → Python model flags a drop → alert surfaces in UI.

---

## 5a. Setup & Plumbing (the stuff you're learning to host later)

Map-level pointers, not copy-paste configs. Each item says *what it is*, *why it exists*, and *what to go learn*. You write the actual files from the docs.

### Docker + Postgres (local database)
- **What:** Postgres runs inside a Docker container instead of installed directly on macOS.
- **Why:** the container is disposable and identical to what you'll deploy — kills "works on my machine," and you can reset DB state by destroying/recreating the container.
- **Learn:** `docker run` (creates a *new* container from an image) vs `docker start`/`stop` (an *existing* one) vs `docker ps`/`ps -a` (running vs all). How to pass `-e POSTGRES_PASSWORD=…`, `-p 5432:5432` (port mapping), and a named volume so data survives restarts. Docs: hub.docker.com Postgres image page.
- **Gotcha you already hit:** a stopped container isn't gone — `docker start <name>` revives it; `docker run <id>` wrongly tries to pull an image.

### `application.properties` vs `application-secrets.properties`
- **What:** two config files. The main one holds non-secret settings (JPA/Hibernate behaviour, `ddl-auto`, datasource URL). The `-secrets` one holds credentials (DB password, later API keys).
- **Why split:** so you can commit the safe config to Git while keeping secrets *out* of the repo. Spring can load an extra profile-style file and merge it.
- **Learn:** how Spring imports a secondary properties file (`spring.config.import`), and what `spring.jpa.hibernate.ddl-auto` values mean — `update` (adds missing columns, never alters/drops existing ones — the source of your earlier `currency` mismatch), `validate` (checks only, boots-fail on mismatch), `create`/`create-drop` (rebuild each run — handy early), `none`. For a from-zero rebuild, `create` or `update` early, tighten to `validate` later.

### `.gitignore` discipline (keep secrets out of history)
- **What:** a file listing paths Git must never track.
- **Why:** once a secret is committed, it lives in history forever (the same permanence that makes your `restart` commit safe works *against* you here). Prevention beats cleanup.
- **Learn:** add `application-secrets.properties`, `target/`, IDE folders, `.env` to `.gitignore` **before** the first commit that would include them. Verify with `git status` that the secrets file shows as ignored, not staged.

### Forward-pointer: AWS hosting (Phase 8, not now)
- The whole point of the Docker + secrets discipline is that it maps onto cloud deploy later: the container becomes an ECS/EC2 workload, the secrets file becomes AWS Secrets Manager / environment variables, local Postgres becomes RDS. You're not learning throwaway local tricks — you're learning the shape of production. Details come at Phase 8; just know the plumbing choices now are deliberate groundwork.

---

## 6. The Phased Roadmap

Each phase = **backend first → verify in Postman → then React/TS frontend**. One feature end-to-end before the next. **Build rule for this project: written from scratch, from official docs. No copied code. Claude acts as advisor/error-corrector, not code source.**

### Phase 0 — Clean slate + data model *(foundation, no new tech)*
- Starting point: repo wiped to a `restart` commit — Spring/Maven skeleton kept, all prior `pricewatch` source (including scrapers) deleted, `products` table dropped.
- Confirm setup works from zero: Docker Postgres running, `application.properties` + secrets wired, app boots against an empty DB.
- Build the three entities yourself: `TrackedProduct`, `ProductListing`, `PricePoint` (+ their com.tameem.pricewatch.repositories), matching §3.
- **Done when:** Docker DB up, app boots clean, the three tables exist in Postgres, entities compile.

### Phase 1 — Core vertical slice: track one product by URL *(synchronous, single store)*
- Write the scraper layer from scratch (Jsoup): a `ProductScraper` interface + first Amazon implementation + a `ProductData` carrier + a `ScrapeException`. (You've built this before and understand it — now reproduce it from docs without copying.)
- `POST /tracked-products` with a URL → pick scraper → scrape → create product + listing + first price_point.
- `GET` list + detail, `POST /{id}/refresh`, `DELETE /{id}`.
- Amazon-only, synchronous. No Kafka, no Redis.
- **Done when:** full loop works in Postman — add a URL, see it saved, refresh it, delete it.

### Phase 2 — Frontend for the core slice
- React + TS: the "Add product" screen (URL input), dashboard grid with product cards, product detail page — matching the mockups but **honestly showing "1 store"** until Phase 3.
- **Done when:** clickable demo of the core idea in the browser.

### Phase 3 — More stores
- Add the remaining scrapers (chosen store set — see §1a) behind the same `ProductScraper` interface.
- One `tracked_product` now has multiple listings → the "current price by store" table and multi-line chart become real. Still synchronous (scrape stores in sequence on refresh — fine for ~6).
- **Note:** "paste one URL → match across all stores" (the Add-product promise) is the **deferred hard-matching problem**. Until it's built, adding by URL tracks that one store; adding others is manual/name-based.
- **Done when:** one product shows prices from multiple stores with per-store vs-lowest deltas.

### Phase 4 — Price history + charts + scheduling + targets
- `GET /{id}/history` (per-store series) → the 90-day multi-line chart with lowest-price shading and hover tooltip.
- Target-price field + `PUT /{id}/target` → the "Set alert" card (reached state computed).
- `GET /dashboard/summary` → the header aggregate tiles.
- Add `@Scheduled` re-scrape (still in-process, synchronous) so history accumulates automatically.
- **Done when:** history accumulates on a schedule and renders as the multi-store trend chart.

### Phase 5 — Introduce Kafka *(the justified moment)*
- Scheduler stops scraping directly; it publishes jobs to a Kafka topic. Consumers scrape.
- **Measure and record** throughput before/after — this is the interview story.
- **Done when:** scraping is decoupled, consumers scale independently.

### Phase 6 — Introduce Redis
- Cache current-price reads; fall back to Postgres for history.
- **Done when:** dashboard reads hit cache, DB load drops for hot products.

### Phase 7 — Alerts + Python ML microservice
- FastAPI service reads history, flags meaningful drops / trends.
- Spring calls it (or shares DB); React surfaces alerts.
- **Done when:** a price drop triggers a visible alert.

### Phase 8 — Deploy (Docker Compose → AWS)
- Whole stack containerized; deployed so a recruiter can click into it.
- **Done when:** live URL.

---


