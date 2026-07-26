# PriceWatch — Task Order

Execution checklist for `backend/docs/PriceWatch-Architecture-Plan.md`.
**That document is the source of truth.** This file only breaks its phases into
ordered, checkable tasks. If the two disagree, the architecture plan wins.

Phase numbering matches the plan (§6). Build rule (§6): written from scratch, from
official docs. Claude advises and corrects errors; it does not supply code.

Visual reference: Claude Design project `b564263b`, file `PriceWatch.dc.html`.
Note its store chips (Walmart, Target, B&H) are placeholder art — the decided store
set is §1a: **Amazon, Noon, Best Buy, Newegg, eBay**.

**Status:** Phase 0 ✅ · Phase 1 ✅ (2 bugs open) · Phase 2 in progress

---

## Phase 1.5 — Unbreak the build

Not in the plan. Two defects found in the Phase 1 code.

- [ ] **1.5.1** Add `jakarta.validation.Valid` import to `ProductController`.
      Currently a compile error — `web.bind.annotation.*` does not cover it.
- [ ] **1.5.2** `mvn compile` → green.
- [ ] **1.5.3** Add `@NotBlank` to `TrackRequest.url`. Without a constraint on the
      record, `@Valid` walks the object, finds nothing, and passes.
- [ ] **1.5.4** Verify POST `{"url": ""}` returns 400, not a 500 from inside Jsoup.
- [ ] **1.5.5** Commit.

_~1h_

---

## Phase 2 — Frontend for the core slice

Plan §6: _"matching the mockups but honestly showing '1 store' until Phase 3."_
**No chart in this phase** — no history endpoint exists yet. No stat tiles either
(`/dashboard/summary` is Phase 4). Ship the honest subset.

### 2a — Skeleton, no data

- [ ] **2.1** Lift the `:root` / `[data-theme]` CSS variable blocks from
      `PriceWatch.dc.html` (lines 12–34) into `src/index.css`. Entire visual identity.
- [ ] **2.2** Load Hanken Grotesk + JetBrains Mono in `index.html`.
- [ ] **2.3** `AppShell` — sidebar + main. Hardcoded nav.
- [ ] **2.4** Theme toggle via `data-theme` on the shell root. Check both themes.
- [ ] **2.5** `npm i react-router-dom`.
- [ ] **2.6** Routes → page shells: `/`, `/product/:id`, `/add`.
      Add an `/alerts` route rendering "coming soon" — it's Phase 7.
- [ ] **2.7** Commit. First time the frontend is tracked in git.

### 2b — Dashboard

- [ ] **2.8** `useTrackedProducts()` hook wrapping the existing
      `getTrackedProducts()`. Never fetch inside a component.
- [ ] **2.9** Fix `jsonRequest`'s error path — read the response body. Right now
      validation messages from 1.5.3 are discarded.
- [ ] **2.10** Render raw JSON in a `<pre>`. Prove the proxy + DTO shape work before
      writing any card markup. Fix here, not later.
- [ ] **2.11** `ProductCard` — brand, name, price, store count. **No sparkline, no
      trend pill** (both need history). Leave the space.
- [ ] **2.12** Grid layout, `auto-fill minmax(360px, 1fr)`.
- [ ] **2.13** Loading skeletons + error state.
- [ ] **2.14** Empty state — nothing tracked yet.
- [ ] **2.15** Search box, client-side filter.
- [ ] **2.16** Grid/list view toggle.
- [ ] **2.17** Commit.

### 2c — Add product

- [ ] **2.18** URL input + submit, wired to existing `trackProduct()`.
- [ ] **2.19** Submitting state. Scraping is slow — this is required, not polish.
- [ ] **2.20** Error display using 2.9.
- [ ] **2.21** Supported-store chips + empty state.
- [ ] **2.22** Commit.

**Scope note:** the mock's two-step "Find stores → preview → Start tracking" is the
deferred hard-matching problem (plan §6 Phase 3). Ship single-step now.

### 2d — Product detail

- [ ] **2.23** `useTrackedProduct(id)` hook.
- [ ] **2.24** Header — image, brand, name, lowest price, store, back button.
- [ ] **2.25** Store price table. One row today. Build it as a list from the start so
      Phase 3 needs no rewrite.
