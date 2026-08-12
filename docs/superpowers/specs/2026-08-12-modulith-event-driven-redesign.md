# Modulith + event-driven per-unit scan/expand — design

## Goal

Replace the "scan the whole fleet at once, every 3 days" batch with **per-artist, individually
scheduled, event-triggered** scanning and expansion, inside a **Spring Modulith** modular monolith
whose boundaries are enforced in CI. This removes the coupling that keeps biting us: a heavy
all-at-once scan (that also fired at startup), one flaky source aborting an entire run, and a newly
added artist not being scanned until the next full cycle.

## Why now (the pains this targets)

- **All-at-once effort:** the scheduler loops every owner × every artist synchronously; a manual
  "Scan now" does the same for one owner. It's the source of the heavy startup scan and the API
  thundering-herd.
- **Distant coupling:** one source throwing aborted the whole expansion (band-aided in #77);
  changing one artist meant re-running everything.
- **No immediacy:** adding an artist (David's "I added James Taylor") didn't scan it until a full
  cycle — bad feedback.

## Constraints

- **Render free-tier spins down** → in-memory events are lost on restart; work state and event
  delivery must be **durable**. (Spring Modulith's Event Publication Registry persists published
  events and re-delivers incomplete ones on restart; the job tables are the durable work backbone.)
- **Prod is live** → migrate **incrementally, keeping the build green at every step** (Mikado), not
  a rewrite. Behavior must not regress.
- **Flyway owns the schema** (ddl-auto=validate); new tables ship as `V__` migrations.
- Single-user-ish app: **no message broker, no distributed anything** — in-process Modulith events
  only. Module boundaries are for structure, not scale.
- Builds on the just-shipped structured logging (#69): per-unit work maps cleanly onto the existing
  per-operation correlation ids.

## Scope decision

**Full Spring Modulith adoption AND the per-unit redesign**, as one initiative executed with ADRs,
CI-enforced boundary verification, TDD, and the Mikado method via subagents. Mikado supplies the
phasing: because the redesign depends on the module structure, the graph lands the boundaries +
`verify()` gate first (behavior unchanged), then the per-unit work model on top.

## Modules (fine-grained; each owns its own controllers/templates)

Top-level packages under `com.robsartin.setlistscout`; `verify()` fails the build if a module
touches another module's internal (non-API) types.

| Module | Owns | External clients |
|---|---|---|
| **catalog** | `Artist` + all status transitions (add-seed, approve, reject, remove, unreject), the add box + file upload, `ArtistSeedService` (name guard) | — |
| **scan** | per-artist show search, `Show`, `ShowRepository`, `scan_job`, the shows page `/` | Ticketmaster, Bandsintown, band-site scraper + TourPageLlm |
| **expansion** | related-artist discovery, `expand_job` | Discogs, Last.fm, SimilarArtist LLM, Tribute LLM |
| **review** | the pending-review UI (radios/approve/reject/unreject) | — |
| **settings** | `SearchSettings`, geocoding, `/settings` | Zippopotam |
| **shared** | MusicBrainz client (used by scan for the official homepage AND by expansion for related artists), `CurrentUser`, `observability` (correlation) | MusicBrainz |

`shared` is a permitted dependency for every module; domain modules do not depend on each other
except through public API types and events.

## Events (Spring Modulith `@ApplicationModuleListener` — async + transactional + durable registry)

All `Artist` writes stay in **catalog**; other modules react to or request via events.

| Event | Published by | Consumed by | Meaning |
|---|---|---|---|
| `ArtistActivated(owner, artistId, name)` | catalog | scan, expansion | An artist entered SEED or APPROVED → becomes scan/expand-eligible. Enqueue a `scan_job` and `expand_job` (due now). |
| `ArtistDeactivated(owner, artistId)` | catalog | scan, expansion | Rejected/removed → cancel/delete its jobs. |
| `SettingsChanged(owner)` | settings | scan | Location/radius/window changed → re-due the owner's `scan_job`s (stale location). |
| `CandidateDiscovered(owner, name, source, discoveredVia, note)` | expansion | catalog | A related/similar/tribute candidate was found → catalog persists it PENDING_REVIEW (with the name-plausibility guard + owner-dedup). |

`ArtistActivated` unifies "seed added" and "approved" into one trigger. `CandidateDiscovered`
routes candidate persistence back through catalog so the `Artist` aggregate has a single writer.

## Per-unit work model

Two tables, one row per unit of work:

`scan_job` / `expand_job`:
- `id`, `owner`, `artist_id` (unique together: `(owner, artist_id)`)
- `status` — `SCHEDULED | RUNNING | FAILED`
- `attempts` (int), `last_error` (nullable)
- `last_run_at` (nullable), `next_due_at` (indexed)
- `claimed_at` (nullable) — the lease; a claim sets it to now, released on completion
- `scan_job` only: **`location_fingerprint`** — a hash/snapshot of the owner's settings
  (postal_code, radius, months) captured at scheduling time, so a `SettingsChanged` can mark
  the row stale and re-due it.

Lifecycle:
1. **Enqueue** on `ArtistActivated` (`next_due_at = now`, status SCHEDULED). Idempotent — upsert on
   `(owner, artist_id)`.
2. **Claim** — the poller selects due, unclaimed rows and atomically sets `claimed_at = now`
   (a conditional update, so two ticks can't grab the same row).
3. **Run one unit** — scan a single artist at the owner's location / expand a single artist.
4. **Success** → `last_run_at = now`, `next_due_at = now + interval`, `attempts = 0`,
   `claimed_at = null`, status SCHEDULED.
5. **Failure** → `attempts++`, `last_error` set, `next_due_at = now + backoff(attempts)`,
   `claimed_at = null`, status FAILED (still re-tried until a cap, then parked with a WARN).

## Scheduling & pacing

- A light `@Scheduled` tick (default every ~1–2 min, configurable) in scan and in expansion selects
  `WHERE next_due_at <= now AND (claimed_at IS NULL OR claimed_at < now - lease)` ordered by
  `next_due_at`, `LIMIT batch`, and processes the batch **rate-limited** (respects MusicBrainz
  ~1 req/sec, the Ticketmaster daily budget, and LLM cost).
- **Cadences (env-configured, defaults):**
  - `SCAN_INTERVAL` default **14 days**
  - `EXPANSION_INTERVAL` default **28 days**
  - plus immediate enqueue **on new/approved artist** (the `ArtistActivated` trigger)
  - `SCAN_TICK_MS`, `SCAN_BATCH_SIZE`, initial delay, and per-tick pacing also configurable.
- No startup stampede: the tick drains due work gradually; nothing runs a full-fleet pass at boot
  (this also subsumes #72's "no scan at startup").

## Flows

- **Add seed / approve** → catalog `ArtistActivated` → scan + expansion enqueue jobs due-now →
  poller runs them within a tick. Immediate feedback.
- **Reject / remove** → catalog `ArtistDeactivated` → jobs cancelled.
- **Settings change** → settings `SettingsChanged` → scan re-dues the owner's `scan_job`s
  (refresh `location_fingerprint`).
- **Recurring** → jobs reschedule themselves via `next_due_at + interval`.
- **Manual "Scan now" / "Expand now"** → set the owner's jobs `next_due_at = now`; the poller
  picks them up (keeps one execution path, no separate async runner).

## Cascade gate (no expansion explosion)

Expansion emits `CandidateDiscovered`; catalog persists candidates as **PENDING_REVIEW** only.
PENDING artists are **not** activated, so they generate no `scan_job`/`expand_job` until a human
approves them (which fires `ArtistActivated`). Expansion is therefore **one level deep, gated by
review** — the same guarantee as today.

## Migration (Mikado, keep-green, subagents, TDD)

**Phase A — Modulith structure, behavior unchanged.**
- Add `spring-modulith-starter-core` + `spring-modulith-starter-events-jpa` (+ optionally
  `-actuator`/`-observability`/`-docs`).
- Add the **event-publication registry** table via Flyway (`event_publication`).
- Create the 5 module packages; move classes in; give each module a clear public API and hide
  internals in sub-packages.
- Convert cross-module calls to public-API calls or the events above.
- Add `ModularityTests` running `ApplicationModules.of(App.class).verify()`; wire it into CI as a
  gate (fails the build on any boundary violation). This is the enforced arch test.
- Keep every existing test green at every Mikado step.

**Phase B — per-unit redesign on the clean structure.**
- Add `scan_job` / `expand_job` tables (Flyway) + repositories.
- Add the enqueue-on-`ArtistActivated`, re-due-on-`SettingsChanged`, cancel-on-`ArtistDeactivated`
  listeners.
- Add the paced due-poller + claim/lease + retry/backoff.
- Wire `CandidateDiscovered` from expansion → catalog persistence.
- Retire the whole-fleet `ShowScanScheduler.scan()` batch and the request-thread `AsyncScanRunner`
  (manual triggers become "set due-now").
- TDD each unit; the Mikado graph orders the leaves; subagents implement them.

## ADRs to write

1. Adopt Spring Modulith with module boundaries enforced by `verify()` in CI.
2. Event-driven inter-module communication via Modulith application events + the durable
   publication registry.
3. Per-unit, individually scheduled scan/expand work model (job tables + paced poller) replacing
   the whole-fleet batch; env-configured cadences (scan 14d / expand 28d) + on-activation triggers.

## Testing

- **Boundary:** `ModularityTests.verify()` (unit-level, runs in CI).
- **Events:** Modulith's `@ApplicationModuleTest` + `Scenario` API to assert that publishing
  `ArtistActivated` enqueues the jobs, `SettingsChanged` re-dues scans, `CandidateDiscovered`
  persists a PENDING artist (guarded/deduped).
- **Work model:** claim is atomic (no double-run); success reschedules; failure backs off; a
  fingerprint mismatch re-dues. Plain unit tests where possible; Testcontainers for the JPA/
  registry paths (CI, since Docker is unavailable locally).
- **Regression:** existing behavior (show search results, expansion candidates, the review gate,
  the shows/artists pages) stays green throughout Phase A.

## Out of scope (YAGNI)

- No message broker / external queue — in-process Modulith events only.
- No distributed services, no microservices.
- No area-browse / "what's near me", no new external sources (separate, declined).
- No per-artist adaptive cadence (fixed interval + on-activation is enough for now).
