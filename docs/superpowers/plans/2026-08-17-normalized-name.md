# Stored `normalized_name` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make artist name-matching an indexed lookup instead of a full-catalog scan, so adding an artist stops costing O(all artists).

**Architecture:** Persist `ArtistNameNormalizer.normalize(name)` in a new `artist.normalized_name` column, populated on every write path and backfilled by a Java Flyway migration. `ArtistNameMatcher#findExistingMatch` keeps its exact signature and swaps its body for a single indexed query.

**Tech Stack:** Java 21 source / JDK 25 toolchain, Spring Boot 3.5, Spring Data JPA, Flyway (Java migration), Postgres + Testcontainers, JUnit 5 + Mockito + AssertJ.

**Issue:** #176. **Spec:** `docs/superpowers/specs/2026-08-17-artist-import-redesign-design.md` (Part A), currently in PR #178.

## The measurement this exists to fix

```java
public Optional<ArtistNameStatusView> findExistingMatch(String owner, String candidateName) {
    String target = ArtistNameNormalizer.normalize(candidateName);
    return artistRepository.findByOwner(owner).stream()          // ALL rows, no status filter
            .filter(view -> ArtistNameNormalizer.normalize(view.getName()).equals(target))
            .findFirst();
}
```

`findByOwner` is unfiltered; `rob.sartin@gmail.com` holds **13,236** artist rows. Every add pays a full scan plus 13,236 normalizations. A 1,138-name bulk import is ~15 million row-loads and normalizations — it timed out with a 502 in production and was killed by the free tier's spin-down after importing only 79 names.

## Scope decision: the unique constraint is deliberately NOT in this plan

The spec describes `UNIQUE (owner, normalized_name)` and dropping `addSeedIfNew`'s racy read-then-write. **That is a follow-up, not this issue.**

Reason: a unique constraint requires merging existing collisions, and a merge that repoints `artist_edge` / `scan_job` / `expand_job` references correctly is what `V13__merge_duplicate_variant_artists` already does in ~200 lines of careful Java. Reproducing or extracting that is real work with real risk, and it is not needed for the performance fix. A **non-unique** index delivers the entire speedup.

`findExistingMatch` therefore keeps today's exact semantics — "return the first match if any" — which remains correct with duplicates present, exactly as `.findFirst()` is today.

There is exactly **one** colliding group in production (`Paul Quinichette - John Coltrane Quintet` / `Paul Quinichette-John Coltrane Quintet`, both `REJECTED`, 3 edges between them, no jobs). Leave it. The follow-up issue merges it.

## Global Constraints

- **Never hand-roll normalization.** `catalog.ArtistNameNormalizer` is the app's single definition of "same name". The backfill must call it, not approximate it in SQL — an ASCII-stripping regex once collapsed every all-Hebrew and all-Japanese name to the same empty key, and a SQL approximation cannot fold unicode dashes or curly quotes.
- **`ddl-auto: validate` is on.** A new column needs both a migration and a matching entity mapping, or the app won't boot.
- **Flyway versions string-sort wrong** — use `sort -V`. Latest is **V18**; this adds **V19**.
- **`insertIfAbsent` is a native query.** JPA lifecycle callbacks do NOT fire for it. This is the single most likely way to get this change wrong.
- **Idempotent writes inside a listener stay `INSERT … ON CONFLICT DO NOTHING`** — never `existsBy` + `save` + catch.
- Owner-scope everything and assert it.
- Never commit to `main`. TDD: failing test → implement → green → commit.

