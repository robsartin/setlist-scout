# Phase B PR4b: Enable the poller + cutover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Flip the per-unit scan/expand poller engine on in production, backfill jobs for the active artists that predate the job tables, retire the old whole-fleet scheduler + synchronous manual-scan machinery, and close the one lost-update race the PR4a review flagged — as a single deployable, tested change (closes #86).

**Architecture:** PR4a built the engine (per-unit `ScanUnitRunner`/`ExpandUnitRunner` + claim-lease `ScanPoller`/`ExpandPoller`) but shipped it gated off, so the old `ShowScanScheduler` whole-fleet batch is still the only thing that scans. PR4b is the cutover: (1) make the job reschedule safe against a concurrent `SettingsChanged` re-due with JPA optimistic locking (`@Version`), (2) one-time idempotent backfill so already-active artists get jobs, (3) rewire the manual "Scan now"/"Expand now" buttons to just set the owner's jobs due-now (the poller drains them — no synchronous scan, per the agreed "queued confirmation, drop the spinner" UX), (4) flip the flags on and delete the retired machinery, (5) an ADR recording the new work model.

**Tech Stack:** Java 21, Spring Boot 3 + Spring Modulith, Spring Data JPA, Postgres + Flyway, Thymeleaf + vendored htmx, JUnit 5 + Testcontainers + Mockito.

## Global Constraints

