# Follow a Venue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Follow a venue's own calendar as a show source, so presenters Ticketmaster does not carry (Cap City Comedy Club, and later others) produce shows.

**Architecture:** A `venue` entity in the `scan` module with its own claim-lease poller, reusing `BandSiteScraperService` for extraction. Every extracted show is persisted; venue-sourced shows display only when their performer is an artist the owner actively follows, and unmatched performers become `PENDING_REVIEW` candidates via a cross-module event.

**Tech Stack:** Java 21 source / JDK 25 toolchain, Spring Boot 3.5, Spring Modulith, Postgres + Flyway, Thymeleaf + vendored htmx, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-20-follow-a-venue-design.md`

## Global Constraints

- **Gradle cannot launch on JDK 25.** `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem`; the toolchain forks 25.
- The gate is `./gradlew --no-daemon clean build`, then `python3 scripts/check_adrs.py`, then `python3 scripts/check_migrations.py`. It exceeds the tool timeout — run it blocking, redirect to a log, poll the log.
- **Migrations live in two directories**: SQL in `src/main/resources/db/migration/`, Java in `src/main/java/db/migration/`. Latest across both is **V23**. Use `sort -V`, never `sort`.
- `ddl-auto: validate` is on: every schema change needs a Flyway migration **and** a matching entity mapping, or the app will not boot.
- **The app ships no custom JavaScript.** Keep it that way.
- Publish events inside a committing transaction (ADR-0024) — `@ApplicationModuleListener` is AFTER_COMMIT, and a publish with no committing transaction is silently dropped.
- Idempotent writes inside a listener must be `INSERT … ON CONFLICT … DO NOTHING`, never `existsBy` + `save` + catch.
- Name equality is **always** `catalog.ArtistNameNormalizer` / `ArtistNameMatcher`. Never hand-roll normalization.
- Owner-scope every query, action, and page, and assert it in tests.
- Modulith boundaries are enforced by `ModularityTests`. `scan` must not write `catalog`'s aggregate directly.
- TDD: failing test → run it → confirm it fails for the right reason → implement → green → commit.
- **Verify every new test actually fails before its fix.** Six tests on the #208 branch initially passed against unfixed code because their expected values matched the old defaults. Mutation-test: revert the change, re-run, confirm red, restore.

---

### Task 1: `venue` entity, migration, repository

**Files:**
- Create: `src/main/resources/db/migration/V24__create_venue.sql`
- Create: `src/main/java/com/robsartin/setlistscout/scan/Venue.java`
- Create: `src/main/java/com/robsartin/setlistscout/scan/VenueRepository.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/VenueRepositoryTest.java`

**Interfaces:**
- Produces: `Venue` (getters `getId/getOwner/getName/getNormalizedName/getCalendarUrl`), `VenueRepository.findByOwnerOrderByNameAsc(String)`, `VenueRepository.insertIfAbsent(...)`, `VenueRepository.findByIdAndOwner(Long, String)`.

- [ ] **Step 1: Write the failing test**

```java
@Test
@DisplayName("two venues with case-variant names for one owner collide on the unique index")
void rejectsCaseVariantDuplicateForSameOwner() {
    venueRepository.insertIfAbsent("rob@example.com", "Cap City Comedy Club",
            ArtistNameNormalizer.normalize("Cap City Comedy Club"),
            "https://www.capcitycomedy.com/events", Instant.now());
    int second = venueRepository.insertIfAbsent("rob@example.com", "cap city COMEDY club",
            ArtistNameNormalizer.normalize("cap city COMEDY club"),
            "https://example.com/other", Instant.now());
    assertThat(second).isZero();
    assertThat(venueRepository.findByOwnerOrderByNameAsc("rob@example.com")).hasSize(1);
}