## The gate

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain > /tmp/gate.log 2>&1
python3 scripts/check_adrs.py
```

Gradle cannot *launch* on JDK 25 — launch on 21, the toolchain forks 25. Docker Desktop must be running. The build takes ~7 min and exceeds the tool timeout: run it backgrounded and poll the log.

Known flakes — read the exception before believing a red gate: `AssertionFailedError` on an `awaitUntil` → #172's async flake, re-run the class in isolation with `--rerun`. `MockitoException: cannot mock this class` → build config (#160). **`:test` reporting `UP-TO-DATE` in ~3s is not a pass** — nothing ran.

## Every write path (mapped, do not re-derive)

An artist's **name** is set in exactly two places, both native inserts:
- `ArtistSeedService:95` — `insertIfAbsent(owner, name, SEED_LIST, SEED, null, null, now)`
- `RelationDiscoveredListener:120` — `insertIfAbsent(owner, toArtistName, type, PENDING_REVIEW, …)`

Three `save()` sites exist but none change the name — `ArtistController:113` (site-url), `ArtistActivationService:44` (status), `ArtistSiteUrlService:32` (site-url). Tests, however, do create artists via `new Artist(...)` + `save()`, so the JPA path must populate the column too.

---

### Task 1: The column, the backfill, and every write path

**Files:**
- Create: `src/main/java/db/migration/V19__add_artist_normalized_name.java`
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/Artist.java`
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java`
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistSeedService.java`
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/RelationDiscoveredListener.java`
- Test: `src/test/java/com/robsartin/setlistscout/migration/NormalizedNameBackfillTest.java`

**Interfaces:**
- Produces: `Artist#getNormalizedName(): String`, populated by `@PrePersist`/`@PreUpdate`
- Produces: `ArtistRepository#insertIfAbsent(owner, name, normalizedName, source, status, discoveredVia, note, createdAt): int` — one new parameter

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/migration/NormalizedNameBackfillTest.java`:

```java
package com.robsartin.setlistscout.migration;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #176. Every artist row must carry the normalizer's output in {@code normalized_name}, whichever
 * path created it. The native-insert case is the one a JPA lifecycle callback would silently miss.
 */
