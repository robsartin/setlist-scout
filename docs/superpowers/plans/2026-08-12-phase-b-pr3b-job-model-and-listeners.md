# Phase B — PR3b: per-unit job model + enqueue/cancel/re-due listeners (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Add the durable `scan_job` / `expand_job` tables and the `@ApplicationModuleListener`s that populate them from the events PR3a lit up — enqueue one job per `(owner, artist, source)` on `ArtistActivated`, cancel on `ArtistDeactivated`, re-due scan jobs on `SettingsChanged`. **Jobs are populated but nothing drains them yet** (the paced poller is PR4). The old whole-fleet scan still runs, so behavior is unchanged for users this PR.

**Architecture:** `scan.ScanJob` (owns `scan_job`, incl. a `location_fingerprint`) and `expansion.ExpandJob` (owns `expand_job`) JPA entities + repos, one row per `(owner, artist_id, source)` (unique). A `scan.ScanJobListener` and `expansion.ExpandJobListener` consume the `shared.events` events. Scan enqueues one `scan_job` per injected `ShowSource`; expansion enqueues one `expand_job` per `RelationSource` (this PR introduces the generic `List<RelationSource>` injection deferred in PR2). Enqueue is idempotent (durable events can redeliver): existsBy-check + catch `DataIntegrityViolationException` on the unique constraint.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Modulith 1.3.12 (durable JPA event registry — already wired), Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers. Build: `JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem ./gradlew …`.

## Global Constraints
- **No user-visible behavior change.** Jobs are inert (no poller). The old `ShowScanScheduler.scan()` still drives scanning/expansion. `CandidateDiscovered`/candidate persistence is untouched.
- **`ModularityTests` verify() MUST stay green.** New entities/listeners stay in their owning modules; listeners consume `shared.events`; scan may read `settings` (exposed) for the fingerprint; no cycles.
- **⚠️ TRANSACTION-BOUNDARY LESSON (from PR3a — do not repeat the bug):** `@ApplicationModuleListener` is `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`. It fires only when the publisher published inside a committed transaction. PR3a already made `ArtistActivationService.changeStatus`/`onSeedCreated` and `SettingsService.updateSettings` `@Transactional`, so the events THIS PR consumes are published transactionally — good. The listener bodies themselves run in their own `REQUIRES_NEW` tx (Modulith), so the job upsert is transactional automatically. **Every listener MUST be tested through the REAL publisher path** (call `activationService.changeStatus(...)` / `settingsService.updateSettings(...)` in a `@SpringBootTest` with Testcontainers and await the job row) — NOT via Modulith `Scenario` alone (that masked the PR3a bug). A pure unit test of the listener method is fine for the enqueue LOGIC, but at least one real-path integration test per event is required.
- **Idempotent enqueue:** events can be redelivered; enqueuing the same `(owner, artist_id, source)` twice must NOT create a duplicate or throw. Use existsBy pre-check + catch `DataIntegrityViolationException` (the PR3a idempotency pattern).
- Keep-green each task: compile + affected tests; Docker up → full `./gradlew build`.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Branch `86-pr3b-job-model-and-listeners`; no worktree.

