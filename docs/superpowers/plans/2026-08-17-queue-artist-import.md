# Queued Artist Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `POST /artists/upload` accept a file, queue one row per name, and return immediately — instead of doing all the work inside the HTTP request.

**Architecture:** A durable `artist_import` row per name, drained by a claim-lease poller mirroring `ScanPoller` (ADR-0023). The upload endpoint parses, dedupes, bulk-inserts, and returns.

**Tech Stack:** Java 21 source / JDK 25 toolchain, Spring Boot 3.5, Spring Data JPA, Flyway, Postgres + Testcontainers, Thymeleaf, JUnit 5 + AssertJ.

**Issue:** #177. **Spec:** `docs/superpowers/specs/2026-08-17-artist-import-redesign-design.md` (Part B).

## Why

The current endpoint loops over every line calling `addSeedIfNew` synchronously. Even now that #176 made each name an indexed lookup, each newly-active artist still publishes `ArtistActivated`, which three listeners turn into ~8 job rows — so a 1,138-name file is still ~9,000 inserts inside one request. The real 1,138-name attempt returned a **502** and was then killed part-way by the free tier's idle spin-down, having imported only **79** names.

## Global Constraints

- **Never commit to `main`.** TDD: failing test → implement → green → commit.
- **`ddl-auto: validate` is on.** New table needs a migration *and* a matching entity mapping.
- **Flyway versions string-sort wrong** — use `sort -V`. Latest is **V19**; this adds **V20**.
- **Idempotent inserts are `INSERT … ON CONFLICT … DO NOTHING`**, never `existsBy` + `save` + catch.
- **Claim-lease uses `FOR UPDATE SKIP LOCKED`** — copy the shape from `ScanJobRepository#claimDue`.
- Owner-scope everything and assert it.
- **No custom JavaScript.** The app ships none.
- `ModularityTests` must stay green.

