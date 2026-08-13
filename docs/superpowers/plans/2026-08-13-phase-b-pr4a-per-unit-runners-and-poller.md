# Phase B — PR4a: per-unit runners + paced poller (gated OFF) (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Build the execution engine that drains `scan_job`/`expand_job` — a per-`(owner, artist, source)` unit runner for each module, plus a paced `@Scheduled` claim-lease poller — **behind an env flag that defaults OFF**. The old `ShowScanScheduler.scan()` batch still drives everything, so **PR4a is behavior-unchanged**; PR4b flips the flag, backfills, and retires the batch (the cutover).

**Architecture:** Each module gets a `…UnitRunner` (runs ONE `(artist, source)` job: query that adapter, persist/publish, update the job row) and a `…Poller` (`@Scheduled` tick → claim a batch of due, unclaimed rows via `FOR UPDATE SKIP LOCKED` → run each unit → reschedule on success / back off on failure). Scan reuses PR1's `ScanQuery` + `resolveSiteUrl` + `persistNew` (extracted from `ShowAggregationService`); expansion reuses PR2's `RelationSource` adapters (extended with a per-source `classification()` + `note()`) and PR3a's `CandidateDiscovered` publish path. Pollers are `@ConditionalOnProperty` (default false).

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Modulith 1.3.12, Postgres + Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers. Build: `JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem ./gradlew …`.