- **JDK 21:** `export JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem` before any gradle command.
- **`ddl-auto=validate`:** every entity mapping must match the Flyway schema exactly. A new column needs both a Flyway migration and the matching `@Column`/`@Version` field, or boot fails.
- **Modulith boundaries stay green:** `ModularityTests` (`ApplicationModules.of(...).verify()`) runs in the build. New types go in the module that owns them; cross-module reads use already-exposed API (`catalog.ArtistRepository`, `settings.SettingsService`, `shared.*`, the `scan.source`/`expansion.source` ports).
- **Full CI gate before the PR:** `./gradlew --no-daemon clean build` BUILD SUCCESSFUL **and** `python3 scripts/check_adrs.py` exit 0. Docker Desktop must be running (Testcontainers). Harmless Hikari / `eventPublicationRegistry` shutdown WARNs appear in every green build — ignore them.
- **TDD per step:** red → green → refactor → commit. The failing test runs before the implementation exists.
- **No new runtime dependencies**, no external CDN/CSS/JS. htmx is vendored.
- **Branch:** `86-pr4b-enable-poller-and-cutover`, branched from up-to-date `main` (PR4a / #97 is merged). Never commit to `main`.
- **Idempotency stays DB-level:** enqueue uses `insertIfAbsent` (`INSERT ... ON CONFLICT (owner, artist_id, source) DO NOTHING`) — the `existsBy`+catch pattern does not survive a Postgres constraint race inside a listener transaction.

---

## Preflight (do once before Task 1)

```bash
cd /Users/sartin/code/setlist-scout
export JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem
git checkout main && git pull
git checkout -b 86-pr4b-enable-poller-and-cutover
```

Confirm the branch builds green before changing anything:

```bash
./gradlew --no-daemon build --console=plain
```

---

## Task 1: Optimistic locking on the job entities (`@Version` + Flyway V8)

**Why:** The PR4a review's one Important finding — a poller reschedule saves the whole job entity, so a `SettingsChanged` re-due that lands on a job while it is RUNNING gets clobbered back to the stale location + `now + interval`. `@Version` makes any concurrent modification detectable: the poller's stale `save()` throws instead of silently winning. This task adds the column + field + a test proving the conflict is detected; Tasks 2–3 use it.

**Files:**
- Create: `src/main/resources/db/migration/V8__job_version.sql`
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ScanJob.java`
- Modify: `src/main/java/com/robsartin/setlistscout/expansion/ExpandJob.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/ScanJobRepositoryTest.java` (add a case)

**Interfaces:**
- Produces: `ScanJob.getVersion(): long`, `ExpandJob.getVersion(): long`; both entities now carry a JPA-managed `version` column that Hibernate bumps on every `save()` and checks on update.

- [ ] **Step 1: Write the failing optimistic-lock test** in `ScanJobRepositoryTest.java`

```java
@Test
@DisplayName("a stale save is rejected once another writer has bumped the version")
void staleSaveThrowsOptimisticLockingFailure() {
    ScanJob job = new ScanJob(1L, "ticketmaster", JobStatus.SCHEDULED, 0,
            Instant.now(), "fp-1");
    job.setOwner(OWNER);
    Long id = scanJobRepository.saveAndFlush(job).getId();
    scanJobRepository.flush();

    // Two independent loads of the same row (simulating poller-in-flight vs. a SettingsChanged re-due).
    ScanJob loadA = scanJobRepository.findById(id).orElseThrow();
    ScanJob loadB = scanJobRepository.findById(id).orElseThrow();
    entityManager.detach(loadA);
    entityManager.detach(loadB);

    // Writer B commits first -> version bumps in the DB.
    loadB.setNextDueAt(Instant.now().plusSeconds(3600));
    scanJobRepository.saveAndFlush(loadB);

    // Writer A now holds a stale version -> its save must be rejected, not silently win.
    loadA.setNextDueAt(Instant.now().plusSeconds(999));
    assertThatThrownBy(() -> scanJobRepository.saveAndFlush(loadA))
            .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
}
```

Add whatever imports/fixtures the existing `ScanJobRepositoryTest` uses (it is already a `@DataJpaTest`/Testcontainers class — match it; if it doesn't already inject a `TestEntityManager`/`EntityManager` for `detach`, add `@Autowired TestEntityManager entityManager` or use two fresh `findById` calls in separate transactions via `TransactionTemplate` — whichever matches the class's existing style). `OWNER` constant already exists in that test.

- [ ] **Step 2: Run it — expect FAIL** (no `version` column: either a validate/schema error or the second save silently succeeds)

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.scan.ScanJobRepositoryTest" --console=plain
```

- [ ] **Step 3: Add Flyway V8** — `src/main/resources/db/migration/V8__job_version.sql`

```sql
-- Optimistic-lock version columns so a poller's reschedule can't silently clobber a concurrent
-- SettingsChanged re-due (or an ArtistDeactivated delete). Existing rows start at 0.
ALTER TABLE scan_job   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE expand_job ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

- [ ] **Step 4: Add the `@Version` field to `ScanJob`** (place it after `id`)

```java
    @Version
    @Column(nullable = false)
    private long version;
```

and a getter with the others:

```java
    public long getVersion() { return version; }
```

Import `jakarta.persistence.Version` (or rely on the existing `jakarta.persistence.*` wildcard already in the file). Do **not** add a setter and do **not** pass `version` in the constructor — Hibernate manages it.

- [ ] **Step 5: Add the identical `@Version` field + getter to `ExpandJob`** (same snippet).

- [ ] **Step 6: Run the test + a boot to confirm `ddl-auto=validate` still passes**

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.scan.ScanJobRepositoryTest" --console=plain
```

Expected: PASS (the stale save throws; the schema validates against the new column).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V8__job_version.sql \
        src/main/java/com/robsartin/setlistscout/scan/ScanJob.java \
        src/main/java/com/robsartin/setlistscout/expansion/ExpandJob.java \
        src/test/java/com/robsartin/setlistscout/scan/ScanJobRepositoryTest.java
git commit -m "PR4b: @Version optimistic locking on scan_job/expand_job (Flyway V8)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Version-bumping bulk re-due (`redueAll`) + rewire `onSettingsChanged`

**Why:** Two callers need "reset this owner's jobs to run now, cleanly claimable": `SettingsChanged` (already exists, currently a load-all-and-save-each loop that is itself exposed to the version race) and the manual "Scan now"/"Expand now" buttons (Task 5). Provide one efficient, **version-bumping** bulk `UPDATE` per repo. Bumping `version` in the same statement is what makes the fix work: an in-flight poller holding the old version will conflict on its later `save()` (Task 3) instead of overwriting the re-due.

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ScanJobRepository.java`
- Modify: `src/main/java/com/robsartin/setlistscout/expansion/ExpandJobRepository.java`
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ScanJobListener.java` (`onSettingsChanged`)
- Test: `src/test/java/com/robsartin/setlistscout/scan/ScanJobRepositoryTest.java` (add a `redueAll` case)
- Test: `src/test/java/com/robsartin/setlistscout/JobEnqueueFlowTest.java` (tighten the `SettingsChanged` assertions)

**Interfaces:**
- Produces:
  - `ScanJobRepository.redueAll(String owner, Instant now, String locationFingerprint): int` — sets every one of the owner's scan jobs to `next_due_at = now`, `status = SCHEDULED`, `attempts = 0`, `claimed_at = null`, `location_fingerprint = :fp`, and `version = version + 1`; returns the row count.
  - `ExpandJobRepository.redueAll(String owner, Instant now): int` — same minus the fingerprint (expansion isn't location-sensitive).

- [ ] **Step 1: Write the failing `redueAll` test** in `ScanJobRepositoryTest.java`

```java
@Test
@DisplayName("redueAll makes all of an owner's jobs due-now, claimable, and bumps version")
void redueAllResetsJobsAndBumpsVersion() {
    ScanJob job = new ScanJob(1L, "ticketmaster", JobStatus.FAILED, 4,
            Instant.now().plus(java.time.Duration.ofDays(14)), "fp-old");
    job.setOwner(OWNER);
    job.setClaimedAt(Instant.now());
    Long id = scanJobRepository.saveAndFlush(job).getId();
    long v0 = scanJobRepository.findById(id).orElseThrow().getVersion();

    Instant now = Instant.now();
    int updated = scanJobRepository.redueAll(OWNER, now, "fp-new");
    assertThat(updated).isEqualTo(1);

    ScanJob after = scanJobRepository.findById(id).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(JobStatus.SCHEDULED);
    assertThat(after.getAttempts()).isZero();
    assertThat(after.getClaimedAt()).isNull();
    assertThat(after.getLocationFingerprint()).isEqualTo("fp-new");
    assertThat(after.getNextDueAt()).isCloseTo(now, within(1, java.time.temporal.ChronoUnit.SECONDS));
    assertThat(after.getVersion()).isEqualTo(v0 + 1);
}
```

(`within` = `org.assertj.core.api.Assertions.within`. If `saveAndFlush` leaves a stale first-level cache copy, add `entityManager.clear()` before the reload, matching the class's convention.)

- [ ] **Step 2: Run it — expect FAIL** (`redueAll` doesn't exist → compile error)

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.scan.ScanJobRepositoryTest" --console=plain
```

- [ ] **Step 3: Add `redueAll` to `ScanJobRepository`**

```java
    /**
     * Version-safe bulk re-due of every one of an owner's scan jobs: make them due-now and cleanly
     * claimable (SCHEDULED, attempts reset, lease cleared) at the current location, and bump
     * {@code version} so any poller holding one of these rows in-flight conflicts on its next
     * {@code save()} (ScanPoller catches that and skips its stale reschedule) instead of silently
     * overwriting this re-due. Used by ScanJobListener#onSettingsChanged and the manual "Scan now"
     * button (ShowController#scanNow).
     */
    @Modifying
    @Query(value = """
            UPDATE scan_job
               SET next_due_at = :now, status = 'SCHEDULED', attempts = 0, claimed_at = NULL,
                   location_fingerprint = :locationFingerprint, version = version + 1
             WHERE owner = :owner
            """, nativeQuery = true)
    int redueAll(@Param("owner") String owner,
                  @Param("now") Instant now,
                  @Param("locationFingerprint") String locationFingerprint);
```

- [ ] **Step 4: Add the fingerprint-less `redueAll` to `ExpandJobRepository`**

```java
    /**
     * Version-safe bulk re-due of every one of an owner's expand jobs (see ScanJobRepository#redueAll;
     * expansion isn't location-sensitive so there's no fingerprint). Used by the manual "Expand now"
     * button (ReviewController#expandNow).
     */
    @Modifying
    @Query(value = """
            UPDATE expand_job
               SET next_due_at = :now, status = 'SCHEDULED', attempts = 0, claimed_at = NULL,
                   version = version + 1
             WHERE owner = :owner
            """, nativeQuery = true)
    int redueAll(@Param("owner") String owner, @Param("now") Instant now);
```

- [ ] **Step 5: Rewire `ScanJobListener.onSettingsChanged`** to the single statement (replaces the load-all-and-save-each loop)

```java
    @ApplicationModuleListener
    void onSettingsChanged(SettingsChanged e) {
        // One version-bumping bulk UPDATE rather than load-all-and-save-each: re-dues every job to
        // run now at the new location AND bumps version, so an in-flight poller reschedule conflicts
        // instead of clobbering this back to the stale location (the PR4a review's lost-update fix).
        String locationFingerprint = settingsService.locationFingerprint(e.owner());
        scanJobRepository.redueAll(e.owner(), Instant.now(), locationFingerprint);
    }
```

Remove the now-unused `import java.util.List;` only if nothing else in the file needs it (the `List<ShowSource>` field still does — leave it).

- [ ] **Step 6: Tighten `JobEnqueueFlowTest`'s `SettingsChanged` assertions.** Find the test(s) that call `updateSettings`/`changeStatus` then assert scan jobs were re-dued. Add assertions that after a settings change the owner's scan jobs are `SCHEDULED`, `attempts == 0`, `claimedAt == null`, and `nextDueAt` ≈ now. (If an existing assertion only checked `nextDueAt`, extend it.) Keep the existing bounded `awaitUntil` helper.

- [ ] **Step 7: Run both tests + full expansion/scan suites**

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.scan.ScanJobRepositoryTest" \
  --tests "com.robsartin.setlistscout.JobEnqueueFlowTest" --console=plain
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/ScanJobRepository.java \
        src/main/java/com/robsartin/setlistscout/expansion/ExpandJobRepository.java \
        src/main/java/com/robsartin/setlistscout/scan/ScanJobListener.java \
        src/test/java/com/robsartin/setlistscout/scan/ScanJobRepositoryTest.java \
        src/test/java/com/robsartin/setlistscout/JobEnqueueFlowTest.java
git commit -m "PR4b: version-bumping redueAll on both job repos; SettingsChanged uses it

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Pollers skip their reschedule on an optimistic-lock conflict

**Why:** With Task 2's `redueAll` bumping `version`, a poller that claimed a job before a concurrent re-due now holds a stale `version`; its `save()` throws `ObjectOptimisticLockingFailureException`. The poller must treat that as "someone re-dued or deactivated this job while I ran it — leave their write alone and move on," not crash the tick. (This also fixes a latent zombie-resurrection bug: if `ArtistDeactivated` deleted the job mid-run, the stale save would otherwise re-insert it.)

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ScanPoller.java`
- Modify: `src/main/java/com/robsartin/setlistscout/expansion/ExpandPoller.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/ScanPollerTest.java`
- Test: `src/test/java/com/robsartin/setlistscout/expansion/ExpandPollerTest.java`

**Interfaces:**
- Consumes: `ScanJobRepository.save` / `ExpandJobRepository.save` may now throw `org.springframework.dao.OptimisticLockingFailureException` (superclass of `ObjectOptimisticLockingFailureException`).

- [ ] **Step 1: Write the failing test** in `ScanPollerTest.java` (mock-based, matching the class's existing Mockito style)

```java
@Test
@DisplayName("a reschedule that loses an optimistic-lock race is swallowed; the tick continues")
void concurrentRedueDuringRunIsSkipped() {
    ScanJob job = scanJob(1L, "ticketmaster");   // helper already in this test; else build inline
    when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
    // The unit runs fine, but the reschedule save loses to a concurrent redueAll:
    when(scanJobRepository.save(job))
            .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ScanJob.class, 1L));

    // Must not propagate out of tick():
    assertThatCode(() -> poller.tick()).doesNotThrowAnyException();
    verify(scanUnitRunner).run(job.getOwner(), job.getArtistId(), job.getSource());
}
```

If the class doesn't have a `scanJob(...)` helper or a wired `poller`, mirror the setup an existing `ScanPollerTest` case uses (there are already happy-path + failure cases). Add a second variant with **two** claimed jobs where the first `save` throws the lock exception and the second succeeds, asserting `scanUnitRunner.run` is invoked for **both** (a conflict on one job must not skip the rest of the batch).

- [ ] **Step 2: Run it — expect FAIL** (the exception propagates out of `tick()`)

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.scan.ScanPollerTest" --console=plain
```

- [ ] **Step 3: Catch the conflict in `ScanPoller.runOne`**

```java
    private void runOne(ScanJob job, Instant now) {
        try {
            scanUnitRunner.run(job.getOwner(), job.getArtistId(), job.getSource());
            recordSuccess(job, now);
        } catch (OptimisticLockingFailureException concurrentChange) {
            // The job was re-dued (SettingsChanged / manual "Scan now") or deleted (ArtistDeactivated)
            // while we ran it. That writer's intent wins -- drop our stale reschedule and move on.
            log.atInfo().addKeyValue("owner", job.getOwner()).addKeyValue("artistId", job.getArtistId())
                    .addKeyValue("source", job.getSource())
                    .log("scan job changed concurrently during run; skipping reschedule");
        } catch (RuntimeException ex) {
            recordFailure(job, now, ex);
        }
    }
```

Import `org.springframework.dao.OptimisticLockingFailureException`. **Ordering matters:** the `OptimisticLockingFailureException` catch must come before the `RuntimeException` catch, or the reschedule-into-`recordFailure` path would swallow it and try another (also-conflicting) save. Note `recordSuccess`/`recordFailure` both call `save`, so either can raise it — this catch around the whole `runOne` body covers both.

- [ ] **Step 4: Apply the identical change to `ExpandPoller.runOne`** and add the mirrored `ExpandPollerTest` case(s).

- [ ] **Step 5 (nit from the review, while you're here): guard the backoff shift.** In both pollers' `nextDelay`, the `1L << attempts` is only overflow-safe because `pollerParkCap` (6) is small. Add a one-line ceiling so an absurd `POLLER_PARK_CAP` can't produce a negative duration:

```java
    private Duration nextDelay(int attempts, Duration interval) {
        if (attempts >= properties.pollerParkCap()) {
            return interval;
        }
        int shift = Math.min(attempts, 30);   // cap the shift; >2^30 * 10m already dwarfs any interval
        Duration exponential = BACKOFF_BASE.multipliedBy(1L << shift);
        return exponential.compareTo(interval) < 0 ? exponential : interval;
    }
```

- [ ] **Step 6: Run both poller test classes**

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.scan.ScanPollerTest" \
  --tests "com.robsartin.setlistscout.expansion.ExpandPollerTest" --console=plain
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/ScanPoller.java \
        src/main/java/com/robsartin/setlistscout/expansion/ExpandPoller.java \
        src/test/java/com/robsartin/setlistscout/scan/ScanPollerTest.java \
        src/test/java/com/robsartin/setlistscout/expansion/ExpandPollerTest.java
git commit -m "PR4b: pollers skip reschedule on optimistic-lock conflict (re-due/deactivate wins)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: One-time idempotent backfill of jobs for already-active artists

**Why:** The job tables only have rows for artists **activated after PR3b shipped** (the listeners enqueue on `ArtistActivated`). Every artist that was already SEED/APPROVED before then — including the canonical seed bands — has **no** jobs, so flipping the poller on would silently never scan them. A startup reconciler enqueues one job per source for each active artist, idempotently (so it's safe to run every boot) and with a jittered `next_due_at` so the flip doesn't fire every job on the first tick. Tribute expand jobs stay SEED-only, matching `ExpandJobListener`.

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java` (add `findByStatusIn`)
- Create: `src/main/java/com/robsartin/setlistscout/scan/ScanJobBackfill.java`
- Create: `src/main/java/com/robsartin/setlistscout/expansion/ExpandJobBackfill.java`
- Modify: `src/main/java/com/robsartin/setlistscout/PollerProperties.java` (backfill spread + enabled flag accessors)
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/robsartin/setlistscout/scan/ScanJobBackfillTest.java` (create)
- Test: `src/test/java/com/robsartin/setlistscout/expansion/ExpandJobBackfillTest.java` (create)

**Interfaces:**
- Consumes: `ArtistRepository.findByStatusIn(List<ArtistStatus>)` (already exists as `findByOwnerAndStatusIn`; add the owner-less variant), `ScanJobRepository.insertIfAbsent`, `ExpandJobRepository.insertIfAbsent`, `SettingsService.locationFingerprint`, the injected `List<ShowSource>` / `List<RelationSource>` ports, `PollerProperties.backfillSpread()` + `PollerProperties.jobBackfillEnabled()`.
- Produces: two `ApplicationRunner` beans that run once at startup.

- [ ] **Step 1: Add `findByStatusIn` to `ArtistRepository`**

```java
    List<Artist> findByStatusIn(List<ArtistStatus> statuses);
```

- [ ] **Step 2: Add the config accessors to `PollerProperties`.** Match the class's existing style (it already exposes `scanInterval()`, `pollerParkCap()`, etc.). Add:
  - `boolean jobBackfillEnabled()` bound to `setlistscout.job-backfill-enabled` (default `true`).
  - `Duration backfillSpread()` bound to `setlistscout.backfill-spread` (default `2h`) — the window over which backfilled `next_due_at` values are jittered.

- [ ] **Step 3: Add the keys to `application.yml`** under `setlistscout:`

```yaml
  job-backfill-enabled: ${JOB_BACKFILL_ENABLED:true} # startup reconciler: enqueue jobs for active artists that predate the job tables; idempotent (insertIfAbsent)
  backfill-spread: ${BACKFILL_SPREAD:2h} # jitter window for backfilled next_due_at, so enabling the poller doesn't fire every job on the first tick
```

- [ ] **Step 4: Write the failing `ScanJobBackfillTest`** (`@SpringBootTest` + Testcontainers; copy the infra header from `JobEnqueueFlowTest`)

```java
@Test
@DisplayName("backfill enqueues one scan job per source for each active artist, jittered, idempotently")
void backfillEnqueuesJobsForActiveArtists() {
    // Two active (SEED + APPROVED) + one REJECTED artist for the same owner.
    Artist seed = save(artist(OWNER, "Wilco", ArtistStatus.SEED));
    Artist approved = save(artist(OWNER, "Dawes", ArtistStatus.APPROVED));
    save(artist(OWNER, "Nope", ArtistStatus.REJECTED));

    Instant before = Instant.now();
    scanJobBackfill.run(null);   // ApplicationRunner#run(ApplicationArguments)

    List<ScanJob> seedJobs = scanJobRepository.findByOwnerAndArtistId(OWNER, seed.getId());
    assertThat(seedJobs).hasSize(showSources.size());
    assertThat(scanJobRepository.findByOwnerAndArtistId(OWNER, approved.getId()))
            .hasSize(showSources.size());
    // No jobs for the rejected artist.
    assertThat(scanJobRepository.findByOwner(OWNER))
            .allSatisfy(j -> assertThat(j.getArtistId()).isIn(seed.getId(), approved.getId()));
    // next_due_at jittered into [now, now + spread].
    assertThat(seedJobs).allSatisfy(j -> assertThat(j.getNextDueAt())
            .isBetween(before, before.plus(java.time.Duration.ofHours(2)).plusSeconds(5)));

    // Idempotent: a second run adds nothing.
    scanJobBackfill.run(null);
    assertThat(scanJobRepository.findByOwnerAndArtistId(OWNER, seed.getId()))
            .hasSize(showSources.size());
}
```

Inject `ScanJobBackfill scanJobBackfill`, `ScanJobRepository scanJobRepository`, `ArtistRepository`, `List<ShowSource> showSources`. Provide `artist(...)`/`save(...)` helpers matching how other Testcontainers tests build catalog rows.

- [ ] **Step 5: Run it — expect FAIL** (`ScanJobBackfill` doesn't exist)

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.scan.ScanJobBackfillTest" --console=plain
```

- [ ] **Step 6: Implement `ScanJobBackfill`**

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.PollerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Startup reconciler: enqueue one scan job per {@link ShowSource} for every active (SEED/APPROVED)
 * artist that doesn't already have jobs. Needed because artists activated before the job tables
 * existed (PR3b) never fired ArtistActivated, so they'd otherwise be invisible to the poller.
 * Idempotent via {@code insertIfAbsent} (safe to run every boot); {@code next_due_at} is jittered
 * across {@link PollerProperties#backfillSpread()} so enabling the poller doesn't stampede every
 * job on the first tick.
 */
@Component
@ConditionalOnProperty(name = "setlistscout.job-backfill-enabled", havingValue = "true", matchIfMissing = true)
public class ScanJobBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanJobBackfill.class);

    private final ArtistRepository artistRepository;
    private final ScanJobRepository scanJobRepository;
    private final List<ShowSource> showSources;
    private final SettingsService settingsService;
    private final PollerProperties properties;

    public ScanJobBackfill(ArtistRepository artistRepository, ScanJobRepository scanJobRepository,
                            List<ShowSource> showSources, SettingsService settingsService,
                            PollerProperties properties) {
        this.artistRepository = artistRepository;
        this.scanJobRepository = scanJobRepository;
        this.showSources = showSources;
        this.settingsService = settingsService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        long spreadMs = properties.backfillSpread().toMillis();
        int enqueued = 0;
        List<Artist> active = artistRepository.findByStatusIn(
                List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));
        for (Artist artist : active) {
            String fingerprint = settingsService.locationFingerprint(artist.getOwner());
            for (ShowSource source : showSources) {
                Instant dueAt = Instant.now().plusMillis(jitter(spreadMs));
                scanJobRepository.insertIfAbsent(artist.getOwner(), artist.getId(), source.id(),
                        dueAt, fingerprint);
                enqueued++;
            }
        }
        log.atInfo().addKeyValue("activeArtists", active.size())
                .addKeyValue("jobsConsidered", enqueued)
                .log("scan job backfill complete (insertIfAbsent -- existing jobs untouched)");
    }

    private long jitter(long spreadMs) {
        return spreadMs <= 0 ? 0 : ThreadLocalRandom.current().nextLong(spreadMs);
    }
}
```

- [ ] **Step 7: Run the scan backfill test — expect PASS.**

- [ ] **Step 8: Write the failing `ExpandJobBackfillTest`.** Same shape, but assert: every active artist gets one expand job per `RelationSource` **except** that the `TRIBUTE_EXPANSION` source is enqueued only for the SEED artist (the APPROVED artist gets `relationSources.size() - 1`). Inject `List<RelationSource> relationSources` and use `RelationSource.classification()` to identify the tribute source.

- [ ] **Step 9: Implement `ExpandJobBackfill`** — mirror `ScanJobBackfill` in the `expansion` package (inject `ExpandJobRepository` + `List<RelationSource>`; no fingerprint). The per-artist loop skips the tribute source unless the artist is SEED:

```java
        for (RelationSource source : relationSources) {
            if (source.classification() == com.robsartin.setlistscout.catalog.ArtistSource.TRIBUTE_EXPANSION
                    && artist.getStatus() != ArtistStatus.SEED) {
                continue;
            }
            Instant dueAt = Instant.now().plusMillis(jitter(spreadMs));
            expandJobRepository.insertIfAbsent(artist.getOwner(), artist.getId(), source.id(), dueAt);
        }
```

- [ ] **Step 10: Run both backfill tests + `ModularityTests`** (the new cross-module reads must stay within allowed dependencies)

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.scan.ScanJobBackfillTest" \
  --tests "com.robsartin.setlistscout.expansion.ExpandJobBackfillTest" \
  --tests "com.robsartin.setlistscout.ModularityTests" --console=plain
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java \
        src/main/java/com/robsartin/setlistscout/scan/ScanJobBackfill.java \
        src/main/java/com/robsartin/setlistscout/expansion/ExpandJobBackfill.java \
        src/main/java/com/robsartin/setlistscout/PollerProperties.java \
        src/main/resources/application.yml \
        src/test/java/com/robsartin/setlistscout/scan/ScanJobBackfillTest.java \
        src/test/java/com/robsartin/setlistscout/expansion/ExpandJobBackfillTest.java
git commit -m "PR4b: startup backfill of scan/expand jobs for active artists (idempotent, jittered)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Rewire the manual "Scan now" / "Expand now" buttons to the async model

**Why:** In the per-unit model there is no synchronous full scan to wait on — the poller drains jobs on its own cadence. Per the agreed UX, the buttons just set the owner's jobs due-now (`redueAll`) and show a brief "queued" confirmation; the blocking "Scanning…" spinner, the `/scan-status` poll, `AsyncScanRunner`, and `ScanStateService` all go away.

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ShowController.java`
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java`
- Modify: `src/main/resources/templates/shows.html`
- Delete: `src/main/java/com/robsartin/setlistscout/scan/AsyncScanRunner.java`
- Delete: `src/main/java/com/robsartin/setlistscout/scan/ScanStateService.java`
- Delete: `src/test/java/com/robsartin/setlistscout/scan/AsyncScanRunnerTest.java`
- Delete: `src/test/java/com/robsartin/setlistscout/scan/ScanStateServiceTest.java`
- Modify: `src/test/java/com/robsartin/setlistscout/scan/ShowControllerTest.java`

**Interfaces:**
- Consumes: `ScanJobRepository.redueAll(owner, now, fingerprint)`, `ExpandJobRepository.redueAll(owner, now)`, `SettingsService.locationFingerprint(owner)`.

- [ ] **Step 1: Update `ShowControllerTest`** (write the new expectation first). Replace the async-scan expectations with: a POST to `/scan-now` calls `scanJobRepository.redueAll(owner, <any now>, <fingerprint>)` and returns the shows view / `showsRegion` fragment with a `scanQueued` flag; there is no longer a `scanning`/`scanLabel`/`/scan-status` interaction. Remove references to `AsyncScanRunner`/`ScanStateService`. (Mock `ScanJobRepository` + `SettingsService`.) Keep the existing sort/populate tests.

- [ ] **Step 2: Run it — expect FAIL / compile error** (old collaborators gone from the controller not yet).

- [ ] **Step 3: Rewire `ShowController`.** Remove the `AsyncScanRunner` + `ScanStateService` fields/constructor params. Inject `ScanJobRepository scanJobRepository` (already imports the scan package). Change `scanNow` and drop `scanStatus`:

```java
    /**
     * Manually request a scan: mark all of this owner's scan jobs due-now (the paced poller picks
     * them up within a tick) and confirm. There's no synchronous scan to wait on in the per-unit
     * model, so this just queues -- newly found shows appear on later page loads as the poller drains.
     */
    @PostMapping("/scan-now")
    public String scanNow(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          @RequestParam(defaultValue = "eventDate") String sort, Model model) {
        String owner = currentUser.email();
        scanJobRepository.redueAll(owner, java.time.Instant.now(), settingsService.locationFingerprint(owner));
        if (hxRequest != null) {
            populateShows(model, owner, sort);
            model.addAttribute("scanQueued", true);
            return "shows :: showsRegion";
        }
        return "redirect:/";
    }
```

Also drop the `scanning`/`scanLabel` model attributes from `shows(...)` (and the `SCANNING_LABEL` constant + the `/scan-status` handler). Keep `populateShows`.

- [ ] **Step 4: Rewire `ReviewController.expandNow`** to the job model:

```java
    /** Manually request expansion: mark all of this owner's expand jobs due-now (the poller drains them). */
    @PostMapping("/expand-now")
    public String expandNow(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                            Model model) {
        expandJobRepository.redueAll(currentUser.email(), java.time.Instant.now());
        return pendingResult(hxRequest, model);
    }
```

Replace the `ExpansionService expansionService` field with `ExpandJobRepository expandJobRepository` (constructor + import). Confirm `ExpansionService` is no longer referenced anywhere in `ReviewController` (it should not be after this).

- [ ] **Step 5: Simplify `shows.html`.** Replace the `showsRegion` fragment's scanning/poll block with a one-shot queued confirmation and drop the `/scan-status` polling. Concretely: remove the `<p th:if="${scanning}" ... hx-get="/scan-status" hx-trigger="every 10s" ...>` element and the `.scanning` machinery; add, inside `showsRegion`, a dismissable/transient line shown only when queued:

```html
    <p th:if="${scanQueued}" class="notice" role="status">
        Scan queued — newly found shows will appear here as they're picked up. Reload in a minute or two.
    </p>
```

Keep the `#shows-region` id + `hx-target`/`hx-swap="outerHTML"` on the "Scan now" form so the confirmation swaps in. (Add a minimal `.notice` style near the existing `.scanning` rule, or reuse an existing class; remove the now-unused `.scanning` rule.) If `justScanned` was referenced anywhere else in the template, remove it too.

- [ ] **Step 6: Delete the retired classes + their tests**

```bash
git rm src/main/java/com/robsartin/setlistscout/scan/AsyncScanRunner.java \
       src/main/java/com/robsartin/setlistscout/scan/ScanStateService.java \
       src/test/java/com/robsartin/setlistscout/scan/AsyncScanRunnerTest.java \
       src/test/java/com/robsartin/setlistscout/scan/ScanStateServiceTest.java
```

- [ ] **Step 7: Fix any remaining references.** Grep and clean:

```bash
grep -rn "ScanStateService\|AsyncScanRunner\|scan-status\|scanStatus\|SCANNING_LABEL" src/
```

`PollerConditionalWiringTest` / `ScanJobRepositoryTest` matched these names earlier only incidentally — check each hit and remove/adjust. (`ScanPoller` mentions `ScanStateService` only if a stray comment; ensure no prod code still imports the deleted classes.)

- [ ] **Step 8: Run the affected tests**

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.scan.ShowControllerTest" \
  --tests "com.robsartin.setlistscout.review.*" --console=plain
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "PR4b: manual scan/expand buttons re-due jobs (queued confirmation); drop sync scan machinery

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Flip the flags on + retire the old whole-fleet scheduler

**Why:** The engine and its callers are ready; enable it and delete the now-dead whole-fleet path in the same change so there's never a build where nothing scans. This is the risky heart of the cutover — do it as one atomic commit.

**Files:**
- Modify: `src/main/resources/application.yml` (flip defaults; remove dead keys)
- Delete: `src/main/java/com/robsartin/setlistscout/scan/ShowScanScheduler.java` + `src/test/java/.../ShowScanSchedulerTest.java`
- Delete: `src/main/java/com/robsartin/setlistscout/scan/ShowAggregationService.java` + `src/test/java/.../ShowAggregationServiceTest.java` (its only callers were the scheduler + AsyncScanRunner — both gone)
- Delete: `src/main/java/com/robsartin/setlistscout/expansion/ExpansionService.java` + `src/test/java/.../ExpansionServiceTest.java` + `src/test/java/.../ExpansionEventFlowTest.java` (whole-fleet `expandAll` is replaced by per-unit `ExpandUnitRunner`; its committed-tx regression guard now lives in `PollerFlowTest.expandHappyPath`)
- Modify: `src/test/java/com/robsartin/setlistscout/PollerConditionalWiringTest.java` (defaults flipped)

**Interfaces:** none new — this task removes code.

- [ ] **Step 1: Confirm the deletion targets are truly unreferenced** (guard against a missed caller)

```bash
grep -rn "ShowAggregationService\|scanForShows" src/main
grep -rn "ExpansionService\|expandAll" src/main
```

Expected after Task 5: the only `main` hits are the class definitions themselves + javadoc `{@link}` mentions in `ScanUnitRunner`/`ExpandUnitRunner`. If any real caller remains, stop and rewire it before deleting. (Update the `{@link ShowAggregationService#scanForShows}` / `{@code ExpansionService.expandAll}` javadoc references in `ScanUnitRunner`/`ExpandUnitRunner` to plain prose so they don't dangle after deletion.)

- [ ] **Step 2: Flip the flags + remove dead keys in `application.yml`**

```yaml
  scan-poller-enabled: ${SCAN_POLLER_ENABLED:true}
  expand-poller-enabled: ${EXPAND_POLLER_ENABLED:true}
```

and delete the two keys that only fed the old scheduler:

```yaml
  # REMOVE these two lines (only ShowScanScheduler read them):
  # scan-interval-ms: ${SCAN_INTERVAL_MS:259200000}
  # scan-initial-delay-ms: ${SCAN_INITIAL_DELAY_MS:259200000}
```

Leave `scan-interval` / `expansion-interval` (the poller's per-job cadence) and the comment block; update the comment that says the poller is "gated OFF by default … the old whole-fleet ShowScanScheduler batch above keeps driving everything" to reflect that the poller is now the driver and the old scheduler is gone.

- [ ] **Step 3: Delete the retired scheduler + dead services + their tests**

```bash
git rm src/main/java/com/robsartin/setlistscout/scan/ShowScanScheduler.java \
       src/test/java/com/robsartin/setlistscout/scan/ShowScanSchedulerTest.java \
       src/main/java/com/robsartin/setlistscout/scan/ShowAggregationService.java \
       src/test/java/com/robsartin/setlistscout/scan/ShowAggregationServiceTest.java \
       src/main/java/com/robsartin/setlistscout/expansion/ExpansionService.java \
       src/test/java/com/robsartin/setlistscout/expansion/ExpansionServiceTest.java \
       src/test/java/com/robsartin/setlistscout/expansion/ExpansionEventFlowTest.java
```

- [ ] **Step 4: Update `PollerConditionalWiringTest`.** It asserted both poller beans are **absent by default**. Now the shipped default is on. Make each case set the property explicitly rather than leaning on the default: assert the bean is **present** when the property is `true` and **absent** when `false` (via `ApplicationContextRunner.withPropertyValues(...)`). If it already sets props explicitly, just flip/confirm the "default" case's expectation.

- [ ] **Step 5: Grep for any dangling reference to the deleted types**

```bash
grep -rn "ShowScanScheduler\|ShowAggregationService\|ExpansionService" src/
```

Expected: no hits (or only this plan/docs). Fix any straggler (e.g. an unused import, a `ServiceBeanWiringTest` entry, a `@MockBean` in some other test that named one of these).

- [ ] **Step 6: Full gate**

```bash
./gradlew --no-daemon clean build --console=plain
```

Expected: BUILD SUCCESSFUL (unit + Testcontainers boot over Flyway V1..V8 with `ddl-auto=validate` + `ModularityTests` + `PollerFlowTest` with the pollers now on-by-default). If a Testcontainers boot test now starts the real pollers and interferes, scope those tests to `setlistscout.scan-poller-enabled=false` where they don't want the poller — but prefer leaving PollerFlowTest as the one that exercises them.

- [ ] **Step 7: Commit (atomic cutover)**

```bash
git add -A
git commit -m "PR4b: enable scan/expand pollers by default; delete whole-fleet scheduler + dead services

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: ADR for the per-unit work model + full gate

**Why:** ADR 0006 recorded the "every 3 days, whole fleet" scan model. PR4b supersedes it. Record the new per-unit event-driven model so the decision + its tradeoffs are captured, and run the ADR compliance check that CI runs.

**Files:**
- Create: `docs/adr/0023-per-unit-event-driven-scan-work-model.md`
- Modify: `docs/adr/README.md` (link the new ADR)
- Modify: `docs/adr/0006-scan-frequency.md` (mark superseded-by-0023)

- [ ] **Step 1: Write ADR 0023.** Follow the frontmatter shape of a recent ADR (e.g. `0022-event-driven-inter-module-communication.md`). Content to capture:
  - **Context:** whole-fleet `@Scheduled` batch rescanned every artist every 3 days regardless of need; no per-artist cadence, no backoff, a manual "Scan now" blocked on a synchronous full scan.
  - **Decision:** durable per-`(owner, artist, source)` jobs (`scan_job`/`expand_job`), enqueued/cancelled/re-dued by catalog+settings domain events, drained by a paced claim-lease poller (`FOR UPDATE SKIP LOCKED`) with per-attempt exponential backoff + park, per-source cadence overrides, JPA optimistic locking so a settings re-due can't be clobbered by an in-flight run, a startup backfill reconciler for pre-existing artists, and manual buttons that simply set jobs due-now.
  - **Consequences:** even external-API load, work scales with the active set, resilient to restarts (durable jobs + lease recovery); manual scan is now asynchronous ("queued", no synchronous results); multi-instance double-run is bounded by the lease (documented tradeoff).
  - **Supersedes:** 0006.

- [ ] **Step 2: Link it in `docs/adr/README.md`** (add the `0023` row to the index, matching the existing list format).

- [ ] **Step 3: Mark 0006 superseded.** Add a top note / status line: `Superseded by [0023](0023-per-unit-event-driven-scan-work-model.md)` (keep 0006's body; don't renumber anything — `check_adrs.py` requires contiguous numbering + every file linked from the index, which 0023 satisfies).

- [ ] **Step 4: Run the ADR check + full gate**

```bash
python3 scripts/check_adrs.py && echo "ADRs OK"
./gradlew --no-daemon clean build --console=plain
```

Expected: `ADRs OK` (exit 0) and BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add docs/adr/0023-per-unit-event-driven-scan-work-model.md docs/adr/README.md docs/adr/0006-scan-frequency.md
git commit -m "PR4b: ADR 0023 — per-unit event-driven scan/expand work model (supersedes 0006)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 6: Final whole-branch review + PR.** (Handled by the subagent-driven-development wrapper: an opus whole-branch review over `main..HEAD`, a fix wave for anything it surfaces, then push + open the PR to `main` closing #86. Stop at the PR — do not merge.)

---

## Self-Review

**Spec coverage (PR4b outline from the PR4a plan + the epic):**
- "Flip `scan-poller-enabled`/`expand-poller-enabled` default → true" → Task 6.
- "One-time jittered backfill … tribute only for SEED" → Task 4.
- "Delete `ShowScanScheduler.scan()` + `AsyncScanRunner`" → Tasks 5 (AsyncScanRunner) + 6 (ShowScanScheduler); also retires the now-dead `ShowAggregationService` + `ExpansionService`.
- "Rewire manual 'Scan now'/'Expand now' → set jobs due-now; adapt the 'Scanning…' indicator / ScanStateService / /scan-status UX" → Task 5 (per Rob's "queued confirmation, drop spinner" choice: delete `ScanStateService` + `/scan-status`).
- "ADR (per-unit work model)" → Task 7.
- PR4a review carry-forwards: lost-update race → Tasks 1–3 (`@Version` + `redueAll` + poller conflict-skip); backoff-overflow nit → Task 3 Step 5. (The per-source colon-key minor is left for the cleanup issues #93/#95 — not a cutover blocker.)

**Type/name consistency:** `redueAll` used identically in Tasks 2/5; `findByStatusIn` added in Task 4 and consumed there; `ScanJob.getVersion()`/`ExpandJob.getVersion()` from Task 1 used in Task 2's assertions; poller catch uses `org.springframework.dao.OptimisticLockingFailureException` (superclass) while tests throw `org.springframework.orm.ObjectOptimisticLockingFailureException` (subclass) — the catch covers both.

**Sequencing safety:** the DB/entity change (1) precedes its users (2–3); backfill (4) lands while the poller is still off (backfilled rows just sit); the manual rewire (5) removes the last non-scheduler users of the dead services so the atomic flip+delete (6) has nothing dangling; between Task 5 (poller still off, buttons queue with nothing draining) and Task 6 there is a deliberately brief within-branch window — never a deployed state.

**Placeholder scan:** every code step carries real code or an exact edit; no TBDs.
