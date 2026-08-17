# Architecture Decision Records

## Universal

- [10. Record architecture decisions with ADRs](0010-record-architecture-decisions.md) — _Accepted_
  Architecturally significant decisions — choices that shape structure, dependencies, interfaces, or the way the team works — need a durable record.
  Related: [13. Keep developer and user documentation current](0013-keep-documentation-current.md)
- [11. Integrate via a PR-based trunk workflow](0011-pr-based-trunk-workflow.md) — _Accepted_
  We want `main` to stay releasable at all times, changes to be reviewable in coherent units, and history to be legible.
  Related: [12. Use the Mikado Method to keep the build green](0012-mikado-method-for-changes.md), [13. Keep developer and user documentation current](0013-keep-documentation-current.md)
- [12. Use the Mikado Method to keep the build green](0012-mikado-method-for-changes.md) — _Accepted_
  Large refactorings, and changes that ripple across a codebase, tempt us into long stretches where nothing compiles and nothing is committable.
  Related: [11. Integrate via a PR-based trunk workflow](0011-pr-based-trunk-workflow.md)
- [13. Keep developer and user documentation current](0013-keep-documentation-current.md) — _Accepted_
  Documentation that lags the code is worse than none — it misleads.
  Related: [10. Record architecture decisions with ADRs](0010-record-architecture-decisions.md), [11. Integrate via a PR-based trunk workflow](0011-pr-based-trunk-workflow.md)
- [14. Declare an explicit license and copyright](0014-license-and-copyright.md) — _Accepted_
  A repository with no license is "all rights reserved" by default — others (and future us) have no clear terms for use, and intent is ambiguous.

## Language

- [15. Build JVM projects with Gradle](0015-jvm-build-with-gradle.md) — _Accepted_
  JVM projects need a consistent build tool, dependency management, and package organization so repositories are predictable to build and navigate, and so shared tooling (formatting, coverage, arch tests) can be applied the same way everywhere.

## Uncategorized

- [0001: Member/lineup expansion sources](0001-member-lineup-expansion-sources.md)
  The seed band list needs to expand to include individual members' other projects and side bands (e.g., a Tom Petty fan should also see Mike Campbell & the Dirty Knobs).