## Global Constraints
- **PR4a is behavior-unchanged in prod.** Pollers default OFF (`SCAN_POLLER_ENABLED`/`EXPAND_POLLER_ENABLED` = false); the whole-fleet `ShowScanScheduler.scan()` and `AsyncScanRunner` stay. Do NOT delete them, do NOT flip the flags on — that's PR4b.
- **`ModularityTests` verify() green.** Runners/pollers stay in their owning modules; scan runner may read `settings`/`catalog` (exposed); expansion runner publishes `shared.events`. No cycles.
- **⚠️ Publish inside a committed tx** (the PR3a lesson): the expansion runner publishes `CandidateDiscovered` — it MUST run inside a committed transaction (the unit-run method is `@Transactional`, or wrap the publish) or the async catalog listener won't fire. The scan runner writes `Show` rows directly (no event).
- **⚠️ Real-path tests, not mocks** (the PR3a/PR3b lesson): each poller gets a Testcontainers test that enqueues a real job, runs the poller tick against real Postgres (mock only the external HTTP adapters), and asserts the persisted effect (a `Show` row / a published-then-persisted candidate) + the job rescheduled. Unit-test the claim SQL against real Postgres (concurrency: two pollers don't double-claim).
- **Behavior parity for a unit:** a scan unit for `(artist, source)` produces the same shows the per-artist `ShowAggregationService` produced for that source (same `ScanQuery`, same `persistNew` dedup). An expansion unit for `(artist, source)` publishes the candidates that source yielded — with a **per-source note** (no confirmed-by-both) and **tribute only for SEED artists** (per the 2026-08-13 decisions).
- Keep-green each task; Docker up → full `./gradlew build`. Commit trailer + branch `86-pr4-poller-and-cutover` (no worktree).

## Design decisions (agreed 2026-08-13)
- **Per-source expansion note, no confirmed-by-both.** Each `RelationSource` declares its `ArtistSource classification()` and a `String note(String baseArtist)`:
  - `MusicBrainzRelationSource`/`DiscogsRelationSource` → `MEMBER_EXPANSION`, `"member/lineup relation of " + base`.
  - `LastFmSimilarSource` → `SIMILAR_EXPANSION`, `"similar to " + base + " (via Last.fm)"`.
  - `SimilarLlmSource` → `SIMILAR_EXPANSION`, `"similar to " + base + " (via LLM)"`.
  - `TributeLlmSource` → `TRIBUTE_EXPANSION`, `"tribute/cover act for " + base`.
- **Tribute is SEED-only.** `ExpandJobListener` must NOT enqueue the tribute-llm job for non-SEED artists (and the runner/poller never runs one). Since `ArtistActivated(owner, artistId, name)` carries no status, the listener reads the artist's status (via catalog `ArtistRepository`, an exposed read) and skips the tribute source unless SEED.
- **Poller mechanics (env, defaults):** tick 90s (`SCAN_TICK_MS`/`EXPAND_TICK_MS`), batch 20 (`…_BATCH_SIZE`), lease 5m, backoff `min(interval, 10min·2^attempts)`, park after 6 attempts (status FAILED, WARN, no more retries), cadence `SCAN_INTERVAL`=14d / `EXPANSION_INTERVAL`=28d (resolvable per source id, falling back to the interval), per-source max-rows-per-tick cap. `@ConditionalOnProperty(name="setlistscout.scan-poller-enabled", havingValue="true")` (default absent = false), same for expand.

---

### Task 1: `RelationSource` gains `classification()` + `note(base)`; update the 5 adapters
**Files:** modify `expansion/source/RelationSource.java` + the 5 adapters + `RelationSourceAdaptersTest`.
- Add `com.robsartin.setlistscout.catalog.ArtistSource classification();` and `String note(String baseArtist);` to the port. (expansion→catalog for `ArtistSource` is an existing exposed edge — fine.)
- Implement per the design table above in each adapter (member/similar-via-source/tribute).
- [ ] TDD: extend the adapter test to assert each adapter's `classification()` + `note("Dawes")`. Commit `PR4a: RelationSource declares classification + per-source note`.

### Task 2: `expansion.ExpandUnitRunner` — run one expand job (publish CandidateDiscovered)
**Files:** create `expansion/ExpandUnitRunner.java` + test.
- `@Transactional void run(String owner, Long artistId, String sourceId, String artistName)`: find the `RelationSource` by `sourceId` (from injected `List<RelationSource>`); if absent, no-op-warn; else for each `name` in `source.related(artistName)` skip blank, else `publisher.publishEvent(new CandidateDiscovered(owner, name, source.classification().name(), artistName, source.note(artistName)))`. (Runs in a committed tx so the catalog listener fires; the catalog listener already applies the name-guard + dedup.)
- Deps: `List<RelationSource>`, `ApplicationEventPublisher`.
- [ ] TDD (unit, mocked publisher + a mocked RelationSource by id): asserts one CandidateDiscovered per related name with the source's classification/note; blank skipped; unknown sourceId → no-op. Commit `PR4a: ExpandUnitRunner publishes CandidateDiscovered per source`.

### Task 3: fix `ExpandJobListener` — tribute enqueue is SEED-only
**Files:** modify `expansion/ExpandJobListener.java` + test. (Inject catalog `ArtistRepository` to read status.)
- `onArtistActivated`: for each `RelationSource s`, enqueue as today EXCEPT: if `s.classification() == TRIBUTE_EXPANSION` (or `s.id().equals("tribute-llm")`), only enqueue when the artist's status is `SEED` (read via `artistRepository.findByIdAndOwner(e.artistId(), e.owner())`). Non-tribute sources always enqueue. Keep `insertIfAbsent` idempotency.
- [ ] TDD (unit): a SEED artist gets all 5 sources enqueued; an APPROVED artist gets the 4 non-tribute sources but NOT tribute-llm. Commit `PR4a: ExpandJobListener enqueues tribute only for SEED artists`.

### Task 4: extract `scan.ScanUnitRunner` from `ShowAggregationService`
**Files:** create `scan/ScanUnitRunner.java` + test; modify `scan/ShowAggregationService.java` (extract `resolveSiteUrl`/`persistNew`/`ScanQuery`-building into reusable form — either move them to the runner and have `ShowAggregationService` delegate, or share a helper). `ShowScanScheduler`/`ShowAggregationService.scanForShows` STILL WORK (batch unchanged).
- `int run(String owner, Long artistId, String sourceId)`: find the Artist (catalog repo) + owner's `SearchSettings`; if either missing, no-op; build the `ScanQuery` (with `resolveSiteUrl(artist)` — the one write, band-site URL cache); find the `ShowSource` by `sourceId`; `persistNew(owner, source.search(query))`; return saved count. Reuse the existing `resolveSiteUrl` + `persistNew` logic (extract so both the runner and the legacy batch use one copy — avoid duplicating).
- Deps: `List<ShowSource>`, `ArtistRepository`, `ShowRepository`, `SearchSettingsRepository`, `MusicBrainzService`.
- [ ] TDD (unit, mocked repos + a mocked ShowSource): running a unit builds the right ScanQuery (fingerprint fields, resolved URL) and persists via `persistNew`; missing artist/settings → no-op. Commit `PR4a: ScanUnitRunner runs one (artist,source) scan; ShowAggregationService reuses the shared persist path`.

### Task 5: claim SQL on both repos (`claimDue`)
**Files:** modify `scan/ScanJobRepository.java` + `expansion/ExpandJobRepository.java` + a Testcontainers repo test.
- Add native `@Modifying @Query(nativeQuery=true)` `List<…Job> claimDue(Instant now, Instant leaseCutoff, int batch)`:
  ```sql
  UPDATE scan_job SET claimed_at = :now, status = 'RUNNING'
  WHERE id IN (SELECT id FROM scan_job
               WHERE next_due_at <= :now AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
               ORDER BY next_due_at LIMIT :batch FOR UPDATE SKIP LOCKED)
  RETURNING *
  ```
  (Postgres `UPDATE … RETURNING *` maps back to the entity; verify the native return mapping. If mapping `RETURNING *` to the entity is fiddly, claim ids then re-select — but prefer RETURNING.)
- [ ] TDD (Testcontainers): due unclaimed rows are claimed (claimed_at set, status RUNNING); not-yet-due and freshly-leased rows are skipped; two concurrent `claimDue` calls never return the same row (SKIP LOCKED). Commit `PR4a: claimDue (FOR UPDATE SKIP LOCKED) on scan/expand job repos`.

### Task 6: `scan.ScanPoller` + `expansion.ExpandPoller` (gated OFF)
**Files:** create `scan/ScanPoller.java`, `expansion/ExpandPoller.java` + config properties + unit tests.
- Each: `@Component @ConditionalOnProperty(name="setlistscout.<scan|expand>-poller-enabled", havingValue="true")`, a `@Scheduled(fixedDelayString=…tick, initialDelayString=…)` `tick()`: `claimDue(now, now-lease, batch)`; for each claimed job, run the unit runner; on success set `last_run_at=now, next_due_at=now+interval(source), attempts=0, claimed_at=null, status=SCHEDULED`; on `RuntimeException` set `attempts++, last_error=msg, next_due_at=now+backoff(attempts), claimed_at=null, status=FAILED` (park at 6). Per-source cadence + max-rows-per-tick cap from config. Save each job update in its own short tx (don't hold a connection across the slow adapter call — run the unit, then update the job).
- Add `setlistscout.scan-poller-enabled`/`expand-poller-enabled` (default false) + `scan-interval`/`expansion-interval`/tick/batch/lease/backoff-cap/park-cap to `AppProperties` (or a dedicated `PollerProperties`) + `application.yml` defaults.
- [ ] TDD (unit, mocked repo returning a claimed job + mocked runner): success reschedules with attempts=0; a thrown RuntimeException backs off + increments attempts + parks at the cap; the poller bean is absent when the flag is off (a `@SpringBootTest` slice or context test asserting no bean by default). Commit `PR4a: paced claim-lease pollers (gated off) for scan + expand`.

### Task 7: real-path poller integration tests + gate
**Files:** create `PollerFlowTest.java` (`@SpringBootTest` with `setlistscout.scan-poller-enabled=true`/`expand-poller-enabled=true` via `@TestPropertySource`, Testcontainers, external HTTP adapters `@MockBean`ed).
- Enqueue a due `scan_job` for a real artist+settings, mock the `ShowSource` to return a show, invoke the poller tick (or await it) → assert a `Show` row persisted AND the job rescheduled (next_due_at advanced, claimed_at null, attempts 0). Same for an `expand_job` → mock the `RelationSource` → assert a PENDING_REVIEW Artist persisted (via the real CandidateDiscovered→listener path) AND the job rescheduled. Include a failure case: the adapter throws → job FAILED, attempts=1, next_due_at backed off. Bounded awaits, no sleeps.
- [ ] Drive green. **Gate:** `ModularityTests` green, `./gradlew --no-daemon clean build` BUILD SUCCESSFUL. Commit `PR4a: real-path poller integration tests`.

---

## PR4b (the cutover — NOT this plan; outlined for context)
Flip `scan-poller-enabled`/`expand-poller-enabled` default → true; one-time **jittered backfill** (a `CommandLineRunner`/startup step: for every active SEED/APPROVED artist with no jobs, enqueue via `insertIfAbsent` with `next_due_at` spread over the next few hours, jittered — tribute only for SEED); **delete `ShowScanScheduler.scan()` + `AsyncScanRunner`**; rewire manual "Scan now"/"Expand now" (`ShowController.scanNow`, `ReviewController.expandNow`) to set the owner's jobs `next_due_at = now` (and adapt the "Scanning…" indicator / `ScanStateService` / `/scan-status` UX to the async model); **ADR** (per-unit work model). This is the small, risky, single-deploy cutover.

## Self-Review
- **Spec coverage:** PR4a = the poller + per-unit runners + claim/backoff/cadence, gated off (behavior unchanged); PR4b = enable + backfill + retire + rewire + ADR. Matches the spec's Phase B "poller/cutover", split for safety.
- **Lessons applied:** expansion runner publishes in a committed tx (PR3a); real-path Testcontainers tests for both pollers + the claim SQL concurrency (PR3a/PR3b); idempotent enqueue already in place (PR3b).
- **Behavior parity:** a scan unit == that source's slice of the per-artist batch (same ScanQuery/persistNew); expansion per-source note + SEED-only tribute per the agreed decisions.
- **Empirical spots:** the `claimDue` `RETURNING *` entity mapping and the pollers' real-path async awaits are acceptance-gated (drive them green; the fallback for RETURNING is claim-ids-then-select).