## Design decisions
- **`shared.JobStatus`** enum `{ SCHEDULED, RUNNING, FAILED }` (both job types use it identically; shared is OPEN). (Alternatively per-module, but shared avoids duplication and matches how `shared` already holds cross-module value types.)
- **`scan.ScanJob`** `@Entity @Table(name="scan_job", uniqueConstraints=@UniqueConstraint(columnNames={"owner","artist_id","source"}))`, IDENTITY id (house style, matching `Show`/`Artist`). Columns: `owner` (String, nullable=false), `artistId` (Long, `@Column(name="artist_id", nullable=false)`), `source` (String, nullable=false), `status` (`@Enumerated(EnumType.STRING)`, nullable=false), `attempts` (int, default 0), `lastError` (String, nullable), `lastRunAt` (Instant, nullable), `nextDueAt` (Instant, nullable=false, `@Column(name="next_due_at")`, indexed), `claimedAt` (Instant, nullable), `locationFingerprint` (String, `@Column(name="location_fingerprint")`).
- **`expansion.ExpandJob`** — identical minus `locationFingerprint`.
- **`location_fingerprint`** = a stable String hash of the owner's search location at enqueue time: `settings` exposes `String SettingsService.locationFingerprint(String owner)` = e.g. `Integer.toHexString(java.util.Objects.hash(postalCode, radiusMiles, monthsAhead))` from `getOrCreateSettings(owner)`. Scan uses it on enqueue and refreshes it on `SettingsChanged`.
- Use `java.time.Instant` for timestamps (UTC, monotonic for due comparisons). Add these columns to the entities via Flyway `V6` (types matching Hibernate's mapping — the `serialized_event` widen in V5 was derived empirically; do the same if `validate` complains, boot test is the gate).

---

### Task 1: `shared.JobStatus` + `scan.ScanJob` entity + `ScanJobRepository` + Flyway `V6` (scan_job) 
**Files:** Create `shared/JobStatus.java`; `scan/ScanJob.java`; `scan/ScanJobRepository.java`; `src/main/resources/db/migration/V6__scan_job.sql`. Test: `scan/ScanJobRepositoryTest.java` (Testcontainers, like the migration tests).

- Entity + enum + repo per the design above. `ScanJobRepository extends JpaRepository<ScanJob, Long>` with: `Optional<ScanJob> findByOwnerAndArtistIdAndSource(String, Long, String)`; `List<ScanJob> findByOwnerAndArtistId(String, Long)`; `List<ScanJob> findByOwner(String)`; `void deleteByOwnerAndArtistId(String, Long)`; `boolean existsByOwnerAndArtistIdAndSource(String, Long, String)`.
- `V6__scan_job.sql`: `CREATE TABLE scan_job (...)` with the columns above, `UNIQUE (owner, artist_id, source)`, and an index on `next_due_at`. Match Hibernate's mapping so `ddl-auto=validate` passes — **gate: `ApplicationContextSmokeTest` green** (boot → Flyway V1..V6 → validate). If validate complains, derive the exact type as V4/V5 were (boot with `ddl-auto=update` against scratch Postgres, read the generated DDL). IDENTITY id column definition must match `Show`/`Artist` (`BIGINT GENERATED BY DEFAULT AS IDENTITY` per V3's reconciliation).
- [ ] TDD: write `ScanJobRepositoryTest` (save + findByOwnerAndArtistIdAndSource round-trip, unique-constraint enforced) first (fails — no table/entity), implement entity+enum+repo+migration, run `ApplicationContextSmokeTest` + the repo test green. Commit `PR3b: scan_job entity + repository + Flyway V6`.

---

### Task 2: `expansion.ExpandJob` entity + `ExpandJobRepository` + Flyway `V7` (expand_job)
**Files:** Create `expansion/ExpandJob.java`; `expansion/ExpandJobRepository.java`; `V7__expand_job.sql`. Test: `expansion/ExpandJobRepositoryTest.java`.
- Same as Task 1 minus `location_fingerprint`. Repo mirrors `ScanJobRepository`'s methods.
- [ ] TDD as Task 1; `ApplicationContextSmokeTest` green after V7. Commit `PR3b: expand_job entity + repository + Flyway V7`.

---

### Task 3: `settings.SettingsService.locationFingerprint(owner)`
**Files:** Modify `settings/SettingsService.java` + test.
- Add `public String locationFingerprint(String owner)` = a stable hash of `getOrCreateSettings(owner)`'s `postalCode` + `radiusMiles` + `monthsAhead` (e.g. `Integer.toHexString(Objects.hash(postalCode, radiusMiles, monthsAhead))`). Pure read; no publish.
- [ ] TDD: same settings → same fingerprint; a changed radius → different fingerprint. Commit `PR3b: settings exposes a location fingerprint`.

---