- [0002: Similar-artist expansion sources](0002-similar-artist-expansion-sources.md)
  Beyond lineup relationships, the list should expand with taste-based "similar artists" — bands that sound alike but share no members (e.g.
- [0003: Show search sources](0003-show-search-sources.md)
  Once the artist list is finalized, the service needs to find their upcoming shows near Austin.
- [0004: Human review gate for artist expansion](0004-human-review-gate-for-expansion.md)
  Automated expansion (ADRs 0001, 0002) will produce false positives — loosely related artists, name collisions, or LLM suggestions that don't actually fit.
- [0005: Output as a live web page, not file/email](0005-output-as-web-page.md)
  The service needs to present found shows in a usable form.
- [0006: Scan frequency](0006-scan-frequency.md) — _Superseded by 0023_
  The scheduled job re-runs expansion and show search on an interval.
  Related: [0023: Per-unit event-driven scan/expand work model](0023-per-unit-event-driven-scan-work-model.md)
- [0007: Geographic scope, runtime-configurable](0007-geographic-scope-runtime-configurable.md)
  Initial scope was "anywhere in Texas," later narrowed to "near Austin only — even San Antonio is too far," with an explicit requirement that the scope be easy to change without redeploying.
- [0008: Hosting on Render](0008-hosting-on-render.md) — _Superseded by 0016_
  The app needs to run continuously (for the scheduler and the always-available web page) without relying on the developer's own machine, since development and operation are currently phone-only.
  Related: [16. Hosting on Render requires a Dockerfile, not native Java support](0016-hosting-on-render-requires-a-dockerfile-not-native-java-support.md)
- [0009: Google OAuth with a per-user allow-list](0009-google-oauth-single-user.md)
  The repo and deployed app are public.
- [16. Hosting on Render requires a Dockerfile, not native Java support](0016-hosting-on-render-requires-a-dockerfile-not-native-java-support.md) — _Accepted_
  [0008](0008-hosting-on-render.md) assumed Render's build would auto-detect a JVM/Maven project the way it auto-detects Node, Python, Ruby, Go, Rust, and Elixir.
  Related: [0008: Hosting on Render](0008-hosting-on-render.md), [15. Build JVM projects with Gradle](0015-jvm-build-with-gradle.md)
- [0017: Tribute/cover band expansion sources](0017-tribute-band-expansion-sources.md)
  Tribute acts don't appear in MusicBrainz lineup relations or Last.fm "similar artist" queries, so neither existing source finds them.
- [0018: ZIP-code search location with Zippopotam.us geocoding](0018-zip-code-search-location.md)
  A ZIP code is a more precise, familiar way to say "near here" than city/state, and enables a real distance radius for both Ticketmaster and Bandsintown.
  Related: [0026: Ticketmaster geoPoint search, not postalCode](0026-ticketmaster-geopoint-search.md)
- [0019: Band official-site tour scraping (hybrid JSON-LD + LLM)](0019-band-site-tour-scraping.md)
  Many artists announce tour dates only on their own site; scrape them via MusicBrainz-discovered URLs, schema.org Event JSON-LD, and an LLM fallback.
- [0020: Schema migrations via Flyway (replacing ddl-auto=update)](0020-flyway-schema-migrations.md)
  ddl-auto=update can't add a NOT NULL column to a populated table (it crashed prod after multi-tenancy); Flyway versioned migrations + ddl-auto=validate replace it.

## Modularity

- [0021: Adopt Spring Modulith with enforced boundaries](0021-adopt-spring-modulith-with-enforced-boundaries.md) — _Accepted_
  Application grew as a flat package with no structural boundaries; Spring Modulith establishes enforced module boundaries (`catalog`, `scan`, `expansion`, `settings`, `review`, `shared`) and a `ModularityTests` verifier in CI.
  Related: [12. Use the Mikado Method to keep the build green](0012-mikado-method-for-changes.md), [0022: Event-driven inter-module communication](#modularity)
- [0022: Event-driven inter-module communication](0022-event-driven-inter-module-communication.md) — _Accepted_
  Cross-module writes need durability and decoupling; Spring Modulith events with a JPA event-publication registry (Flyway `V4__event_publication.sql`) provide both. Phase A places the registry; Phase B activates publishing and listening.
  Related: [0020: Schema migrations via Flyway](0020-flyway-schema-migrations.md), [0021: Adopt Spring Modulith with enforced boundaries](#modularity)
- [0023: Per-unit event-driven scan/expand work model](0023-per-unit-event-driven-scan-work-model.md) — _Accepted_
  The old whole-fleet `@Scheduled` batch rescanned every artist on a fixed interval regardless of need; durable per-`(owner, artist, source)` jobs, enqueued/re-dued by catalog and settings domain events and drained by a paced claim-lease poller, replace it.
  Related: [0006: Scan frequency](0006-scan-frequency.md), [0022: Event-driven inter-module communication](0022-event-driven-inter-module-communication.md)
- [0024: Event and durable-write invariant](0024-event-and-durable-write-invariant.md) — _Accepted_
  Two real Phase B production bugs — an event published outside a committing transaction, and an existsBy-then-catch idempotency check that poisoned a listener's Postgres transaction — established two non-obvious rules for every publisher and listener.
  Related: [0022: Event-driven inter-module communication](0022-event-driven-inter-module-communication.md), [0023: Per-unit event-driven scan/expand work model](0023-per-unit-event-driven-scan-work-model.md)
- [0025: Blueprint-managed hosting for auto-propagating DB credential rotation](0025-blueprint-managed-hosting-for-credential-rotation.md) — _Accepted_
  A dashboard-linked Datastore URL is a snapshot, not a live reference — it doesn't follow Postgres credential rotation. Adopting Render Blueprint management (`render.yaml` + `fromDatabase` refs) makes rotation auto-propagate with no manual re-link.
  Related: [0008: Hosting on Render](0008-hosting-on-render.md), [16. Hosting on Render requires a Dockerfile, not native Java support](0016-hosting-on-render-requires-a-dockerfile-not-native-java-support.md)
- [0026: Ticketmaster geoPoint search, not postalCode](0026-ticketmaster-geopoint-search.md) — _Accepted_
  Ticketmaster's postal-code index doesn't cover every ZIP and silently returns a well-formed zero-result response for the ones it misses, indistinguishable from a legitimate "nothing nearby" — found via #152, where one production owner got zero Ticketmaster results the entire time.
  Related: [0018: ZIP-code search location with Zippopotam.us geocoding](0018-zip-code-search-location.md)
- [0027: Shared scans as a synthetic owner key, not parallel infrastructure](0027-shared-scan-synthetic-owner-key.md) — _Accepted_
  A shared scan (#163) needs everything a person's scan has — location, artist list, jobs, persisted shows — without being a person; every owner-scoped table and job already keys on an opaque string, so a shared scan gets its own `shared:<uuid>` owner key instead of parallel job tables or a nullable discriminator column that would defeat `show_event`'s uniqueness constraint.
  Related: [0021: Adopt Spring Modulith with enforced boundaries](0021-adopt-spring-modulith-with-enforced-boundaries.md), [0023: Per-unit event-driven scan/expand work model](0023-per-unit-event-driven-scan-work-model.md)
