# setlist-scout — working agreements

Personal concert-finder (Spring Boot / Java 21, Spring Modulith, Postgres, Thymeleaf + vendored htmx),
deployed on Render. Read this before touching anything.

## Hard rules

**Never push to a branch whose PR is open and ready for review.** If more work is needed,
**return the PR to draft first** (`gh pr ready --undo <n>`) or branch off it. A reviewer must never
chase a moving target. This is [ADR-0011](docs/adr/0011-pr-based-trunk-workflow.md), not a preference.

**Never commit directly to `main`.** Issue → branch (`<issue>-short-description`) → focused commits →
PR → squash-merge. Stop at the PR; the human merges.

**Never mark work done without running the gate.** "Should pass" is not evidence — run it and read
the output.

## The gate

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain   # ~11 min
python3 scripts/check_adrs.py                       # ADR numbering contiguous + linked in the index
```

- **Gradle cannot *launch* on JDK 25** — launch it on **21**; the toolchain forks **25** for
  compile/test. Both must be installed.
- The build **exceeds the 600s tool cap** and gets backgrounded. **Redirect to a log file, then
  poll that log in a separate command** for `BUILD SUCCESSFUL`/`BUILD FAILED` — never declare
  success early, and never just "wait for a notification" (nothing will wake you). Verify the log
  you're reading belongs to *this* run: a stale log from an earlier build will happily show a
  green line that has nothing to do with your change.
- **Async-await flakes:** integration tests wait on `@Async` AFTER_COMMIT listeners via
  `awaitUntil`. Under CI load (a container per test class, contended Hikari pools) these can take
  far longer than locally. If one fails on CI but passes locally — `PollerFlowTest.expandHappyPath`
  is the usual suspect — suspect the deadline before suspecting the code. Raising `AWAIT_TIMEOUT`
  is free: the loop returns as soon as the condition holds, so it only bounds the failure path.
- Docker Desktop must be running (Testcontainers).
- Harmless: Hikari / `eventPublicationRegistry` shutdown WARNs at the end of every green build.

## Invariants that have each caused a production bug

**Publish events inside a committing transaction.** `@ApplicationModuleListener` is AFTER_COMMIT —
a publish with no committing transaction around it is silently dropped and the listener never fires.
Query slow externals *outside* the transaction first, then publish in a short `TransactionTemplate`.
See [ADR-0024](docs/adr/0024-event-and-durable-write-invariant.md).

**Idempotent writes inside a listener must be `INSERT … ON CONFLICT … DO NOTHING`** — never
`existsBy` + `save` + catch. A constraint race poisons the whole listener transaction (Postgres
"current transaction is aborted"), which also breaks Modulith's own completion write → endless
redelivery.

**A Modulith `Scenario` test is a false green.** It wraps the publish in its own transaction, so it
passes even when the production publish site doesn't commit. Every event flow needs a real-path
Testcontainers test that drives the **actual** publisher and asserts the persisted effect.

**`ddl-auto: validate` is on.** Every schema change needs a Flyway migration *and* a matching entity
mapping, or the app won't boot.

## Gotchas that have wasted real time

- **Flyway versions string-sort wrong**: `ls | sort` puts V10 before V9. Use `sort -V`.
- **Migrations against live data**: profile prod read-only first (the Render MCP can query), state
  the expected row counts as acceptance criteria, and test every skip/safety case. A DELETE
  migration must prove nothing loses its last referencing row.
- **`artist (owner, name)` uniqueness is case- AND punctuation-sensitive.** Use
  `catalog.ArtistNameNormalizer` / `ArtistNameMatcher` for name equality — one definition, app-wide.
  Never hand-roll normalization (an ASCII-stripping regex collapses every non-Latin name to the same
  empty key).
- **Thymeleaf only resolves expressions inside `th:*` attributes** — `hx-get="@{/x}"` ships the
  literal `@{/x}`. Use `th:hx-get`.
- **Thymeleaf fragment parameters leak into the content fragment** — naming a layout parameter the
  same as a model attribute silently shadows it.
- **GitHub GraphQL gets rate-limited** (so `gh pr create` fails); fall back to
  `gh api -X POST repos/<owner>/<repo>/pulls --input -`.
- **`.superpowers/` is agent scratch** and is gitignored — never commit it.

## Conventions

- **TDD**: failing test → implement → green → commit. **Mikado** for refactors: green at every step.
- **Modulith boundaries** (`catalog`, `scan`, `expansion`, `settings`, `review`, `shared`) are
  enforced by `ModularityTests`. `shared` is OPEN. A module writes its own data; events cross
  boundaries.
- **Owner-scope everything** — the app is multi-tenant by `owner` (email). Every query, action, and
  page must be scoped, and it should be asserted in tests.
- Status changes go through `catalog.ArtistActivationService` so the domain events fire (jobs
  enqueue/cancel). Never a direct repository save.
- ADRs live in `docs/adr/`, numbered contiguously and linked from the index — `check_adrs.py`
  enforces both.