@Test
@DisplayName("the same venue name under two different owners is allowed")
void allowsSameNameForDifferentOwners() {
    venueRepository.insertIfAbsent("a@example.com", "Cap City Comedy Club",
            ArtistNameNormalizer.normalize("Cap City Comedy Club"), "https://x/events", Instant.now());
    venueRepository.insertIfAbsent("b@example.com", "Cap City Comedy Club",
            ArtistNameNormalizer.normalize("Cap City Comedy Club"), "https://x/events", Instant.now());
    assertThat(venueRepository.findByOwnerOrderByNameAsc("a@example.com")).hasSize(1);
    assertThat(venueRepository.findByOwnerOrderByNameAsc("b@example.com")).hasSize(1);
}
```

Extend `AbstractPostgresIntegrationTest`, as every other repository test in this package does.

- [ ] **Step 2: Run it, expect failure**

`./gradlew --no-daemon test --tests '*VenueRepositoryTest*' --console=plain`
Expected: compilation failure — `Venue`/`VenueRepository` do not exist.

- [ ] **Step 3: Write the migration**

```sql
-- V24__create_venue.sql
CREATE TABLE venue (
    id              BIGSERIAL PRIMARY KEY,
    owner           VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    calendar_url    VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- owner first: every lookup is owner-scoped. Mirrors artist's (owner, normalized_name)
-- uniqueness from V21 -- one definition of "same name", app-wide.
CREATE UNIQUE INDEX venue_owner_normalized_name ON venue (owner, normalized_name);
```

- [ ] **Step 4: Write the entity and repository**

`Venue` mirrors `ArtistImport`'s shape: `@Entity`, `@Table(name = "venue")`, `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`, a `protected Venue()` for JPA, and a package-private test-fixture constructor. `owner`/`name`/`normalizedName` get no setters — a venue's identity is fixed at creation. `calendarUrl` gets a setter (the owner can correct a URL).

`VenueRepository extends JpaRepository<Venue, Long>` with a native insert, following `ArtistImportRepository#insertIfAbsent`:

```java
@Modifying
@Query(value = """
        INSERT INTO venue (owner, name, normalized_name, calendar_url, created_at)
        VALUES (:owner, :name, :normalizedName, :calendarUrl, :createdAt)
        ON CONFLICT (owner, normalized_name) DO NOTHING
        """, nativeQuery = true)
int insertIfAbsent(@Param("owner") String owner,
                   @Param("name") String name,
                   @Param("normalizedName") String normalizedName,
                   @Param("calendarUrl") String calendarUrl,
                   @Param("createdAt") Instant createdAt);

List<Venue> findByOwnerOrderByNameAsc(String owner);

Optional<Venue> findByIdAndOwner(Long id, String owner);
```

`ON CONFLICT` names the constraint it must suppress. Per CLAUDE.md it suppresses **only** that one — do not assume it covers others.

- [ ] **Step 5: Run the test, expect pass**

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V24__create_venue.sql src/main/java/com/robsartin/setlistscout/scan/Venue.java src/main/java/com/robsartin/setlistscout/scan/VenueRepository.java src/test/java/com/robsartin/setlistscout/scan/VenueRepositoryTest.java
git commit -m "#206: add the venue entity, its migration, and its repository"
```

---

### Task 2: `venue_scan_job` with a claim lease

**Files:**
- Create: `src/main/resources/db/migration/V25__create_venue_scan_job.sql`
- Create: `src/main/java/com/robsartin/setlistscout/scan/VenueScanJob.java`
- Create: `src/main/java/com/robsartin/setlistscout/scan/VenueScanJobRepository.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/VenueScanJobRepositoryTest.java`

**Interfaces:**
- Consumes: `Venue` from Task 1.
- Produces: `VenueScanJobRepository.claimDue(Instant now, Instant leaseCutoff, int batch)` returning `List<VenueScanJob>`; `VenueScanJob` getters `getId/getOwner/getVenueId/getStatus/getAttempts/getNextDueAt`, setters `setStatus/setClaimedAt/setNextDueAt/setLastRunAt/setAttempts/setLastError`.

**Do NOT extend `shared.AbstractJob`.** It requires a non-null `artist_id`, which a venue job has no value for. Mirror the shape; do not inherit a column it cannot satisfy. This is the same call `ArtistImport` made in #177 — read that class first.

- [ ] **Step 1: Write the failing test**

```java
@Test
@DisplayName("claimDue returns only due, unclaimed rows and stamps claimed_at")
void claimsOnlyDueUnclaimedRows() {
    Long due = insertJob(OWNER, venueId, Instant.now().minusSeconds(60), null);
    Long notDue = insertJob(OWNER, venueId2, Instant.now().plusSeconds(600), null);

    List<VenueScanJob> claimed = venueScanJobRepository.claimDue(
            Instant.now(), Instant.now().minusSeconds(300), 10);

    assertThat(claimed).extracting(VenueScanJob::getId).containsExactly(due).doesNotContain(notDue);
    assertThat(claimed.get(0).getClaimedAt()).isNotNull();
}

@Test
@DisplayName("a row claimed within the lease window is not re-claimed")
void doesNotReclaimWithinLease() {
    insertJob(OWNER, venueId, Instant.now().minusSeconds(60), Instant.now().minusSeconds(10));
    assertThat(venueScanJobRepository.claimDue(
            Instant.now(), Instant.now().minusSeconds(300), 10)).isEmpty();
}

@Test
@DisplayName("a row whose lease has expired is reclaimable, so a dead worker does not strand it")
void reclaimsAfterLeaseExpiry() {
    insertJob(OWNER, venueId, Instant.now().minusSeconds(60), Instant.now().minusSeconds(600));
    assertThat(venueScanJobRepository.claimDue(
            Instant.now(), Instant.now().minusSeconds(300), 10)).hasSize(1);
}
```

- [ ] **Step 2: Run it, expect failure** (classes do not exist)

- [ ] **Step 3: Write the migration**

```sql
-- V25__create_venue_scan_job.sql
CREATE TABLE venue_scan_job (
    id           BIGSERIAL PRIMARY KEY,
    owner        VARCHAR(255) NOT NULL,
    venue_id     BIGINT NOT NULL REFERENCES venue (id) ON DELETE CASCADE,
    status       VARCHAR(255) NOT NULL,
    attempts     INTEGER NOT NULL DEFAULT 0,
    last_error   TEXT,
    claimed_at   TIMESTAMP(6) WITH TIME ZONE,
    next_due_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_run_at  TIMESTAMP(6) WITH TIME ZONE,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- ON DELETE CASCADE: removing a venue must not strand its jobs. The claim query orders by
-- next_due_at, so index it alongside status.
CREATE INDEX venue_scan_job_due ON venue_scan_job (status, next_due_at);
CREATE UNIQUE INDEX venue_scan_job_venue ON venue_scan_job (owner, venue_id);
```

- [ ] **Step 4: Write the entity and repository**

Reuse `shared.JobStatus` for the status enum (`@Enumerated(EnumType.STRING)`) — it already has the states this needs, and a second parallel enum would drift.

```java
@Modifying
@Query(value = """
        UPDATE venue_scan_job SET claimed_at = :now
        WHERE id IN (
            SELECT id FROM venue_scan_job
            WHERE status = 'SCHEDULED' AND next_due_at <= :now
              AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
            ORDER BY next_due_at
            LIMIT :batch
            FOR UPDATE SKIP LOCKED
        )
        RETURNING *
        """, nativeQuery = true)
List<VenueScanJob> claimDue(@Param("now") Instant now,
                            @Param("leaseCutoff") Instant leaseCutoff,
                            @Param("batch") int batch);
```

`FOR UPDATE SKIP LOCKED` per ADR-0023 so concurrent workers never contend.

- [ ] **Step 5: Run the tests, expect pass**

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V25__create_venue_scan_job.sql src/main/java/com/robsartin/setlistscout/scan/VenueScanJob.java src/main/java/com/robsartin/setlistscout/scan/VenueScanJobRepository.java src/test/java/com/robsartin/setlistscout/scan/VenueScanJobRepositoryTest.java
git commit -m "#206: add venue_scan_job with a claim lease"
```

---

### Task 3: scan a venue and persist its shows

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/VenueScanRunner.java`
- Create: `src/main/java/com/robsartin/setlistscout/scan/VenueScanPoller.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/VenueScanRunnerTest.java`

**Interfaces:**
- Consumes: Task 1 `Venue`/`VenueRepository`, Task 2 `VenueScanJob`/`VenueScanJobRepository`, and the existing `BandSiteScraperService#scrapeShows(String artistName, String siteUrl, LocalDateTime start, LocalDateTime end)` which returns `List<Show>` already carrying per-show performer and kind (from #208, PR #216).
- Produces: `VenueScanRunner.run(VenueScanJob job)`.

**No radius filter.** Unlike `BandSiteShowSource`, do not geocode-filter. The owner chose this venue deliberately; a venue does not move. This is a stated decision in the spec, not an oversight — do not "fix" it.

Pass the **venue's name** as `scrapeShows`'s `artistName` argument: it is used for the prompt's subject and as the per-show fallback when the extractor returns no performer.

Persist with `source = "venue:" + host(calendarUrl)`, matching the existing `band-site:<host>` convention visible in `show_event.source`.

- [ ] **Step 1: Write the failing test**

```java
@Test
@DisplayName("persists every extracted show with the performer as artist_name and a venue: source")
void persistsEveryExtractedShow() {
    when(scraper.scrapeShows(eq("Cap City Comedy Club"), eq(CALENDAR_URL), any(), any()))
            .thenReturn(List.of(
                    new Show("Matt Braunger", DATE_1, "The Red Room at Cap City", "Austin",
                            null, "x", "u", Show.Kind.COMEDY),
                    new Show("Nick Mullen", DATE_2, "Cap City Comedy Club", "Austin",
                            null, "x", "u", Show.Kind.COMEDY)));

    runner.run(job);

    List<Show> stored = showRepository.findByOwnerOrderByEventDateTimeAsc(OWNER);
    assertThat(stored).extracting(Show::getArtistName)
            .containsExactlyInAnyOrder("Matt Braunger", "Nick Mullen");
    assertThat(stored).allSatisfy(s -> assertThat(s.getSource()).startsWith("venue:"));
    assertThat(stored).extracting(Show::getKind).containsOnly(Show.Kind.COMEDY);
}

@Test
@DisplayName("keeps the room name the extractor reported, not the venue's own name")
void keepsExtractedRoomName() {
    when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of(
            new Show("Matt Braunger", DATE_1, "The Red Room at Cap City", "Austin",
                    null, "x", "u", Show.Kind.COMEDY)));
    runner.run(job);
    assertThat(showRepository.findByOwnerOrderByEventDateTimeAsc(OWNER))
            .singleElement()
            .extracting(Show::getVenueName).isEqualTo("The Red Room at Cap City");
}

@Test
@DisplayName("a scraper failure marks the job failed and does not break the run")
void recordsScraperFailure() {
    when(scraper.scrapeShows(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));
    runner.run(job);
    VenueScanJob reloaded = venueScanJobRepository.findById(job.getId()).orElseThrow();
    assertThat(reloaded.getLastError()).contains("boom");
    assertThat(reloaded.getClaimedAt()).isNull();
}

@Test
@DisplayName("rescanning the same calendar does not duplicate shows")
void rescanIsIdempotent() {
    when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of(
            new Show("Matt Braunger", DATE_1, "Cap City Comedy Club", "Austin",
                    null, "x", "u", Show.Kind.COMEDY)));
    runner.run(job);
    runner.run(job);
    assertThat(showRepository.findByOwnerOrderByEventDateTimeAsc(OWNER)).hasSize(1);
}
```

`show_event` already has `UNIQUE (owner, artist_name, event_date_time, venue_name)` — rely on it via `ON CONFLICT DO NOTHING`, not a read-then-write.

- [ ] **Step 2: Run, expect failure** (`VenueScanRunner` does not exist)

- [ ] **Step 3: Implement `VenueScanRunner`**

Window: `LocalDateTime.now()` to `now.plusMonths(settings.getMonthsAhead())`, from `settingsService.getOrCreateSettings(owner)` — the same window `ShowController` uses, so a scanned show cannot fall outside the page's own range.

On success: `status = SCHEDULED`, `claimedAt = null`, `lastRunAt = now`, `nextDueAt = now + scanInterval`, `attempts = 0`. On failure: increment `attempts`, record `lastError`, back off, clear `claimedAt`.

- [ ] **Step 4: Implement `VenueScanPoller`**

Copy `ArtistImportPoller`'s shape exactly: `@Scheduled(fixedDelayString = "${setlistscout.venue-tick-ms:5000}", initialDelayString = "${setlistscout.venue-tick-ms:5000}")`, a `Clock` test seam, `claimDue`, then `runOne` per row wrapped in `Correlation.runWithNewId(...)`.

- [ ] **Step 5: Run the tests, expect pass**

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/VenueScanRunner.java src/main/java/com/robsartin/setlistscout/scan/VenueScanPoller.java src/test/java/com/robsartin/setlistscout/scan/VenueScanRunnerTest.java
git commit -m "#206: scan a venue calendar and persist its shows"
```

---

### Task 4: unmatched performers become candidates

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistSource.java`
- Create: `src/main/java/com/robsartin/setlistscout/shared/events/VenuePerformerSeen.java`
- Modify: `src/main/java/com/robsartin/setlistscout/scan/VenueScanRunner.java`
- Create: `src/main/java/com/robsartin/setlistscout/catalog/VenuePerformerListener.java`
- Test: `src/test/java/com/robsartin/setlistscout/catalog/VenuePerformerListenerTest.java`

**Interfaces:**
- Consumes: Task 3's `VenueScanRunner`.
- Produces: `ArtistSource.VENUE_EXPANSION`; `VenuePerformerSeen(String owner, String performerName)`.

**Cross-module by event only.** `scan` must not write `catalog`'s aggregate — `ModularityTests` enforces it. Per ADR-0024 the publish must happen **inside a committing transaction**, or the `@ApplicationModuleListener` silently never fires. Query and extract first, then publish in a short `TransactionTemplate`.

- [ ] **Step 1: Write the failing test**

```java
@Test
@DisplayName("an unmatched performer becomes exactly one PENDING_REVIEW artist sourced VENUE_EXPANSION")
void createsCandidateForUnmatchedPerformer() {
    listener.on(new VenuePerformerSeen(OWNER, "Nick Mullen"));
    Artist created = artistRepository.findByOwnerAndNormalizedName(
            OWNER, ArtistNameNormalizer.normalize("Nick Mullen")).orElseThrow();
    assertThat(created.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
    assertThat(created.getSource()).isEqualTo(ArtistSource.VENUE_EXPANSION);
}

@Test
@DisplayName("a performer already in the catalog is left completely alone")
void doesNotTouchExistingArtist() {
    seedArtist(OWNER, "Nick Mullen", ArtistStatus.APPROVED, ArtistSource.SEED_LIST);
    listener.on(new VenuePerformerSeen(OWNER, "nick MULLEN"));
    Artist existing = artistRepository.findByOwnerAndNormalizedName(
            OWNER, ArtistNameNormalizer.normalize("Nick Mullen")).orElseThrow();
    assertThat(existing.getStatus()).isEqualTo(ArtistStatus.APPROVED);
    assertThat(existing.getSource()).isEqualTo(ArtistSource.SEED_LIST);
}

@Test
@DisplayName("a rejected performer is not resurrected as a new candidate")
void doesNotResurrectRejected() {
    seedArtist(OWNER, "Nick Mullen", ArtistStatus.REJECTED, ArtistSource.VENUE_EXPANSION);
    listener.on(new VenuePerformerSeen(OWNER, "Nick Mullen"));
    assertThat(artistRepository.findByOwnerAndNormalizedName(
            OWNER, ArtistNameNormalizer.normalize("Nick Mullen")).orElseThrow()
            .getStatus()).isEqualTo(ArtistStatus.REJECTED);
}

@Test
@DisplayName("the same performer seen twice creates exactly one candidate")
void isIdempotent() {
    listener.on(new VenuePerformerSeen(OWNER, "Nick Mullen"));
    listener.on(new VenuePerformerSeen(OWNER, "Nick Mullen"));
    assertThat(artistRepository.findByOwner(OWNER)).hasSize(1);
}

@Test
@DisplayName("a performer seen for one owner never creates an artist for another")
void isOwnerScoped() {
    listener.on(new VenuePerformerSeen("a@example.com", "Nick Mullen"));
    assertThat(artistRepository.findByOwner("b@example.com")).isEmpty();
}
```

The rejected case matters: a venue calendar re-lists the same comedian every cycle, so without it a rejection would be undone on the next scan and reappear in the review queue forever.

- [ ] **Step 2: Run, expect failure**

- [ ] **Step 3: Add the enum value**

```java
VENUE_EXPANSION      // seen performing at a followed venue (#206)
```

- [ ] **Step 4: Implement the listener**

`@ApplicationModuleListener`, using `ArtistRepository#insertIfAbsent` with `ON CONFLICT (owner, normalized_name) DO NOTHING`. Never `existsBy` + `save` + catch: a constraint race poisons the whole listener transaction, which also breaks Modulith's completion write and causes endless redelivery.

`insertIfAbsent` handles all three of "already exists as APPROVED", "already exists as REJECTED", and "seen twice" with a single write and no branching — the row simply is not inserted.

- [ ] **Step 5: Publish from `VenueScanRunner`**

After persisting shows, publish one `VenuePerformerSeen` per distinct performer, inside the committing transaction.

- [ ] **Step 6: Run the tests, expect pass**

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/ArtistSource.java src/main/java/com/robsartin/setlistscout/shared/events/VenuePerformerSeen.java src/main/java/com/robsartin/setlistscout/scan/VenueScanRunner.java src/main/java/com/robsartin/setlistscout/catalog/VenuePerformerListener.java src/test/java/com/robsartin/setlistscout/catalog/VenuePerformerListenerTest.java
git commit -m "#206: turn unmatched venue performers into review candidates"
```

---

### Task 5: display venue shows only for artists the owner follows

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ShowController.java` (`populateShows`, around line 63)
- Test: `src/test/java/com/robsartin/setlistscout/web/ShowsPageRenderTest.java`

**Interfaces:**
- Consumes: Task 3's `venue:`-prefixed `source` values.

**This is the task most likely to cause a production bug. Read carefully.**

`TicketmasterService` stores the **event title** in `artist_name` (`label` is the event name, falling back to the artist name only when blank). The show recovered by #207 is stored as `A Very Merry Symphony ft. Austin Symphony Orchestra` — a string that is not, and never will be, a catalog name.

So the active-artist check applies **only to rows whose `source` starts with `venue:`**. A blanket filter hides every Ticketmaster show the owner has.

- [ ] **Step 1: Write the failing tests — the regression guard first**

```java
@Test
@DisplayName("a Ticketmaster show whose artist_name is an event title still displays")
void ticketmasterEventTitleShowStillDisplays() throws Exception {
    seedShow(OWNER, "A Very Merry Symphony ft. Austin Symphony Orchestra",
             "ticketmaster", FUTURE);
    mockMvc.perform(get("/shows").with(user(OWNER)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("A Very Merry Symphony")));
}

@Test
@DisplayName("a venue show whose performer is not an active artist is not displayed")
void venueShowForUnfollowedPerformerIsHidden() throws Exception {
    seedShow(OWNER, "Nick Mullen", "venue:www.capcitycomedy.com", FUTURE);
    mockMvc.perform(get("/shows").with(user(OWNER)))
            .andExpect(content().string(not(containsString("Nick Mullen"))));
}

@Test
@DisplayName("a venue show whose performer IS an active artist is displayed")
void venueShowForFollowedPerformerIsShown() throws Exception {
    seedArtist(OWNER, "Nick Mullen", ArtistStatus.APPROVED, ArtistSource.VENUE_EXPANSION);
    seedShow(OWNER, "Nick Mullen", "venue:www.capcitycomedy.com", FUTURE);
    mockMvc.perform(get("/shows").with(user(OWNER)))
            .andExpect(content().string(containsString("Nick Mullen")));
}

@Test
@DisplayName("venue-show matching is case- and punctuation-insensitive, like all name equality here")
void venueShowMatchingUsesNormalizedNames() throws Exception {
    seedArtist(OWNER, "Nick Mullen", ArtistStatus.APPROVED, ArtistSource.VENUE_EXPANSION);
    seedShow(OWNER, "nick mullen", "venue:www.capcitycomedy.com", FUTURE);
    mockMvc.perform(get("/shows").with(user(OWNER)))
            .andExpect(content().string(containsString("nick mullen")));
}

@Test
@DisplayName("a PENDING_REVIEW performer's venue shows are not displayed until approved")
void pendingPerformerShowsAreHidden() throws Exception {
    seedArtist(OWNER, "Nick Mullen", ArtistStatus.PENDING_REVIEW, ArtistSource.VENUE_EXPANSION);
    seedShow(OWNER, "Nick Mullen", "venue:www.capcitycomedy.com", FUTURE);
    mockMvc.perform(get("/shows").with(user(OWNER)))
            .andExpect(content().string(not(containsString("Nick Mullen"))));
}
```

- [ ] **Step 2: Run, expect the venue tests to fail and the Ticketmaster one to pass**

The Ticketmaster test passing here is correct and important — it pins existing behaviour so Step 3 cannot break it. Confirm the three venue tests fail for the right reason (the shows render when they should not).

- [ ] **Step 3: Implement the filter**

In `populateShows`, build the active-name set the same way the existing `tributeArtistNames` set is built a few lines below — `ArtistNameNormalizer.normalize` for the keys, not `toLowerCase`, since this is name equality:

```java
Set<String> activeArtistNames = artistRepository.findByOwner(owner).stream()
        .filter(a -> a.getStatus() == ArtistStatus.SEED || a.getStatus() == ArtistStatus.APPROVED)
        .map(a -> ArtistNameNormalizer.normalize(a.getName()))
        .collect(Collectors.toSet());

shows = shows.stream()
        .filter(s -> !s.getSource().startsWith("venue:")
                || activeArtistNames.contains(ArtistNameNormalizer.normalize(s.getArtistName())))
        .toList();
```

Apply this **before** `hiddenCount` is computed and before `focusable(outcome, shows)`.

Note `findByOwner` loads the full catalog (13k+ rows for the real owner). `populateShows` already does exactly this for `tributeArtistNames`, so this adds no new query — reuse one load for both sets rather than issuing a second.

- [ ] **Step 4: Run the tests, expect all five to pass**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/ShowController.java src/test/java/com/robsartin/setlistscout/web/ShowsPageRenderTest.java
git commit -m "#206: show venue-sourced shows only for artists the owner follows"
```

---

### Task 6: the `/venues` management page

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/VenueController.java`
- Create: `src/main/resources/templates/venues.html`
- Modify: `src/main/resources/templates/fragments/nav.html` (add the link)
- Test: `src/test/java/com/robsartin/setlistscout/web/VenuePageRenderTest.java`

**Interfaces:**
- Consumes: Tasks 1–3.

Mirror `/artists`, with three rules learned the hard way:

- **The add form goes above the list from the start.** #175 had to move the artist one retroactively.
- **Real `<label>` elements** on both inputs. This app treats labelled controls as an acceptance criterion.
- **No custom JavaScript.** If a swap needs focus, use a server-side `[autofocus]` in the swapped-in content — see `review.ActionOutcome` and #155.

Each row shows name, calendar URL, **last scanned**, and **shows contributed**. That last pair is load-bearing, not decoration: a venue whose calendar silently stops parsing looks identical to a venue with no shows, which is exactly how #211 hid. Get the count with a `COUNT(*) … GROUP BY` query, never `findAll().size()`.

- [ ] **Step 1: Write the failing test**

```java
@Test
@DisplayName("renders the add form above the venue list")
void rendersAddFormAboveList() throws Exception {
    String html = mockMvc.perform(get("/venues").with(user(OWNER)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(html.indexOf("id=\"add-venue\"")).isLessThan(html.indexOf("id=\"venue-list\""));
}

@Test
@DisplayName("both inputs are labelled")
void inputsAreLabelled() throws Exception {
    String html = mockMvc.perform(get("/venues").with(user(OWNER)))
            .andReturn().getResponse().getContentAsString();
    assertThat(html).contains("for=\"venue-name\"").contains("for=\"venue-url\"");
}

@Test
@DisplayName("shows last-scanned and contributed-show count per venue")
void showsScanHealth() throws Exception {
    // a venue with one contributed show and a last_run_at
    mockMvc.perform(get("/venues").with(user(OWNER)))
            .andExpect(content().string(containsString("1 show")));
}

@Test
@DisplayName("adding a venue creates it and its scan job")
void addingVenueCreatesScanJob() throws Exception {
    mockMvc.perform(post("/venues").with(user(OWNER)).with(csrf())
            .param("name", "Cap City Comedy Club")
            .param("url", "https://www.capcitycomedy.com/events"));
    assertThat(venueRepository.findByOwnerOrderByNameAsc(OWNER)).hasSize(1);
    assertThat(venueScanJobRepository.findAll()).hasSize(1);
}

@Test
@DisplayName("one owner never sees another's venues")
void isOwnerScoped() throws Exception {
    seedVenue("other@example.com", "Someone Else's Room", "https://x/events");
    mockMvc.perform(get("/venues").with(user(OWNER)))
            .andExpect(content().string(not(containsString("Someone Else's Room"))));
}
```

- [ ] **Step 2: Run, expect failure**
- [ ] **Step 3: Implement the controller and template**
- [ ] **Step 4: Run the tests, expect pass**
- [ ] **Step 5: Run the full gate**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain > /tmp/206-gate.log 2>&1
python3 scripts/check_adrs.py
python3 scripts/check_migrations.py
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/VenueController.java src/main/resources/templates/venues.html src/main/resources/templates/fragments/nav.html src/test/java/com/robsartin/setlistscout/web/VenuePageRenderTest.java
git commit -m "#206: add the venues management page"
```

---

## Verification beyond the suite

The unit and integration suite proves the plumbing. It does **not** prove a real calendar produces real shows — that gap is exactly what hid #211 through 3,016 scans and two green agent gates.

Before calling this done, add Cap City Comedy Club (`https://www.capcitycomedy.com/events`) against the real deployment and confirm:

- the venue scan runs and `venue_scan_job.last_run_at` is set;
- `show_event` gains rows with `source` starting `venue:` and real comedian names in `artist_name`;
- those shows carry `kind = COMEDY`;
- unmatched performers appear on the Candidates page as `VENUE_EXPANSION`;
- approving one makes their shows appear on `/shows` **without** a rescan;
- **the owner's existing Ticketmaster shows are all still visible** — the Task 5 regression, checked against production data rather than only a fixture.

A live extraction run on 2026-08-20 returned **181 shows, 2026-08-20 to 2027-11-10, all COMEDY, all with named performers**, so a correct implementation should land roughly that many rows within the owner's `monthsAhead` window.
