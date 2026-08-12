# Phase A — Modulith structure (Mikado graph)

**Goal:** `ModularityTests.verify()` passes with 5 domain modules (`catalog`, `scan`, `expansion`,
`review`, `settings`) + `shared`; the durable event-publication registry is in place; **behavior is
unchanged and the build stays green at every step**. (Per-unit job model + domain events that drive
it are Phase B, not here.) Spec: `docs/superpowers/specs/2026-08-12-modulith-event-driven-redesign.md`.

**Keep-green invariant:** every increment must leave `compileJava compileTestJava` + the existing
tests green. `verify()` itself only goes green at the end — it is added as the LAST step (as a
failing-then-passing test), because it checks the whole structure at once. Run each increment as a
subagent, Mikado-style: attempt a move, if it breaks something note the prerequisite, revert, do the
prerequisite first.

Run gradle with `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem`. Docker is unavailable locally →
the Testcontainers boot test (which confirms the app still boots with each move) runs in CI.

**Status:** deps + all 5 domain modules' classes relocated & green (`shared` cea7e63, `settings`
d72769d, `catalog` d839b8f, `expansion` fe64983, `scan` 31e0e2a). NEXT = the controller-split half:
`review` module (split ArtistController), move the other controllers + config to their modules,
resolve verify() boundary crossings to public APIs, add the event registry, turn on `verify()`,
ADRs, PR.

## Prerequisite tree (leaves first — do bottom-up)

- [x] **Add Spring Modulith deps** — `spring-modulith-starter-core` (+ `-starter-test`), BOM 1.3.12
      in `build.gradle.kts`. Compiles + tests green. (commit on branch `modulith-redesign`)
- [x] **`shared` module** — moved MusicBrainz client, `CurrentUser`, the `observability` package into
      `com.robsartin.setlistscout.shared.*`; imports fixed, green. (commit cea7e63; also moved
      MusicBrainzServiceTest + widened two test-seam visibilities to public.)
- [ ] **`settings` module** — move `SearchSettings`, `GeocodingService` (+ its `GeoResult`), and a new
      `SettingsController` (extract the `POST /settings` handler out of `ShowController`) into
      `…settings.*`. Green.
- [ ] **`catalog` module** — move `Artist`, `ArtistSource`, `ArtistStatus`, `ArtistRepository`,
      `ArtistSeedService`, `DataInitializer` seed logic, and the artist add/upload endpoints into
      `…catalog.*`. Expose a public API for the reads/writes other modules need (activation status,
      tribute-artist names for the shows page, candidate persistence). Green.
- [ ] **`expansion` module** — move `ExpansionService` + Discogs/Last.fm/SimilarLlm/TributeLlm into
      `…expansion.*`; introduce the `RelationSource` port + one adapter per source (`expansion.source.*`).
      Route candidate persistence to catalog's public API for now (event conversion happens later).
      Green.
- [ ] **`scan` module** — move `ShowAggregationService`, `Show`, `ShowRepository`, `AsyncScanRunner`,
      `ScanStateService`, `ShowScanScheduler`, Ticketmaster/Bandsintown/BandSiteScraper/TourPageLlm,
      and the shows page (`/`, `/scan-now`, `/scan-status`) into `…scan.*`; introduce the `ShowSource`
      port + adapters (`scan.source.*`). Green.
- [ ] **`review` module** — move the pending-review endpoints (`/artists/review`, approve/reject/
      unreject/remove, the pending fragments) into `…review.*`; it invokes catalog's public API for
      status changes. Decide ownership of the composite `/artists` page. Green.
- [ ] **Resolve remaining boundary crossings** surfaced by a trial `verify()` — convert any leftover
      cross-module calls to public-API calls (Phase A) rather than events; only introduce a domain
      event where an existing cross-module *write* needs decoupling. Green.
- [ ] **Event-publication registry** — add `spring-modulith-starter-events-jpa`, a Flyway
      `V__event_publication.sql` table, config. Green (registry is inert until Phase B uses events).
- [ ] **Turn on enforcement** — add `ModularityTests` running
      `ApplicationModules.of(SetlistScoutApplication.class).verify()`; watch it pass. It runs in the
      existing CI Build&test job → the boundary gate is now enforced.
- [ ] **ADRs** — write ADR: "Adopt Spring Modulith with CI-enforced boundaries"; ADR: "Event-driven
      inter-module communication via the durable registry". Update `docs/adr/README.md`; `check_adrs.py`
      green.
- [ ] **PR** — spec + Phase A on branch `modulith-redesign` → PR to main; CI green (incl. `verify()`).

## Notes / expected pitfalls
- `ShowController` currently mixes shows-page (scan), settings (settings), and reads catalog's
  tribute names (#71) — it splits across modules; the moves above pull each part to its owner.
- `ArtistController` mixes catalog (add/upload) and review (approve/reject) — splits into catalog +
  review controllers.
- `DataInitializer` seeds catalog + settings — split its two responsibilities to the two modules.
- MusicBrainz is used by scan (homepage) and expansion (related) → lives in `shared`.