- [ ] **2.26** Refresh button → `refreshProduct()` → refetch.
- [ ] **2.27** Delete with confirm → navigate to dashboard.
- [ ] **2.28** Responsive pass.
- [ ] **2.29** Commit. **Done when:** clickable demo of the core idea in a browser.

_~4–5 days_

---

## Phase 3 — More stores

Plan §1a/§6. Highest-risk phase in the project.

- [ ] **3.1** Refactor `AmazonScraper` behind a scraper-selection strategy that picks
      by URL host. Do this before writing scraper #2, not after.
- [ ] **3.2** Save real HTML fixtures per store so scraper work and UI work never
      block on a live request.
- [ ] **3.3** Scraper #2. **Stop here and reassess.** Plan §1a says the final store
      list is picked _after_ you've felt the effort of scraper #2. Log actual hours.
- [ ] **3.4** Locale/currency parsing — international sites serve different HTML and
      currency per region. Plan §1a calls this out as the real stressor.
- [ ] **3.5** Scrapers #3–5, from the reassessed list.
- [ ] **3.6** Multi-listing refresh — scrape a product's stores in sequence.
- [ ] **3.7** Enforce the `UNIQUE (tracked_product_id, store)` constraint from §3.
- [ ] **3.8** Detail page: per-store rows, LOWEST badge, vs-lowest delta, Visit link.
- [ ] **3.9** Commit. **Done when:** one product shows prices from multiple stores.

**Risk:** Amazon actively blocks Jsoup; selectors rot without notice. Every store is a
fresh reverse-engineering session. Fixtures (3.2) are what stop this phase from
holding the whole project hostage.

_~4–8 days. Widest variance in the plan._

---

## Phase 4 — History, charts, scheduling, targets

- [ ] **4.1** `PricePointRepository.findByProductListingOrderByCheckedAtAsc`.
- [ ] **4.2** `GET /{id}/history` — series **keyed by store**
      (`{ "AMAZON": [{date, price}], … }`), per plan §4. Not a flat list.
- [ ] **4.3** `PUT /{id}/target` — its own endpoint, per §4. Reached state computed,
      never stored.
- [ ] **4.4** `GET /dashboard/summary` — tracked count, active drops, saved, avg drop.
      Derived per §3, not columns.
- [ ] **4.5** Add trend % + recent price array to `TrackedProductResponse` so cards
      get sparklines without N extra calls.
- [ ] **4.6** `@Scheduled` re-scrape, in-process. Respect `last_checked` so you don't
      hammer stores.
- [ ] **4.7** Verify all of the above in Postman before touching React.
- [ ] **4.8** `PriceChart`: axes + gridlines only, no data. Get the scales right first
      — `X(i)`/`Y(v)` are linear scales; math is at `PriceWatch.dc.html` line 533.
- [ ] **4.9** One line per store.
- [ ] **4.10** Shaded area under the running minimum.
- [ ] **4.11** Hover crosshair + per-store tooltip.
- [ ] **4.12** Legend + collapsible panel.
- [ ] **4.13** Sparklines on cards, reusing the same scale helpers.
- [ ] **4.14** Trend pills on cards (the gap left at 2.11).
- [ ] **4.15** Stat tiles + hero drop banner from `/dashboard/summary`.
- [ ] **4.16** Target-price card with reached/not-reached state.
- [ ] **4.17** Commit. **Done when:** history accumulates on a schedule and renders.

_~4–5 days. 4.8–4.11 is the single hardest task in the project — do not attempt it in
one sitting._

---

## Phase 5 — Kafka

Plan §5: the trigger is scheduled scraping across N products × 5 stores starting to
block. **Measure before you migrate — the before/after number is the interview story.**

- [ ] **5.1** Record baseline: wall-clock for a full scheduled sweep, at a product
      count large enough to hurt. Write the number down.
- [ ] **5.2** Kafka + Zookeeper (or KRaft) in docker-compose.
- [ ] **5.3** Learn the model first: topics, partitions, consumer groups, offsets.
      Partition count caps consumer parallelism — decide it deliberately.
- [ ] **5.4** Define the job message — listing id, store, url. Keep it small.
- [ ] **5.5** Producer: scheduler publishes instead of scraping.
- [ ] **5.6** Consumer: scrapes, writes `price_point`.
- [ ] **5.7** Failure handling — retries, dead-letter topic. A store being down must
      not stall a partition.