### Task 4: `scan.ScanJobListener` — enqueue/cancel/re-due scan jobs
**Files:** Create `scan/ScanJobListener.java` + `scan/ScanJobListenerTest.java`.
- `@org.springframework.modulith.events.ApplicationModuleListener` methods (or one class with three listener methods):
  - `onArtistActivated(ArtistActivated e)`: for each `ShowSource s` in the injected `List<ShowSource>`, idempotently enqueue a `scan_job(owner=e.owner(), artistId=e.artistId(), source=s.id(), status=SCHEDULED, attempts=0, nextDueAt=Instant.now(), locationFingerprint=settingsService.locationFingerprint(e.owner()))`. Idempotent: skip if `existsByOwnerAndArtistIdAndSource`, and catch `DataIntegrityViolationException` around save.
  - `onArtistDeactivated(ArtistDeactivated e)`: `scanJobRepository.deleteByOwnerAndArtistId(e.owner(), e.artistId())`.
  - `onSettingsChanged(SettingsChanged e)`: for each of `scanJobRepository.findByOwner(e.owner())`, set `nextDueAt=Instant.now()` and `locationFingerprint=settingsService.locationFingerprint(e.owner())`, save (re-due stale-location scans).
  - Deps: `ScanJobRepository`, `List<ShowSource>`, `SettingsService`.
- [ ] TDD (unit, mocked repo/sources/settings): activation enqueues one job per ShowSource with due-now + fingerprint; re-delivery/duplicate is a no-op; deactivation deletes; settings-changed re-dues + refreshes fingerprint. Commit `PR3b: scan job listener (enqueue on activation, cancel on deactivation, re-due on settings change)`.

---

### Task 5: `expansion.ExpandJobListener` + `List<RelationSource>` injection
**Files:** Create `expansion/ExpandJobListener.java` + test. (Introduce the generic `List<RelationSource>` bean injection here — the 5 adapters already implement the port.)
- `onArtistActivated(ArtistActivated e)`: for each `RelationSource s` in the injected `List<RelationSource>`, idempotently enqueue an `expand_job(owner, artistId, source=s.id(), SCHEDULED, attempts=0, nextDueAt=now)`. (No location fingerprint — expansion isn't location-sensitive.)
- `onArtistDeactivated(ArtistDeactivated e)`: `deleteByOwnerAndArtistId`.
- Deps: `ExpandJobRepository`, `List<RelationSource>`.
- [ ] TDD (unit): activation enqueues one expand_job per RelationSource; idempotent; deactivation deletes. Commit `PR3b: expand job listener (enqueue on activation, cancel on deactivation)`.

---

### Task 6: real-path integration test(s) + gate
**Files:** Create `JobEnqueueFlowTest.java` (`@SpringBootTest` + Testcontainers).
- Prove the REAL publisher→listener→job path (NOT Scenario): 
  1. Seed an Artist and call `artistActivationService.changeStatus(id, owner, APPROVED)` (or create a SEED via `ArtistSeedService`) → await (bounded poll, no fixed sleep) that a `scan_job` per `ShowSource` and an `expand_job` per `RelationSource` exist for `(owner, artistId)`.
  2. `settingsService.updateSettings(owner, newZip, ...)` → await the owner's `scan_job`s' `nextDueAt` advanced / fingerprint changed.
  3. `changeStatus(id, owner, REJECTED)` on an active artist → await the jobs deleted.
  (Split into two/three test methods if cleaner. Mirror `ExpansionEventFlowTest`'s bounded-await helper from PR3a.)
- [ ] Drive to green (Docker up). **Gate:** `./gradlew test --tests "…ModularityTests"` green, then `./gradlew --no-daemon clean build` BUILD SUCCESSFUL (full suite incl. boot/validate + these tests). Commit `PR3b: real-path job-enqueue integration tests`.

---

## Self-Review
- **Spec coverage:** PR3b = job tables + repos + enqueue/cancel/re-due listeners (jobs inert; poller = PR4). ✅ Tasks 1–6.
- **Transaction/async lesson applied:** the consumed events are already published transactionally (PR3a); every listener has a real-path integration test (Task 6), not just Scenario — this is the explicit guard against repeating the PR3a false-green.
- **Idempotency:** existsBy + catch `DataIntegrityViolationException` on every enqueue (durable redelivery + concurrency).
- **Cycle safety:** scan/expansion listeners consume `shared.events`; scan→settings for the fingerprint (exposed); jobs live in their owning modules. verify() gate in Task 6.
- **Empirical spots (acceptance-gated):** V6/V7 column types vs `validate` (boot test), and the async real-path awaits (bounded polls) — as PR3a.
