# Shared show scan: intersect two users' artist lists at a named location (#163)

**Status:** approved 2026-08-16 (brainstormed interactively with Rob).
**Issue:** #163 (`ready`).

## Goal

Answer "we're both going to be in Chicago that week — which shows do *we both* want to see?"

Everything in the app today is single-owner: `Artist`, `Show`, `SearchSettings`, and every job row is scoped by `owner`, and every scan is pinned to that owner's saved ZIP/radius/window. Nothing reads across two owners, and nothing can search a location that isn't somebody's saved home base. This adds one page that does both, without disturbing either.

## Decisions (settled during brainstorming — these bind the design)

| Decision | Choice | Why |
|---|---|---|
| Execution | **Synchronous, with a cap** | Real intersection today is 4 artists × 2 sources = 8 API calls, not the ~40 the issue feared. Durable-job machinery would be scaffolding for an 8-call operation. |
| Placement | **New page** under the Shows nav | The app's page restraint (#96) is worth keeping, but a mode toggle would make an already-busy `/` busier. |
| Sources | **Ticketmaster + Bandsintown** | Both cheap API calls. Excludes `BandSiteShowSource`, which scrapes and falls back to `TourPageLlmService` — slow and bills per artist, directly at odds with a synchronous request. |
| Access | **Admin only** | Reuses the existing `#136` admin gate as-is. No new access logic. |
| Persistence | **Transient — render and discard** | No schema change. Avoids polluting an owner's `show_event` (owner-scoped, natural-key unique) with shows at a location they don't live in. |
| Thin results | **Clear empty states only** | No "only one of you follows this" section — the issue itself flags it as speculative. |

## 1. The intersection must normalize names — this is the load-bearing detail

Verified against production. Rob has 1,269 active artists, David has 5. The true overlap is **4**:

```
Tom Petty        / Tom petty
James Taylor     / James taylor
Bruce Springsteen / Bruce springsteen
Brandi Carlile   / Brandi Carlile
```

An exact-name join (`a.name = b.name`) finds **1 of 4** — and looks like it works. David's entries are lowercased on the second word; Rob's are properly capitalized.

The intersection **must** go through `catalog.ArtistNameNormalizer`, the app-wide definition of name equality (CLAUDE.md: one definition, never hand-rolled). This is the single most likely way to build this feature and have it silently under-report.

Note the asymmetry is structural, not incidental: **the intersection is bounded by the smaller list**. That is why synchronous execution is safe here — the cost scales with the smaller user, not the larger.

## 2. Reuse seam: `ScanQuery` is already location-shaped

`scan.source.ScanQuery` carries `artistName`, `postalCode`, `latitude`, `longitude`, `radiusMiles`, `city`, `state`, `windowStart`, `windowEnd` — no owner. `ShowSource.search(ScanQuery)` returns `List<Show>` and is documented query-only, never writing.

So the sources can be driven directly with an ad-hoc location, with **no `ScanJob`, no poller, no persistence**. That is the whole reuse story: build a `ScanQuery` per (shared artist × source), call `search`, collect results. Nothing in the durable-job machinery needs to change or be contorted.

**Ticketmaster gotcha (#152):** send a geohash `geoPoint`, never a `postalCode` — Ticketmaster silently returns zero for postal codes it doesn't index. `TicketmasterService` already does this correctly *when lat/long are present*, so the entered location must be geocoded via `settings.GeocodingService` before building the query. If geocoding fails, the run must say so rather than silently returning nothing.

## 3. Flow

1. Admin opens the page, picks the other user, enters location (ZIP or city/state), radius, and months-ahead.
2. Geocode the entered location. Failure → clear error, no scan.
3. Compute the normalized intersection of the two owners' **active** (`SEED` + `APPROVED`) artists.
4. If the intersection exceeds the cap, stop and say so (see §4).
5. For each shared artist × each of the two sources, build a `ScanQuery` and call `search`.
6. Merge results, sort by date, render. Discard on reload.

## 4. The cap

A hard limit on intersection size (suggested **25 artists**, i.e. ≤50 API calls). Exceeding it renders an explicit message naming the actual count and the limit — never a truncated result set presented as complete, and never a silently slow request.

The cap is a config value, not a literal, following the app's existing `${...}` env-var convention.

## 5. Failure and empty states — all four are distinct

Each of these renders a different message. Collapsing them is the main UX failure mode:

- **Geocoding failed** — location not understood; nothing was searched.
- **No artists in common** — the two lists don't overlap at all.
- **Intersection over the cap** — N shared artists exceeds the limit of M.
- **Shared artists, no shows** — "you share N artists, none playing there in that window."

A source erroring mid-run must not fail the whole scan: sources already swallow their own failures and return empty (each logs a WARN with `source`/`artist`). Bandsintown is currently 403ing for unrelated credential reasons, so in practice today the page will run on Ticketmaster alone — it must degrade to that without looking broken.

## 6. Accessibility (acceptance criterion, not a nicety)

- Labeled form controls; the user picker is a real `<select>` with a `<label>`.
- Results in a semantic `<table>` with proper headers.
- Visible focus throughout.
- Results landing must be announced. The app already has a shared `#sr-status` live region (added for #155) — **reuse it**. CLAUDE.md warns its `hx-swap-oob="innerHTML"` is load-bearing and must not be altered.
- No custom JavaScript. CLAUDE.md is explicit on this.

## 7. Module placement

The page reads `catalog` (both owners' artists) and drives `scan` sources. `ShowController` lives in `scan` and already owns `/`. A new controller in `scan` is the natural home; `review` reading `catalog` is already an established cross-module read, so this pattern is not novel. `ModularityTests` must stay green.

`NavModelAdvice.otherOwnerEmails()` already returns the allow-list minus the admin and is already used by #136's cross-account dropdown — reuse it directly. Because this feature is admin-only, its existing admin scoping is correct as-is and needs no change.

## Non-goals

No persistence of results or scan history. No band-site/LLM source. No non-admin access. No "near miss" / one-sided-follow section. No change to the existing per-owner scan, job, or Shows behavior.

## Testing

- **Intersection is normalized**: the real production pair (`"Tom petty"` / `"Tom Petty"`) matches. A test asserting an exact-match join would find only 1 of 4 is the regression guard that matters most here.
- Only **active** (SEED/APPROVED) artists intersect — a REJECTED/REMOVED/PENDING_REVIEW artist on either side does not.
- **Owner isolation**: a non-admin is rejected; the scan reads exactly the two named owners and no third party's artists leak in.
- Each of the four empty/failure states renders its own distinct message.
- Over-cap renders the message and performs **zero** source calls.
- Geocoding failure performs zero source calls.
- Sources are called with a `geoPoint`-capable query (lat/long populated), not a bare postal code.