@SpringBootTest
@Testcontainers
class NormalizedNameBackfillTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rob@example.com";

    @Autowired private ArtistRepository artistRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        artistRepository.deleteAll();
    }

    private String storedNormalizedName(String name) {
        return jdbc.queryForObject(
                "SELECT normalized_name FROM artist WHERE owner = ? AND name = ?",
                String.class, OWNER, name);
    }

    @Test
    @DisplayName("the JPA save path stores the normalizer's output")
    void savePathPopulatesNormalizedName() {
        Artist artist = new Artist("Wilco", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(OWNER);
        artistRepository.save(artist);

        assertThat(storedNormalizedName("Wilco")).isEqualTo(ArtistNameNormalizer.normalize("Wilco"));
    }

    @Test
    @DisplayName("the NATIVE insertIfAbsent path stores it too -- a @PrePersist alone would miss this")
    void nativeInsertPopulatesNormalizedName() {
        String name = "Paul Quinichette - John Coltrane Quintet";
        artistRepository.insertIfAbsent(OWNER, name, ArtistNameNormalizer.normalize(name),
                ArtistSource.SEED_LIST.name(), ArtistStatus.SEED.name(), null, null, Instant.now());

        assertThat(storedNormalizedName(name)).isEqualTo(ArtistNameNormalizer.normalize(name));
        // The hyphen-spacing fold (#157) is what makes this differ from a plain lowercase.
        assertThat(storedNormalizedName(name)).isEqualTo("paul quinichette-john coltrane quintet");
    }

    @Test
    @DisplayName("unicode folding survives the round trip -- en dash and curly quote")
    void unicodeFoldingIsStored() {
        String enDash = "Only Murders in the Building – Cast";
        String curly = "Charlie Parker’s Re-boppers";
        for (String name : new String[] {enDash, curly}) {
            artistRepository.insertIfAbsent(OWNER, name, ArtistNameNormalizer.normalize(name),
                    ArtistSource.SEED_LIST.name(), ArtistStatus.SEED.name(), null, null, Instant.now());
        }

        assertThat(storedNormalizedName(enDash)).isEqualTo(ArtistNameNormalizer.normalize(enDash));
        assertThat(storedNormalizedName(curly)).isEqualTo(ArtistNameNormalizer.normalize(curly));
        // Proof it is not merely a lowercase copy: the en dash became a hyphen.
        assertThat(storedNormalizedName(enDash)).contains("-cast").doesNotContain("–");
    }

    @Test
    @DisplayName("a non-Latin name keeps its characters -- normalization must not ASCII-strip")
    void nonLatinNamePreserved() {
        String hebrew = "החברה";
        artistRepository.insertIfAbsent(OWNER, hebrew, ArtistNameNormalizer.normalize(hebrew),
                ArtistSource.SEED_LIST.name(), ArtistStatus.SEED.name(), null, null, Instant.now());

        assertThat(storedNormalizedName(hebrew)).isEqualTo(hebrew).isNotBlank();
    }

    @Test
    @DisplayName("the column is NOT NULL and indexed")
    void columnIsNotNullAndIndexed() {
        Integer nullable = jdbc.queryForObject(
                "SELECT CASE WHEN is_nullable = 'YES' THEN 1 ELSE 0 END FROM information_schema.columns "
                        + "WHERE table_name = 'artist' AND column_name = 'normalized_name'",
                Integer.class);
        assertThat(nullable).as("normalized_name must be NOT NULL").isZero();

        Integer indexes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'artist' "
                        + "AND indexdef ILIKE '%normalized_name%'",
                Integer.class);
        assertThat(indexes).as("normalized_name must be indexed -- the whole point of #176")
                .isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*NormalizedNameBackfillTest" --console=plain --rerun`
Expected: FAIL — compilation error (`insertIfAbsent` has no such overload) and no `normalized_name` column.

- [ ] **Step 3: Write the migration**

Create `src/main/java/db/migration/V19__add_artist_normalized_name.java`. Read `V13__merge_duplicate_variant_artists.java` first and follow its structure and voice — it is the precedent for a Java Flyway migration in this codebase.

```java
package db.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Issue #176: stores {@link ArtistNameNormalizer#normalize(String)}'s output in
 * {@code artist.normalized_name} so name matching becomes an indexed lookup instead of a
 * full-catalog scan.
 *
 * <p>Before this, {@code ArtistNameMatcher#findExistingMatch} loaded EVERY artist row for the
 * owner and re-normalized each one in Java, per candidate name -- 13,236 rows for the main user.
 * A 1,138-name bulk import was therefore ~15 million row-loads and normalizations; it timed out
 * with a 502 in production and was killed part-way by the free tier's idle spin-down.
 *
 * <h2>Why a Java migration, not SQL</h2>
 * Same reason as {@code V13__merge_duplicate_variant_artists}: {@link ArtistNameNormalizer} folds
 * unicode dashes and curly quotes with explicit character replacements and deliberately preserves
 * non-ASCII text. Reproducing that in SQL would be a second, hand-rolled definition of "same name"
 * that could silently drift from the Java one -- the exact drift that inflated #118's first
 * live-profiling pass from 3 real pairs to a false 13.
 *
 * <h2>Deliberately NOT unique</h2>
 * A {@code UNIQUE (owner, normalized_name)} constraint would require merging pre-existing
 * collisions, and merging correctly means repointing {@code artist_edge}/{@code scan_job}/{@code
 * expand_job} references -- which is what V13 already does in careful detail. That is a follow-up
 * issue, not this one. A plain index delivers the entire performance fix, and
 * {@code findExistingMatch} keeps its existing "first match wins" semantics, which stays correct
 * with duplicates present.
 */
public class V19__add_artist_normalized_name extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE artist ADD COLUMN IF NOT EXISTS normalized_name varchar(255)");
        }

        // Backfill in one pass. Read id+name, write normalized_name -- batched so a large catalog
        // does not build one enormous statement.
        try (PreparedStatement read = connection.prepareStatement(
                     "SELECT id, name FROM artist WHERE normalized_name IS NULL");
             PreparedStatement write = connection.prepareStatement(
                     "UPDATE artist SET normalized_name = ? WHERE id = ?");
             ResultSet rows = read.executeQuery()) {
            int batched = 0;
            while (rows.next()) {
                write.setString(1, ArtistNameNormalizer.normalize(rows.getString("name")));
                write.setLong(2, rows.getLong("id"));
                write.addBatch();
                if (++batched % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE artist ALTER COLUMN normalized_name SET NOT NULL");
            // Not unique -- see the class javadoc. Owner first: every lookup is owner-scoped.
            statement.execute("CREATE INDEX IF NOT EXISTS idx_artist_owner_normalized_name "
                    + "ON artist (owner, normalized_name)");
        }
    }
}
```

- [ ] **Step 4: Add the entity field**

In `src/main/java/com/robsartin/setlistscout/catalog/Artist.java`, add the field beside `name`:

```java
    /**
     * {@link ArtistNameNormalizer#normalize(String)} of {@link #name} (#176), persisted so name
     * matching is an indexed lookup rather than a scan-and-renormalize over the whole catalog.
     * <p>
     * Maintained by {@link #syncNormalizedName()} for the JPA path. The two NATIVE
     * {@code ArtistRepository#insertIfAbsent} call sites pass it explicitly instead, because
     * lifecycle callbacks do not fire for a native query -- relying on the callback alone would
     * leave nulls on exactly the path that creates every artist in production.
     */
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;
```

Add the lifecycle hook and getter:

```java
    @PrePersist
    @PreUpdate
    void syncNormalizedName() {
        this.normalizedName = ArtistNameNormalizer.normalize(name);
    }

    public String getNormalizedName() { return normalizedName; }
```

`@PrePersist` and `@PreUpdate` come from `jakarta.persistence`, already wildcard-imported in this file.

- [ ] **Step 5: Add the parameter to the native insert**

In `src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java`, change `insertIfAbsent` to carry the normalized name. Keep the existing Javadoc and add a line explaining why the caller supplies it:

```java
    @Modifying
    @Query(value = """
            INSERT INTO artist (owner, name, normalized_name, source, status, discovered_via, note, created_at)
            VALUES (:owner, :name, :normalizedName, :source, :status, :discoveredVia, :note, :createdAt)
            ON CONFLICT (owner, name) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("owner") String owner,
                        @Param("name") String name,
                        @Param("normalizedName") String normalizedName,
                        @Param("source") String source,
                        @Param("status") String status,
                        @Param("discoveredVia") String discoveredVia,
                        @Param("note") String note,
                        @Param("createdAt") Instant createdAt);
```

Add to its Javadoc: *"`normalizedName` is passed by the caller rather than derived here because this is a native query — `Artist`'s `@PrePersist` never runs for it (#176)."*

- [ ] **Step 6: Update both native-insert call sites**

`ArtistSeedService:95` — pass `ArtistNameNormalizer.normalize(name)` as the third argument.
`RelationDiscoveredListener:120` — pass `ArtistNameNormalizer.normalize(toArtistName)` as the third argument.

Both classes are in `catalog`, so `ArtistNameNormalizer` needs no import change in `ArtistSeedService`; add one in `RelationDiscoveredListener` if absent.

- [ ] **Step 7: Run the test and watch it pass**

Run: `./gradlew --no-daemon test --tests "*NormalizedNameBackfillTest" --console=plain --rerun`
Expected: PASS, 5 tests.

- [ ] **Step 8: Run the catalog suite — the blast radius**

Run: `./gradlew --no-daemon test --tests "*catalog*" --tests "*ArtistSeedService*" --tests "*RelationDiscovered*" --console=plain --rerun`
Expected: PASS. Any Mockito stub of `insertIfAbsent` in a test now has the wrong arity and must be updated — that is expected mechanical fallout, not a design problem. Do not weaken an assertion to make one pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/db/migration/V19__add_artist_normalized_name.java \
        src/main/java/com/robsartin/setlistscout/catalog/Artist.java \
        src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java \
        src/main/java/com/robsartin/setlistscout/catalog/ArtistSeedService.java \
        src/main/java/com/robsartin/setlistscout/catalog/RelationDiscoveredListener.java \
        src/test/java/com/robsartin/setlistscout/migration/NormalizedNameBackfillTest.java
git commit -m "feat: store normalized_name on artist, populated on every write path (#176)"
```

(Add any test files you had to update for the new `insertIfAbsent` arity.)

---

### Task 2: Swap the lookup to the index

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistNameMatcher.java`
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java`
- Modify: `src/test/java/com/robsartin/setlistscout/catalog/ArtistNameMatcherTest.java`

**Interfaces:**
- `ArtistNameMatcher#findExistingMatch(String, String): Optional<ArtistNameStatusView>` — **signature unchanged**. Its two production callers (`ArtistSeedService:85`, `RelationDiscoveredListener:102`) and every test that mocks it are untouched by design.
- Produces: `ArtistRepository#findFirstByOwnerAndNormalizedName(String, String): Optional<ArtistNameStatusView>`

- [ ] **Step 1: Write the failing test**

`ArtistNameMatcherTest` currently mocks `artistRepository.findByOwner(OWNER)` and asserts matching behaviour. Rewrite it as a real `@SpringBootTest` + `@Testcontainers` integration test against a live Postgres, seeding artists through the repository and asserting the same behaviours it asserts today:

- exact match found
- case-variant match found (`"wilco"` finds `"Wilco"`)
- unicode en-dash variant found
- hyphen-spacing variant found (`"Paul Quinichette-John Coltrane Quintet"` matches the spaced form)
- curly-apostrophe variant found
- a genuinely different name is NOT matched (`"Radioheads"` must not match `"Radiohead"`)
- an empty catalog returns empty
- **owner-scoping: another owner's identically-named artist is not returned** (add this — the mocked version could not test it, because the mock returned whatever it was told)

Keep every existing case. The point of this task is that behaviour is preserved while the mechanism changes, so the test must prove exactly that.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*ArtistNameMatcherTest" --console=plain --rerun`
Expected: FAIL — the new repository method does not exist yet.

- [ ] **Step 3: Add the indexed query**

In `ArtistRepository`:

```java
    /**
     * The indexed replacement for scanning every row and re-normalizing it in Java (#176). Backed by
     * {@code idx_artist_owner_normalized_name}.
     * <p>
     * {@code findFirst}, not a unique lookup: {@code (owner, normalized_name)} is deliberately NOT
     * unique yet (see {@code V19__add_artist_normalized_name}), so a pre-existing duplicate variant
     * would make a single-result query throw. This preserves the exact semantics of the
     * {@code .findFirst()} it replaces.
     */
    Optional<ArtistNameStatusView> findFirstByOwnerAndNormalizedName(String owner, String normalizedName);
```

- [ ] **Step 4: Swap the matcher's body**

```java
    public Optional<ArtistNameStatusView> findExistingMatch(String owner, String candidateName) {
        return artistRepository.findFirstByOwnerAndNormalizedName(
                owner, ArtistNameNormalizer.normalize(candidateName));
    }
```

Update the class Javadoc: it now performs one indexed lookup, and the normalized form is stored rather than recomputed. Note that the signature is unchanged so callers and their tests are unaffected.

- [ ] **Step 5: Delete the now-dead query**

`ArtistRepository#findByOwner(String)` (the unfiltered projection) had exactly one production caller — the matcher body you just replaced. Remove it, and its Javadoc. **Verify first** with `grep -rn "findByOwner(" src/main src/test` that nothing else calls it (`findByOwnerAnd…` methods are different and must stay); update or remove any test that only existed to exercise it.

- [ ] **Step 6: Run the test and watch it pass**

Run: `./gradlew --no-daemon test --tests "*ArtistNameMatcherTest" --console=plain --rerun`
Expected: PASS.

- [ ] **Step 7: Run the full gate**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain > /tmp/gate.log 2>&1
python3 scripts/check_adrs.py
```
Expected: BUILD SUCCESSFUL. Read the actual exception before treating any single failure as real — see the known flakes above.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/ArtistNameMatcher.java \
        src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java \
        src/test/java/com/robsartin/setlistscout/catalog/ArtistNameMatcherTest.java
git commit -m "perf: match artist names by indexed lookup instead of a full-catalog scan (#176)"
```

---

## Notes for the implementer

**The one way to get this badly wrong** is to populate `normalized_name` with a `@PrePersist` alone. Production creates every artist through the two **native** `insertIfAbsent` calls, where lifecycle callbacks never fire. That path would insert nulls, violate `NOT NULL`, and fail at runtime rather than in any test that only exercises `save()`. `nativeInsertPopulatesNormalizedName` exists specifically to catch it.

**Do not add a unique constraint** — see the scope note above. There is one known colliding pair in production and merging it correctly is a follow-up issue with its own repointing requirements.

**Do not change `findExistingMatch`'s signature.** Two production callers and roughly twenty test stubs depend on it. Changing only the body is what keeps this a small, safe diff.

**Expected mechanical fallout:** every Mockito stub of `insertIfAbsent` gains an argument. Update them; do not weaken assertions to make them pass.
