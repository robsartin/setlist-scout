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
- [0006: Scan frequency](0006-scan-frequency.md)
  The scheduled job re-runs expansion and show search on an interval.
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
- [0019: Band official-site tour scraping (hybrid JSON-LD + LLM)](0019-band-site-tour-scraping.md)
  Many artists announce tour dates only on their own site; scrape them via MusicBrainz-discovered URLs, schema.org Event JSON-LD, and an LLM fallback.