- [ ] **5.8** Scale to multiple consumer instances, confirm the group rebalances.
- [ ] **5.9** Re-measure. Record before/after in the README.
- [ ] **5.10** Commit. **Done when:** scraping is decoupled and consumers scale.

_~4–6 days from zero Kafka knowledge. ~2 if you already know it._

---

## Phase 6 — Redis

Plan §5 trigger: dashboard hammering Postgres for the same hot products.

- [ ] **6.1** Measure baseline dashboard read latency and query count.
- [ ] **6.2** Redis in docker-compose, `spring-boot-starter-data-redis`.
- [ ] **6.3** Cache the derived current-price values (§3 "Derived values"). History
      still reads Postgres.
- [ ] **6.4** Invalidate on new `price_point` — the consumer writes DB then updates
      cache, per the §5 end-to-end trace.
- [ ] **6.5** Fall back cleanly when Redis is down. A cache outage must not be an
      app outage.
- [ ] **6.6** Re-measure. Record.
- [ ] **6.7** Commit.

_~2–3 days_

---

## Phase 7 — Alerts + Python ML service

- [ ] **7.1** Decide `target_price` on `tracked_product` vs a separate `alert` table.
      Plan §3 says start with the field, promote only if multi-alert is needed.
- [ ] **7.2** Recompute reached state on each scrape.
- [ ] **7.3** `GET /alerts` + mark-as-read.
- [ ] **7.4** Alerts screen (already designed) + sidebar unread badge.
- [ ] **7.5** FastAPI service skeleton.
- [ ] **7.6** Decide integration: Spring calls it over HTTP, or it reads the shared
      DB. HTTP is cleaner to explain in an interview.
- [ ] **7.7** Trend/drop detection over the history series. Start simple — a moving
      average and a threshold beats an unexplainable model.
- [ ] **7.8** Surface model output in the UI.
- [ ] **7.9** Commit.

_~5–7 days_

---

## Phase 8 — Deploy to AWS

- [ ] **8.1** Dockerfile for the Spring app. Multi-stage build.
- [ ] **8.2** Dockerfile for the FastAPI service.
- [ ] **8.3** Full docker-compose: backend, frontend, Postgres, Kafka, Redis, ML.
      Works end-to-end locally first.
- [ ] **8.4** Externalize all config to environment variables. The secrets file
      becomes env vars / Secrets Manager, per plan §5a.
- [ ] **8.5** Postgres → RDS.
- [ ] **8.6** Containers → ECS (or EC2). Managed Kafka (MSK) is expensive — consider
      self-hosting it on the same box for a portfolio project.
- [ ] **8.7** Frontend → S3 + CloudFront, or served by Spring.
- [ ] **8.8** Domain, HTTPS.
- [ ] **8.9** **Watch the bill.** Set a budget alarm on day one. MSK and RDS are the
      expensive line items.
- [ ] **8.10** README: screenshots, architecture diagram, the Phase 5 and 6
      before/after numbers.
- [ ] **8.11** Tag `v1.0`. **Done when:** a recruiter can click a live URL.

_~5–8 days_

---

## Estimate

At 8h/day of genuine focus:

| Phase                           | Days           |
| ------------------------------- | -------------- |
| 1.5 Bugs                        | 0.1            |
| 2 Frontend core slice           | 4–5            |
| 3 More stores                   | 4–8            |
| 4 History + charts + scheduling | 4–5            |
| 5 Kafka                         | 4–6            |
| 6 Redis                         | 2–3            |
| 7 Alerts + ML                   | 5–7            |
| 8 AWS                           | 5–8            |
| **Total**                       | **28–42 days** |

**≈ 6–9 calendar weeks** at 8h/day. 8h/day of real focus is rare — the honest planning
number is closer to **10 weeks**.

### Two demoable checkpoints along the way

- **End of Phase 2** (~1 week): clickable core demo. Something to show immediately.
- **End of Phase 4** (~3 weeks): visually complete against the mockups. Everything
  after this is backend depth an interviewer has to be _told_ about — which is exactly
  why the Phase 5/6 before-and-after measurements matter.

### Tests

The plan has no test phase. Add them inside each phase rather than at the end:
`@DataJpaTest` on repositories, `@WebMvcTest` on controllers, scraper tests against
the Phase 3.2 fixtures. Roughly +15% to each phase.