## The gate

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain > /tmp/gate.log 2>&1
python3 scripts/check_adrs.py
```

Gradle cannot *launch* on JDK 25 — launch on 21. Docker Desktop must be running. ~6-7 min, exceeds the tool timeout: background it and poll the log.

**When a test times out waiting on something, first check whether the awaited work ever ran** — see CLAUDE.md. That confusion cost #132 and #172 real time.

## Design decisions already settled (from the spec)

- **One row per name**, not one per file: per-name retry, exact progress, and one bad name cannot fail the batch.
- **Not extending `AbstractJob`** — it requires a non-null `artist_id`, and an import row has no artist yet. Mirror the shape; don't inherit a column it can't satisfy.
- **Re-upload is idempotent**: names already `PENDING` for that owner are skipped. `DONE` rows do **not** block re-queueing — re-importing after removing an artist must work.
- **Failed names stay visible** with their error.
- **The poller is paced**, but without external-API backoff — this work is database-only.

---

### Task 1: The `artist_import` table

**Files:**
- Create: `src/main/resources/db/migration/V20__create_artist_import.sql`
- Create: `src/main/java/com/robsartin/setlistscout/catalog/ArtistImport.java`
- Create: `src/main/java/com/robsartin/setlistscout/catalog/ArtistImportStatus.java`
- Create: `src/main/java/com/robsartin/setlistscout/catalog/ArtistImportRepository.java`
- Test: `src/test/java/com/robsartin/setlistscout/catalog/ArtistImportRepositoryTest.java`

**Interfaces produced:**
- `ArtistImportStatus` — `PENDING`, `DONE`, `FAILED`
- `ArtistImport(String owner, String name, String normalizedName)` with getters for all columns plus setters for `status`, `attempts`, `lastError`, `claimedAt`, `nextDueAt`
- `ArtistImportRepository#insertIfAbsent(owner, name, normalizedName, nextDueAt, createdAt): int`
- `ArtistImportRepository#claimDue(now, leaseCutoff, batch): List<ArtistImport>`
- `ArtistImportRepository#countByOwnerAndStatus(String, ArtistImportStatus): long`
- `ArtistImportRepository#findByOwnerAndStatusOrderByNameAsc(String, ArtistImportStatus): List<ArtistImport>`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/catalog/ArtistImportRepositoryTest.java`:

```java
package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ArtistImportRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rob@example.com";
    private static final String OTHER = "david@example.com";

    @Autowired private ArtistImportRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    /** @Transactional: insertIfAbsent is a @Modifying native query and needs an ambient transaction. */
    @Transactional
    void queue(String owner, String name, Instant dueAt) {
        repository.insertIfAbsent(owner, name, ArtistNameNormalizer.normalize(name), dueAt, Instant.now());
    }

    @Test
    @Transactional
    @DisplayName("queues a name as PENDING")
    void queuesPending() {
        repository.insertIfAbsent(OWNER, "Wilco", ArtistNameNormalizer.normalize("Wilco"),
                Instant.now(), Instant.now());

        assertThat(repository.countByOwnerAndStatus(OWNER, ArtistImportStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("re-queueing a name already PENDING is a no-op -- a double upload does not double the work")
    void pendingDuplicateIsSkipped() {
        String n = ArtistNameNormalizer.normalize("Wilco");
        assertThat(repository.insertIfAbsent(OWNER, "Wilco", n, Instant.now(), Instant.now())).isEqualTo(1);
        assertThat(repository.insertIfAbsent(OWNER, "wilco", n, Instant.now(), Instant.now())).isZero();

        assertThat(repository.countByOwnerAndStatus(OWNER, ArtistImportStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("a DONE row does NOT block re-queueing -- re-importing after a removal must work")
    void doneDoesNotBlockRequeue() {
        String n = ArtistNameNormalizer.normalize("Wilco");
        repository.insertIfAbsent(OWNER, "Wilco", n, Instant.now(), Instant.now());
        ArtistImport row = repository.findAll().get(0);
        row.setStatus(ArtistImportStatus.DONE);
        repository.save(row);

        assertThat(repository.insertIfAbsent(OWNER, "Wilco", n, Instant.now(), Instant.now())).isEqualTo(1);
        assertThat(repository.countByOwnerAndStatus(OWNER, ArtistImportStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("the same name for two owners is queued independently")
    void ownersAreIndependent() {
        String n = ArtistNameNormalizer.normalize("Wilco");
        assertThat(repository.insertIfAbsent(OWNER, "Wilco", n, Instant.now(), Instant.now())).isEqualTo(1);
        assertThat(repository.insertIfAbsent(OTHER, "Wilco", n, Instant.now(), Instant.now())).isEqualTo(1);

        assertThat(repository.countByOwnerAndStatus(OWNER, ArtistImportStatus.PENDING)).isEqualTo(1);
        assertThat(repository.countByOwnerAndStatus(OTHER, ArtistImportStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("claimDue returns only rows that are due, and marks them claimed")
    void claimDueRespectsDueTime() {
        Instant now = Instant.now();
        repository.insertIfAbsent(OWNER, "Due Now", ArtistNameNormalizer.normalize("Due Now"),
                now.minusSeconds(10), now);
        repository.insertIfAbsent(OWNER, "Not Yet", ArtistNameNormalizer.normalize("Not Yet"),
                now.plus(Duration.ofHours(1)), now);

        List<ArtistImport> claimed = repository.claimDue(now, now.minus(Duration.ofMinutes(5)), 20);

        assertThat(claimed).extracting(ArtistImport::getName).containsExactly("Due Now");
        assertThat(claimed.get(0).getClaimedAt()).isNotNull();
    }

    @Test
    @Transactional
    @DisplayName("a claimed row is not claimed again until its lease expires")
    void claimIsLeased() {
        Instant now = Instant.now();
        repository.insertIfAbsent(OWNER, "Wilco", ArtistNameNormalizer.normalize("Wilco"),
                now.minusSeconds(10), now);

        assertThat(repository.claimDue(now, now.minus(Duration.ofMinutes(5)), 20)).hasSize(1);
        assertThat(repository.claimDue(now, now.minus(Duration.ofMinutes(5)), 20))
                .as("still inside the lease window").isEmpty();
        assertThat(repository.claimDue(now, now.plus(Duration.ofMinutes(5)), 20))
                .as("lease expired -- reclaimable, so a crashed worker's row is not lost").hasSize(1);
    }

    @Test
    @Transactional
    @DisplayName("failed names are listable with their error")
    void failedAreListable() {
        Instant now = Instant.now();
        repository.insertIfAbsent(OWNER, "Bad Name", ArtistNameNormalizer.normalize("Bad Name"), now, now);
        ArtistImport row = repository.findAll().get(0);
        row.setStatus(ArtistImportStatus.FAILED);
        row.setLastError("boom");
        repository.save(row);

        List<ArtistImport> failed = repository.findByOwnerAndStatusOrderByNameAsc(OWNER, ArtistImportStatus.FAILED);
        assertThat(failed).extracting(ArtistImport::getName).containsExactly("Bad Name");
        assertThat(failed.get(0).getLastError()).isEqualTo("boom");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*ArtistImportRepositoryTest" --console=plain --rerun`
Expected: FAIL — none of these types exist.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V20__create_artist_import.sql`:

```sql
-- #177: one row per name from a bulk artist upload, drained by a claim-lease poller.
-- The upload endpoint used to call addSeedIfNew per line inside the HTTP request; a real
-- 1,138-name file returned a 502 and was killed part-way by the free tier's idle spin-down
-- after importing 79 names. Queueing lets the request return immediately.
--
-- Deliberately NOT modelled on scan_job/expand_job's shared AbstractJob mapping: that requires a
-- non-null artist_id, and an import row has no artist yet -- creating one is the whole point.
CREATE TABLE IF NOT EXISTS artist_import (
    id              bigserial PRIMARY KEY,
    owner           varchar(255) NOT NULL,
    name            varchar(255) NOT NULL,
    normalized_name varchar(255) NOT NULL,
    status          varchar(32)  NOT NULL,
    attempts        integer      NOT NULL DEFAULT 0,
    last_error      text,
    claimed_at      timestamp(6) with time zone,
    next_due_at     timestamp(6) with time zone NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL
);

-- Idempotent re-upload, enforced by the database rather than a read-then-write check:
-- a name already queued for this owner cannot be queued twice. PARTIAL, on PENDING only --
-- a DONE row must not block re-importing a name the owner later removed and wants back.
CREATE UNIQUE INDEX IF NOT EXISTS artist_import_pending_key
    ON artist_import (owner, normalized_name) WHERE status = 'PENDING';

-- The poller's claim query orders by next_due_at over due rows.
CREATE INDEX IF NOT EXISTS idx_artist_import_due
    ON artist_import (next_due_at) WHERE status = 'PENDING';
```

- [ ] **Step 4: Write the status enum and entity**

Create `src/main/java/com/robsartin/setlistscout/catalog/ArtistImportStatus.java`:

```java
package com.robsartin.setlistscout.catalog;

/**
 * Lifecycle of one queued import name (#177).
 * <p>
 * Deliberately not {@code shared.JobStatus}: a scan or expand job is recurring and returns to
 * SCHEDULED after every run, whereas an import row is terminal -- once the name is seeded it is
 * DONE and never runs again. Sharing the enum would blur that difference.
 */
public enum ArtistImportStatus {
    /** Queued, not yet seeded. The only status the partial unique index constrains. */
    PENDING,
    /** Seeded successfully. Terminal. */
    DONE,
    /** Gave up after the retry cap. Terminal, and kept so the owner can see what failed and why. */
    FAILED
}
```

Create `src/main/java/com/robsartin/setlistscout/catalog/ArtistImport.java`:

```java
package com.robsartin.setlistscout.catalog;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One name from a bulk upload, waiting to be seeded (#177).
 * <p>
 * Mirrors the durable-job shape (claim lease, attempts, backoff) without extending
 * {@code shared.AbstractJob}, which requires a non-null {@code artist_id} that an import row by
 * definition does not have yet.
 */
@Entity
@Table(name = "artist_import")
public class ArtistImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    /** The name exactly as the owner supplied it -- what gets seeded and what they see. */
    @Column(nullable = false)
    private String name;

    /** {@link ArtistNameNormalizer#normalize} of {@link #name}; the partial unique index keys on it. */
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ArtistImportStatus status = ArtistImportStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** Set while a worker holds this row; null when idle. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ArtistImport() {
        // JPA
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public ArtistImportStatus getStatus() { return status; }
    public void setStatus(ArtistImportStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Instant getNextDueAt() { return nextDueAt; }
    public void setNextDueAt(Instant nextDueAt) { this.nextDueAt = nextDueAt; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: Write the repository**

Create `src/main/java/com/robsartin/setlistscout/catalog/ArtistImportRepository.java`:

```java
package com.robsartin.setlistscout.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ArtistImportRepository extends JpaRepository<ArtistImport, Long> {

    long countByOwnerAndStatus(String owner, ArtistImportStatus status);

    List<ArtistImport> findByOwnerAndStatusOrderByNameAsc(String owner, ArtistImportStatus status);

    /**
     * Queue one name, idempotently. {@code ON CONFLICT DO NOTHING} against the PARTIAL unique index
     * {@code artist_import_pending_key} — so re-uploading a file while its rows are still PENDING
     * queues nothing new, while a name whose earlier import is DONE can be queued again.
     * <p>
     * The DB-level conflict, rather than an {@code existsBy} pre-check, is this codebase's standing
     * rule for idempotent inserts: a read-then-write races, and inside a listener a resulting
     * constraint violation would poison the whole transaction.
     *
     * @return 1 if this call queued the name, 0 if it was already pending
     */
    @Modifying
    @Query(value = """
            INSERT INTO artist_import (owner, name, normalized_name, status, attempts, next_due_at, created_at)
            VALUES (:owner, :name, :normalizedName, 'PENDING', 0, :nextDueAt, :createdAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("owner") String owner,
                        @Param("name") String name,
                        @Param("normalizedName") String normalizedName,
                        @Param("nextDueAt") Instant nextDueAt,
                        @Param("createdAt") Instant createdAt);

    /**
     * Claim a batch of due rows, mirroring {@code ScanJobRepository#claimDue}: {@code FOR UPDATE
     * SKIP LOCKED} so concurrent workers never contend, and a lease so a row whose worker died is
     * reclaimable rather than stuck forever.
     */
    @Modifying
    @Query(value = """
            UPDATE artist_import SET claimed_at = :now
            WHERE id IN (
                SELECT id FROM artist_import
                WHERE status = 'PENDING' AND next_due_at <= :now
                  AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
                ORDER BY next_due_at
                LIMIT :batch
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<ArtistImport> claimDue(@Param("now") Instant now,
                                 @Param("leaseCutoff") Instant leaseCutoff,
                                 @Param("batch") int batch);
}
```

- [ ] **Step 6: Run the test and watch it pass**

Run: `./gradlew --no-daemon test --tests "*ArtistImportRepositoryTest" --console=plain --rerun`
Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V20__create_artist_import.sql \
        src/main/java/com/robsartin/setlistscout/catalog/ArtistImport.java \
        src/main/java/com/robsartin/setlistscout/catalog/ArtistImportStatus.java \
        src/main/java/com/robsartin/setlistscout/catalog/ArtistImportRepository.java \
        src/test/java/com/robsartin/setlistscout/catalog/ArtistImportRepositoryTest.java
git commit -m "feat: artist_import table for queued bulk uploads (#177)"
```

---

### Task 2: The poller

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/catalog/ArtistImportPoller.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/robsartin/setlistscout/catalog/ArtistImportPollerTest.java`

**Interfaces:**
- Consumes: `ArtistImportRepository#claimDue`, `ArtistSeedService#addSeedIfNew(String, String): boolean`
- Produces: `ArtistImportPoller#tick(): void`, plus a package-private `Clock` constructor as a test seam

**Read `ScanPoller` first and follow its structure.** Two deliberate differences: an import row is **terminal** on success (`DONE`, never rescheduled — unlike a scan job which returns to `SCHEDULED`), and there is no per-source interval, because this work never touches an external API.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/catalog/ArtistImportPollerTest.java`. Cover:

- **Happy path:** a PENDING row is claimed, the artist is seeded (assert via `ArtistRepository`), and the row becomes `DONE`.
- **A name already active** still ends `DONE` — `addSeedIfNew` returning `false` is a legitimate no-op, not a failure.
- **Batch limit respected:** with more due rows than the batch size, one tick claims exactly the batch size.
- **Failure retries:** if seeding throws, the row goes back to `PENDING` with `attempts` incremented, `lastError` set, `claimedAt` cleared, and `nextDueAt` in the future.
- **Retry cap:** once `attempts` reaches the cap, the row becomes `FAILED` (terminal) and is not claimed again.
- **Owner isolation:** a row for owner A never creates an artist for owner B.

Use a `Clock` seam so backoff assertions are deterministic — `ScanPoller` has exactly this constructor for the same reason. Do **not** assert on wall-clock sleeps.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*ArtistImportPollerTest" --console=plain --rerun`
Expected: FAIL — `ArtistImportPoller` does not exist.

- [ ] **Step 3: Write the poller**

Create `ArtistImportPoller`, modelled on `ScanPoller`:

- `@Component`, `@ConditionalOnProperty(name = "setlistscout.import-poller-enabled", havingValue = "true", matchIfMissing = true)`.
- `@Scheduled(fixedDelayString = "${setlistscout.import-tick-ms:5000}", initialDelayString = "${setlistscout.import-tick-ms:5000}")`. Faster than the scan tick (90s): this work is database-only, and a 1,138-name import should drain in minutes, not hours.
- Two constructors — the `@Autowired` production one and a package-private `Clock` seam. **Copy `ScanPoller`'s explicit `@Autowired` and its comment**: with two constructors, Spring's implicit single-constructor autowiring does not apply and startup fails without it.
- `tick()`: claim a batch, run each.
- Success → `status = DONE`, `claimedAt = null`.
- Failure → `attempts++`, `lastError` truncated (reuse `ScanPoller`'s `LAST_ERROR_MAX_LEN` value of 8000), `claimedAt = null`; below the cap go back to `PENDING` with an exponential `nextDueAt`, at or above the cap set `FAILED`.
- Log each failure at WARN with `owner` and `name`.

Add to `application.yml` under `setlistscout:`:

```yaml
  # #177: bulk-import queue. Faster tick than the scan poller because this work is database-only
  # -- no external API to pace against -- so a large import drains in minutes rather than hours.
  import-poller-enabled: ${IMPORT_POLLER_ENABLED:true}
  import-tick-ms: ${IMPORT_TICK_MS:5000}
  import-batch-size: ${IMPORT_BATCH_SIZE:25}
  import-max-attempts: ${IMPORT_MAX_ATTEMPTS:3}
```

Bind those the way the existing poller settings are bound — check whether they belong on `PollerProperties` or read via `@Value`, and follow whichever pattern the neighbouring settings already use.

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew --no-daemon test --tests "*ArtistImportPollerTest" --console=plain --rerun`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/ArtistImportPoller.java \
        src/main/resources/application.yml \
        src/test/java/com/robsartin/setlistscout/catalog/ArtistImportPollerTest.java
git commit -m "feat: claim-lease poller that drains queued artist imports (#177)"
```

---

### Task 3: The upload endpoint and progress display

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/catalog/ArtistImportService.java`
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistController.java`
- Modify: `src/main/resources/templates/artists.html`
- Test: `src/test/java/com/robsartin/setlistscout/catalog/ArtistImportUploadTest.java`

**Interfaces:**
- Produces: `ArtistImportService#queue(String owner, java.io.BufferedReader reader): int` — returns how many names were newly queued

- [ ] **Step 1: Write the failing test**

Create `ArtistImportUploadTest`, a `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` test posting a multipart file to `/artists/upload`. Cover:

- **Returns before processing.** Immediately after the POST, the pending count equals the number of names and **no artist has been created yet**. Disable the poller for this test (`setlistscout.import-poller-enabled=false` via `@DynamicPropertySource`) so the assertion is deterministic rather than racing the drain — that is the whole point of the endpoint.
- **Duplicates within one file are queued once** (e.g. `Wilco` and `wilco` on separate lines).
- **Blank lines and `#` comments are skipped.**
- **Re-uploading while rows are still PENDING queues nothing new.**
- **The `MAX_UPLOAD_LINES` cap (2000) still applies.**
- **Owner scoping:** the queued rows belong to the uploading user.
- **The flash message reports the queued count**, not an "added" count.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*ArtistImportUploadTest" --console=plain --rerun`
Expected: FAIL.

- [ ] **Step 3: Write the service**

`ArtistImportService#queue(owner, reader)` reads at most `MAX_UPLOAD_LINES` lines; for each, trims, skips blanks and `#` comments, normalizes via `ArtistNameNormalizer`, dedupes **within the file** by normalized form, and calls `ArtistImportRepository#insertIfAbsent`. Returns the count of rows actually inserted (the sum of `insertIfAbsent`'s return values). Annotate `@Transactional` — `insertIfAbsent` is `@Modifying` and needs an ambient transaction.

- [ ] **Step 4: Rewrite the endpoint**

Replace `ArtistController#upload`'s body: delegate to `ArtistImportService#queue` and set a flash message naming the queued count, e.g. `"Queued 1,138 names. They'll be added in the background."` Keep the `IOException` handling and its "Could not read that file." message. `MAX_UPLOAD_LINES` moves to the service with the loop; do not leave a dangling copy behind.

- [ ] **Step 5: Add the progress display**

In `populateActive` (or wherever the Artists page model is built), add pending and failed counts for the owner. In `artists.html`, near the upload form, render:

- when pending > 0: `Importing: N remaining.`
- when failed > 0: `N names could not be imported.` with the names listed (they are already in the model via `findByOwnerAndStatusOrderByNameAsc`).

Plain server-rendered text. **No JavaScript, no polling** — it refreshes on the next page load or htmx swap. Give the pending notice `role="status"` so it is announced, matching how `uploadMessage` is already handled.

- [ ] **Step 6: Run the test and watch it pass**

Run: `./gradlew --no-daemon test --tests "*ArtistImport*" --tests "*ArtistControllerTest" --console=plain --rerun`
Expected: PASS.

- [ ] **Step 7: Run the full gate**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain > /tmp/gate.log 2>&1
python3 scripts/check_adrs.py
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/ArtistImportService.java \
        src/main/java/com/robsartin/setlistscout/catalog/ArtistController.java \
        src/main/resources/templates/artists.html \
        src/test/java/com/robsartin/setlistscout/catalog/ArtistImportUploadTest.java
git commit -m "feat: upload queues names and returns immediately (#177)"
```

---

## Notes for the implementer

**The partial unique index is the idempotency mechanism.** `artist_import_pending_key` is `UNIQUE (owner, normalized_name) WHERE status = 'PENDING'`. That is what makes a double upload harmless and a re-import after removal still possible. Do not replace it with an application-level `existsBy` check — a read-then-write races, and the DB-level conflict is this codebase's standing rule for idempotent inserts.

**An import row is terminal on success.** Unlike a scan job, it does not reschedule. `ScanPoller` is the structural model, not the semantic one.

**`addSeedIfNew` returning `false` is success, not failure.** It means the name was already active — exactly what should happen for the many names in a 1,138-artist list that are already in the catalog. Marking those `FAILED` would make a normal import look broken.

**Do not couple this to expansion.** Imported seeds publish `ArtistActivated` like any other seed and expand normally. That is intended: the owner has said he wants the expanding universe.

**Not in scope:** CSV/JSON parsing, import history beyond pending/failed counts, undo, cancelling an in-flight import, and any change to `addSeedIfNew`'s meaning.
