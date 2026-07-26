# Design reference

Source: Claude Design project `b564263b-9d20-4e41-b844-68de13779532`
<https://claude.ai/design/p/b564263b-9d20-4e41-b844-68de13779532>

`PriceWatch.dc.html` is a local snapshot of the desktop mockup. It renders only
inside the design tool (needs `support.js`) — read it as source, don't open it
expecting a working page.

## What to take from it

| Lines | Content | Use |
|---|---|---|
| 12–34 | `:root` + `[data-theme="dark"]` + `[data-theme="light"]` variables | Copy verbatim into `src/index.css`. Entire palette, both themes. |
| 35–45 | Reset + keyframes (`pw-pop`, `pw-fade`, `pw-slide`) | Copy. |
| ~100–166 | Dashboard markup — hero, cards, list view | Task 2.11–2.16 |
| ~168–317 | Product detail — header, chart panel, store table, target card | Task 2.23–2.27, 4.16 |
| ~318–400 | Add product | Task 2.18–2.21 |
| 533+ | `buildChart()` — SVG scale math | Task 4.8–4.11 |

## Template DSL → React

The mockup uses the design tool's own tags, not React:

- `<sc-if value="{{ x }}">` → `{x && (...)}`
- `<sc-for list="{{ cards }}" as="card">` → `{cards.map(card => ...)}`
- `{{ foo }}` → `{foo}`
- `style-hover=` / `style-focus-within=` → real CSS `:hover` / `:focus-within`

Inline `style="..."` everywhere is a design-tool artifact. Use CSS classes in the
real app — inline style objects reallocate on every render.

## Other files in the design project (not snapshotted)

View in the browser via the URL above.

- **`PriceWatch Mobile.dc.html`** — the responsive target. Worth opening before
  task 2.28. Key differences from desktop, all deliberate:
  - bottom tab bar (Home / Alerts / Add) instead of the sidebar
  - filter chips (All / Price drops / Watching) instead of the search box
  - cards stack vertically, full width
  - chart is `354×196` with left gutter `40`, vs desktop `760×300` gutter `52`;
    4 y-ticks instead of 5, 3 date labels instead of 5
  - `onTouchStart`/`onTouchMove` alongside `onMouseMove` for the chart hover
- `PriceWatch Options.dc.html` — settings screen, no phase assigned yet.
- `screenshots/` — rendered PNGs of each screen.
- `support.js`, `ios-frame.jsx` — design tool runtime. Not needed.

## Known deviations from the mockup

The mockup is the end state (architecture plan §1a), not a phase target.

- **Store chips** show Walmart, Target, B&H Photo. Placeholder art. The decided set
  is plan §1a: Amazon, Noon, Best Buy, Newegg, eBay.
- **Add product** shows a two-step "Find stores → preview → Start tracking" flow.
  That needs cross-store matching, deferred to Phase 3+. Ship single-step.
- **Every card has a sparkline and trend pill.** Both need price history — Phase 4.
  Phase 2 cards render without them.
- **Stat tiles and hero drop banner** need `GET /dashboard/summary` — Phase 4.
- **Alerts screen** is Phase 7.
