# Permanent Shared Scans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a shared scan a permanent, auto-refreshing scan context that both participants can see — the artists two users both follow, at a location neither of them has saved.

**Architecture:** A shared scan gets its own synthetic `owner` key (`"shared:<uuid>"`). Every owner-scoped thing in the app — `ScanJob`, `Artist`, `SearchSettings`, `Show`, the claim-lease poller, `ScanUnitRunner` — keys on one opaque owner string and never requires it to be an email, so the entire durable-job machinery is **inherited, not rewritten**. Two explicit guards keep a synthetic owner from receiving the job set a real user would.

**Tech Stack:** Java 21 source / JDK 25 toolchain, Spring Boot 3.5, Spring Modulith, Flyway, Postgres + Testcontainers, Thymeleaf + vendored htmx, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-16-shared-scan-permanent-design.md`

## Global Constraints

- **Name matching goes through `catalog.ArtistNameNormalizer`.** Never string equality. Rob has 1,269 active artists, David has 5, they genuinely share **4**, and an exact-name join finds **1**. This is the single most likely way to build this and be silently wrong.
- **Active means `SEED` + `APPROVED`.** Same definition as `ArtistActivationService#isActive`.
- **Status changes go through `catalog.ArtistActivationService` / `ArtistSeedService`** — never a direct repository save. That is not ceremony: it is what publishes `ArtistActivated`/`ArtistDeactivated`, which is what enqueues and cancels scan jobs. The job lifecycle is inherited from it.
- **Publish events only inside a committing transaction** (ADR-0024). A publish with no committing transaction is silently dropped and the listener never fires.
- **Idempotent writes inside a listener must be `INSERT … ON CONFLICT … DO NOTHING`** — never `existsBy` + `save` + catch. A constraint race poisons the whole listener transaction and breaks Modulith's completion write.
- **A Modulith `Scenario` test is a false green.** Every event flow needs a real-path Testcontainers test driving the actual publisher.
- **`ddl-auto: validate` is on.** Every schema change needs a Flyway migration *and* a matching entity mapping.
- **Flyway versions string-sort wrong** — use `sort -V`. Latest is `V17`; this plan adds `V18`.
- **Owner-scope everything and assert it in tests.**
- **No custom JavaScript.** Use Thymeleaf `th:hx-*` — a bare `hx-post="@{/x}"` ships the literal string.
- **`hx-swap-oob="innerHTML"` on `#sr-status` is load-bearing.** The default `"true"` replaces the node and drops `role`/`aria-live`.
- **`ModularityTests` must stay green.** `shared` is OPEN; `scan → catalog` and `scan → settings` are already established.
- **TDD**: failing test → implement → green → commit.

## The gate

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain > /tmp/gate.log 2>&1
python3 scripts/check_adrs.py
```

Gradle cannot *launch* on JDK 25 — launch on 21, the toolchain forks 25. Docker Desktop must be running. The build takes ~5.5 min idle and exceeds the tool timeout: run it backgrounded, then poll the log for `BUILD SUCCESSFUL`/`BUILD FAILED`.

**Two known flakes — read the exception before believing a red gate:**
- `AssertionFailedError` on an `awaitUntil` → the async-await flake (#172), worse under load. Re-run the class in isolation to confirm.
- `MockitoException: cannot mock this class` → build config broke (#160), not the test.
- **`:test` reporting `UP-TO-DATE` in ~3s is not a pass** — nothing ran. Use `--rerun` to force a genuine isolated re-run.

## File Structure

| File | Responsibility |
|---|---|
| `shared/SharedScanOwner.java` (new) | The one definition of "is this owner key a shared scan". Used by `scan` *and* `expansion`, so it lives in the OPEN `shared` module. |
| `db/migration/V18__create_shared_scan.sql` (new) | The `shared_scan` table. |
| `scan/SharedScan.java` (new) | Entity: identity and participants only. Location lives in `search_settings`. |
| `scan/SharedScanRepository.java` (new) | Lookup by owner key and by participant. |
| `scan/ScanJobListener.java` (modify) | Guard 2 — cheap sources only for shared owners. |
| `expansion/ExpandJobListener.java` (modify) | Guard 1 — no expansion for shared owners. |
| `catalog/SharedArtistFinder.java` (new) | The normalized intersection. |
| `scan/SharedScanReconciler.java` (new) | Materializes the intersection as artists under the shared key; re-runs on participant changes. |
| `scan/SharedScanService.java` (new) | Which shared scans a user may see; their shows. |
| `scan/SharedScanController.java` (new) | `/shared` page and its actions. |
| `templates/shared.html` (new) | The page. |
| `templates/fragments/layout.html` (modify) | Nav link. |
| `static/css/app.css` (modify) | `select:focus-visible` + focus ring for the results region. |

---

### Task 1: `shared_scan` — table, entity, and the owner-key predicate

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/shared/SharedScanOwner.java`
- Create: `src/main/resources/db/migration/V18__create_shared_scan.sql`
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScan.java`
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScanRepository.java`
- Test: `src/test/java/com/robsartin/setlistscout/shared/SharedScanOwnerTest.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/SharedScanRepositoryTest.java`

**Interfaces:**
- Produces: `SharedScanOwner.PREFIX` (`"shared:"`), `SharedScanOwner#isSharedScanKey(String): boolean`, `SharedScanOwner#newKey(): String`
- Produces: `SharedScan` entity with `getId/getOwnerKey/getOwnerA/getOwnerB/getLabel/getCreatedAt`, constructor `SharedScan(String ownerKey, String ownerA, String ownerB, String label)`
- Produces: `SharedScanRepository#findByOwnerKey(String): Optional<SharedScan>`, `#findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(String, String): List<SharedScan>`

- [ ] **Step 1: Write the failing predicate test**

Create `src/test/java/com/robsartin/setlistscout/shared/SharedScanOwnerTest.java`:

```java
package com.robsartin.setlistscout.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharedScanOwnerTest {

    @Test
    @DisplayName("a generated key is recognised as a shared-scan key")
    void generatedKeyIsRecognised() {
        String key = SharedScanOwner.newKey();
        assertThat(SharedScanOwner.isSharedScanKey(key)).isTrue();
    }

    @Test
    @DisplayName("generated keys are unique")
    void generatedKeysAreUnique() {
        assertThat(SharedScanOwner.newKey()).isNotEqualTo(SharedScanOwner.newKey());
    }

    @Test
    @DisplayName("a real email is never a shared-scan key")
    void emailIsNotASharedScanKey() {
        assertThat(SharedScanOwner.isSharedScanKey("rob.sartin@gmail.com")).isFalse();
        assertThat(SharedScanOwner.isSharedScanKey("davidbuley01@gmail.com")).isFalse();
    }

    @Test
    @DisplayName("null and blank are not shared-scan keys, and do not throw")
    void nullAndBlankAreSafe() {
        assertThat(SharedScanOwner.isSharedScanKey(null)).isFalse();
        assertThat(SharedScanOwner.isSharedScanKey("")).isFalse();
        assertThat(SharedScanOwner.isSharedScanKey("   ")).isFalse();
    }

    @Test
    @DisplayName("an address that merely contains the prefix is not a shared-scan key")
    void prefixMustBeAtTheStart() {
        assertThat(SharedScanOwner.isSharedScanKey("not-shared:1234")).isFalse();
        assertThat(SharedScanOwner.isSharedScanKey("x@shared:example.com")).isFalse();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*SharedScanOwnerTest" --console=plain`
Expected: FAIL — compilation error, `SharedScanOwner` does not exist.

- [ ] **Step 3: Write `SharedScanOwner`**

Create `src/main/java/com/robsartin/setlistscout/shared/SharedScanOwner.java`:

```java
package com.robsartin.setlistscout.shared;

import java.util.UUID;

/**
 * The one definition of "this owner string is a shared scan, not a person" (#163).
 * <p>
 * A shared scan is a scan context that happens not to be a user: it owns artists, settings, jobs
 * and shows exactly as a person does, because every owner-scoped query in this app keys on an
 * opaque string and never requires it to be an email. This class is what keeps the two kinds
 * distinguishable.
 * <p>
 * Lives in {@code shared} (an OPEN module) because both {@code scan} and {@code expansion} need
 * the predicate to apply their guards, and neither should depend on the other.
 * <p>
 * The {@code shared:} prefix cannot collide with a real address: an email always contains
 * {@code @} and never begins with this prefix. Login is also unreachable for these keys --
 * {@code SecurityConfig} authorises against the configured {@code allowedEmails}, which a
 * generated key can never match.
 */
public final class SharedScanOwner {

    /** Prefix marking an owner string as a shared scan rather than a person. */
    public static final String PREFIX = "shared:";

    private SharedScanOwner() {
    }

    /**
     * @return true if {@code owner} identifies a shared scan. Null-safe: a null owner (no
     * authenticated principal) is not a shared scan.
     */
    public static boolean isSharedScanKey(String owner) {
        return owner != null && owner.startsWith(PREFIX);
    }

    /**
     * A fresh, opaque owner key. Random rather than derived from the participants' addresses so
     * the key stays stable if a participant is ever swapped, and so it carries no personal data.
     * Ordering is irrelevant here, so a plain random UUID is used rather than the UUIDv7 generator
     * the app uses for correlation ids.
     */
    public static String newKey() {
        return PREFIX + UUID.randomUUID();
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew --no-daemon test --tests "*SharedScanOwnerTest" --console=plain`
Expected: PASS, 5 tests.

- [ ] **Step 5: Write the migration**

Create `src/main/resources/db/migration/V18__create_shared_scan.sql`:

```sql
-- #163: a shared scan is a scan context shared by two users. It holds identity only --
-- WHO is sharing. Location/radius/window deliberately live in search_settings under this
-- row's owner_key, so SettingsService, the settings-edit flow, and the existing
-- SettingsChanged -> re-due-every-scan-job behaviour all apply to shared scans unchanged.
CREATE TABLE shared_scan (
    id         bigserial PRIMARY KEY,
    owner_key  varchar(255) NOT NULL UNIQUE,
    owner_a    varchar(255) NOT NULL,
    owner_b    varchar(255) NOT NULL,
    label      varchar(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL
);

-- Lookup is "which shared scans is this signed-in user part of", on every page load.
CREATE INDEX idx_shared_scan_owner_a ON shared_scan (owner_a);
CREATE INDEX idx_shared_scan_owner_b ON shared_scan (owner_b);
```

- [ ] **Step 6: Write the entity and repository**

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScan.java`:

```java
package com.robsartin.setlistscout.scan;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Two users sharing a scan (#163): identity and participants only.
 * <p>
 * Location, radius and window are NOT here on purpose -- they live in {@code search_settings}
 * under {@link #getOwnerKey()}. That is what lets {@code SettingsService}, the settings form, and
 * the existing {@code SettingsChanged -> ScanJobListener.onSettingsChanged} re-due behaviour apply
 * to a shared scan with no new code. Duplicating location columns here would forfeit all of it.
 * <p>
 * Two participant columns rather than a join table: the app needs exactly one pairing, and
 * {@code SharedArtistFinder#findSharedArtistNames} is a two-owner function. N-way membership would
 * change the finder too, so it is not modelled speculatively.
 */
@Entity
@Table(name = "shared_scan")
public class SharedScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The synthetic owner string this scan's artists, settings, jobs and shows are keyed by. */
    @Column(name = "owner_key", nullable = false, unique = true)
    private String ownerKey;

    @Column(name = "owner_a", nullable = false)
    private String ownerA;

    @Column(name = "owner_b", nullable = false)
    private String ownerB;

    /** Display name, e.g. "Rob & David". */
    @Column(nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SharedScan() {
        // JPA
    }

    public SharedScan(String ownerKey, String ownerA, String ownerB, String label) {
        this.ownerKey = ownerKey;
        this.ownerA = ownerA;
        this.ownerB = ownerB;
        this.label = label;
    }

    public Long getId() { return id; }
    public String getOwnerKey() { return ownerKey; }
    public String getOwnerA() { return ownerA; }
    public String getOwnerB() { return ownerB; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }

    /** True if {@code email} is one of the two participants. Case-insensitive: OIDC casing must not decide access. */
    public boolean includes(String email) {
        return email != null && (email.equalsIgnoreCase(ownerA) || email.equalsIgnoreCase(ownerB));
    }

    /** The other participant's address, from {@code email}'s point of view; null if {@code email} isn't a participant. */
    public String otherParticipant(String email) {
        if (email == null) return null;
        if (email.equalsIgnoreCase(ownerA)) return ownerB;
        if (email.equalsIgnoreCase(ownerB)) return ownerA;
        return null;
    }
}
```

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScanRepository.java`:

```java
package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SharedScanRepository extends JpaRepository<SharedScan, Long> {

    Optional<SharedScan> findByOwnerKey(String ownerKey);

    /**
     * Every shared scan the given address participates in. Both parameters take the SAME address --
     * the derived-query grammar has no single-parameter "a or b" form. Case-insensitive on both
     * sides, because the address arrives from the OIDC token and its casing must not decide access.
     */
    List<SharedScan> findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(String ownerA, String ownerB);
}
```

- [ ] **Step 7: Write the repository test**

Create `src/test/java/com/robsartin/setlistscout/scan/SharedScanRepositoryTest.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SharedScanRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";
    private static final String DAVID = "david@example.com";
    private static final String STRANGER = "stranger@example.com";

    @Autowired
    private SharedScanRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private SharedScan save(String a, String b) {
        return repository.save(new SharedScan(SharedScanOwner.newKey(), a, b, "Test pairing"));
    }

    @Test
    @DisplayName("both participants find the shared scan; a third party does not")
    void findsByEitherParticipant() {
        save(ROB, DAVID);

        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(ROB, ROB)).hasSize(1);
        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(DAVID, DAVID)).hasSize(1);
        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(STRANGER, STRANGER)).isEmpty();
    }

    @Test
    @DisplayName("participant lookup ignores case -- OIDC casing must not decide access")
    void participantLookupIgnoresCase() {
        save(ROB, DAVID);

        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase("ROB@EXAMPLE.COM", "ROB@EXAMPLE.COM"))
                .hasSize(1);
    }

    @Test
    @DisplayName("owner keys are unique and round-trip")
    void ownerKeyRoundTrips() {
        SharedScan saved = save(ROB, DAVID);

        assertThat(repository.findByOwnerKey(saved.getOwnerKey())).isPresent();
        assertThat(repository.findByOwnerKey("shared:nope")).isEmpty();
    }

    @Test
    @DisplayName("includes() and otherParticipant() are case-insensitive and reciprocal")
    void participantHelpers() {
        SharedScan scan = save(ROB, DAVID);

        assertThat(scan.includes("ROB@EXAMPLE.COM")).isTrue();
        assertThat(scan.includes(STRANGER)).isFalse();
        assertThat(scan.includes(null)).isFalse();
        assertThat(scan.otherParticipant(ROB)).isEqualTo(DAVID);
        assertThat(scan.otherParticipant(DAVID)).isEqualTo(ROB);
        assertThat(scan.otherParticipant(STRANGER)).isNull();
    }
}
```

- [ ] **Step 8: Run the tests**

Run: `./gradlew --no-daemon test --tests "*SharedScanOwnerTest" --tests "*SharedScanRepositoryTest" --console=plain`
Expected: PASS, 9 tests total.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/shared/SharedScanOwner.java \
        src/test/java/com/robsartin/setlistscout/shared/SharedScanOwnerTest.java \
        src/main/resources/db/migration/V18__create_shared_scan.sql \
        src/main/java/com/robsartin/setlistscout/scan/SharedScan.java \
        src/main/java/com/robsartin/setlistscout/scan/SharedScanRepository.java \
        src/test/java/com/robsartin/setlistscout/scan/SharedScanRepositoryTest.java
git commit -m "feat: shared_scan table, entity, and the synthetic owner-key predicate (#163)"
```

---

### Task 2: The two guards — at **four** sites, not two

**Do this before anything creates a shared artist.** These guards are what stop a shared scan from receiving a real user's job set. Implementing them after the reconciler would leave a window in which the first shared scan enqueues expansion jobs — filling a Candidates queue nobody can see and billing LLM calls per artist, entirely invisibly.

**The non-obvious part: the listeners are not the only enqueue path.** `ScanJobBackfill` and `ExpandJobBackfill` are `ApplicationRunner`s that run on **every application start**. Each calls `artistRepository.findByStatusIn(...)` — which has **no owner filter** — and enqueues jobs directly via `insertIfAbsent`, never touching the listeners. Guarding only the listeners means every restart re-defeats both guards: expand jobs for shared artists, and band-site scan jobs for them too. Both guards must therefore exist at four sites, and the source policy gets exactly one definition so the two scan sites cannot drift.

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScanSources.java`
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ScanJobListener.java`
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ScanJobBackfill.java`
- Modify: `src/main/java/com/robsartin/setlistscout/expansion/ExpandJobListener.java`
- Modify: `src/main/java/com/robsartin/setlistscout/expansion/ExpandJobBackfill.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/SharedScanGuardsTest.java`

**Interfaces:**
- Consumes: `SharedScanOwner#isSharedScanKey(String)`, `ShowSource#id()`
- Produces: `SharedScanSources#forOwner(String owner, List<ShowSource> all): List<ShowSource>` (package-private to `scan`)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/scan/SharedScanGuardsTest.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJobBackfill;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #163. A shared-scan owner must receive a REDUCED job set: scan jobs for the cheap sources only,
 * and no expansion jobs at all. Both failures are invisible from every page -- an over-expanding
 * shared scan would only ever surface as an LLM bill -- so they are pinned here.
 */
@SpringBootTest
@Testcontainers
class SharedScanGuardsTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String REAL_OWNER = "rob@example.com";

    @Autowired private ArtistRepository artistRepository;
    @Autowired private ScanJobRepository scanJobRepository;
    @Autowired private ExpandJobRepository expandJobRepository;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ScanJobBackfill scanJobBackfill;
    @Autowired private ExpandJobBackfill expandJobBackfill;

    @BeforeEach
    void clean() {
        scanJobRepository.deleteAll();
        expandJobRepository.deleteAll();
        artistRepository.deleteAll();
    }

    private Artist seedArtist(String owner, String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist);
    }

    /** ADR-0024: @ApplicationModuleListener is AFTER_COMMIT, so the publish must be inside a committing transaction. */
    private void publishActivated(String owner, Artist artist) {
        transactionTemplate.executeWithoutResult(tx ->
                publisher.publishEvent(new ArtistActivated(owner, artist.getId(), artist.getName(),
                        ArtistStatus.SEED.name())));
    }

    @Test
    @DisplayName("a shared-scan owner gets NO expansion jobs")
    void sharedOwnerGetsNoExpandJobs() {
        String sharedKey = SharedScanOwner.newKey();
        Artist artist = seedArtist(sharedKey, "Tom Petty");

        publishActivated(sharedKey, artist);

        List<?> jobs = awaitUntil(() -> expandJobRepository.findAll(), j -> !j.isEmpty());
        assertThat(jobs)
                .as("expansion for a shared scan would fill a Candidates queue nobody can see "
                        + "and bill LLM calls per artist")
                .isEmpty();
    }

    @Test
    @DisplayName("a real owner still gets expansion jobs -- the guard must not disable expansion generally")
    void realOwnerStillGetsExpandJobs() {
        Artist artist = seedArtist(REAL_OWNER, "Tom Petty");

        publishActivated(REAL_OWNER, artist);

        assertThat(awaitUntil(() -> expandJobRepository.findAll(), j -> !j.isEmpty())).isNotEmpty();
    }

    @Test
    @DisplayName("a shared-scan owner gets scan jobs for ticketmaster and bandsintown only")
    void sharedOwnerGetsCheapSourcesOnly() {
        String sharedKey = SharedScanOwner.newKey();
        Artist artist = seedArtist(sharedKey, "Tom Petty");

        publishActivated(sharedKey, artist);

        List<ScanJob> jobs = awaitUntil(() -> scanJobRepository.findAll(), j -> !j.isEmpty());
        assertThat(jobs).isNotEmpty();
        assertThat(jobs).extracting(ScanJob::getSource)
                .as("band-site falls back to TourPageLlmService, which bills per artist")
                .containsOnly("ticketmaster", "bandsintown");
    }

    @Test
    @DisplayName("a real owner still gets a scan job for every source, band-site included")
    void realOwnerGetsEverySource() {
        Artist artist = seedArtist(REAL_OWNER, "Tom Petty");

        publishActivated(REAL_OWNER, artist);

        List<ScanJob> jobs = awaitUntil(() -> scanJobRepository.findAll(), j -> !j.isEmpty());
        assertThat(jobs).extracting(ScanJob::getSource).contains("band-site");
    }

    // ---- The startup backfills are a SECOND enqueue path that never touches the listeners.
    // findByStatusIn has no owner filter, so both backfills see shared-scan artists. Without the
    // same guards there, every application restart would undo them. These two tests are the only
    // thing that catches that.

    @Test
    @DisplayName("the expand backfill skips shared-scan artists")
    void expandBackfillSkipsSharedOwners() {
        String sharedKey = SharedScanOwner.newKey();
        seedArtist(sharedKey, "Tom Petty");
        seedArtist(REAL_OWNER, "Bruce Springsteen");

        expandJobBackfill.run(new DefaultApplicationArguments());

        assertThat(expandJobRepository.findAll())
                .as("a restart must not re-enqueue the expansion the listener guard prevents")
                .allSatisfy(job -> assertThat(job.getOwner()).isEqualTo(REAL_OWNER));
        assertThat(expandJobRepository.findAll()).isNotEmpty();
    }

    @Test
    @DisplayName("the scan backfill enqueues only cheap sources for shared-scan artists")
    void scanBackfillAppliesSourcePolicy() {
        String sharedKey = SharedScanOwner.newKey();
        seedArtist(sharedKey, "Tom Petty");
        seedArtist(REAL_OWNER, "Bruce Springsteen");

        scanJobBackfill.run(new DefaultApplicationArguments());

        assertThat(scanJobRepository.findAll())
                .filteredOn(job -> job.getOwner().equals(sharedKey))
                .isNotEmpty()
                .extracting(ScanJob::getSource)
                .containsOnly("ticketmaster", "bandsintown");
        assertThat(scanJobRepository.findAll())
                .filteredOn(job -> job.getOwner().equals(REAL_OWNER))
                .extracting(ScanJob::getSource)
                .contains("band-site");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*SharedScanGuardsTest" --console=plain`
Expected: FAIL — **four** of the six tests fail, which is the correct red state: `sharedOwnerGetsNoExpandJobs` and `scanBackfillAppliesSourcePolicy`'s shared-owner assertion, plus `sharedOwnerGetsCheapSourcesOnly` and `expandBackfillSkipsSharedOwners`. With zero guards implemented, every test that asserts a *reduced* job set fails, at both the listener and the backfill site. The two that pass are the real-owner controls — they must stay green throughout, since the guards must not disable expansion or band-site generally.

- [ ] **Step 3: Write the one source policy**

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScanSources.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.shared.SharedScanOwner;

import java.util.List;
import java.util.Set;

/**
 * Which show sources an owner's scan jobs are enqueued for (#163).
 * <p>
 * One definition, used by BOTH enqueue paths -- {@code ScanJobListener} (on activation) and
 * {@code ScanJobBackfill} (on every application start). The backfill reaches
 * {@code scanJobRepository.insertIfAbsent} without going through the listener, so a policy that
 * lived only in the listener would be re-defeated on every restart.
 */
final class SharedScanSources {

    /**
     * The sources a shared scan may use. An allow-list, not a deny-list, so a source added later is
     * excluded by default rather than silently joining shared scans. band-site is the one
     * deliberately left out: it scrapes and falls back to {@code TourPageLlmService}, which is
     * billed per artist, and a shared scan's artists are already covered by both participants'
     * own scans.
     */
    private static final Set<String> SHARED_SCAN_SOURCE_IDS = Set.of("ticketmaster", "bandsintown");

    private SharedScanSources() {
    }

    /** Every source for a real owner; only the cheap ones for a shared scan. */
    static List<ShowSource> forOwner(String owner, List<ShowSource> all) {
        if (!SharedScanOwner.isSharedScanKey(owner)) {
            return all;
        }
        return all.stream().filter(source -> SHARED_SCAN_SOURCE_IDS.contains(source.id())).toList();
    }
}
```

- [ ] **Step 4: Guard 2 at both scan enqueue sites**

In `src/main/java/com/robsartin/setlistscout/scan/ScanJobListener.java`, change the loop header in `onArtistActivated` from `for (ShowSource source : showSources) {` to:

```java
        for (ShowSource source : SharedScanSources.forOwner(e.owner(), showSources)) {
```

In `src/main/java/com/robsartin/setlistscout/scan/ScanJobBackfill.java`, change the inner loop header in `run` from `for (ShowSource source : showSources) {` to:

```java
            // #163: a shared scan is enqueued for the cheap sources only. This runs on every
            // application start and reaches insertIfAbsent directly, so it needs the same policy
            // ScanJobListener applies -- guarding only the listener would be undone on each restart.
            for (ShowSource source : SharedScanSources.forOwner(artist.getOwner(), showSources)) {
```

Both files are in the same package as `SharedScanSources`, so no import is needed.

- [ ] **Step 5: Guard 1 at both expansion enqueue sites**

In `src/main/java/com/robsartin/setlistscout/expansion/ExpandJobListener.java`, add the import `import com.robsartin.setlistscout.shared.SharedScanOwner;` and insert this as the first statement of `onArtistActivated`, before `ArtistStatus artistStatus = parseStatus(e.status());`:

```java
        // #163 guard: a shared scan is a scan context, not a person. Expanding it would discover
        // member/similar/tribute candidates for an owner who has no Candidates page, no reviewer,
        // and no way to see them -- while billing an LLM call per artist. Invisible if wrong,
        // which is why SharedScanGuardsTest pins it.
        if (SharedScanOwner.isSharedScanKey(e.owner())) {
            return;
        }
```

In `src/main/java/com/robsartin/setlistscout/expansion/ExpandJobBackfill.java`, add the same import and insert this as the first statement of the `for (Artist artist : active)` loop body, before the `for (RelationSource source : relationSources)` loop:

```java
            // #163: same guard as ExpandJobListener. findByStatusIn has NO owner filter, so this
            // startup backfill sees shared-scan artists too; without this it would re-enqueue the
            // expansion jobs the listener guard exists to prevent, on every restart.
            if (SharedScanOwner.isSharedScanKey(artist.getOwner())) {
                continue;
            }
```

Leave both `onArtistDeactivated` paths alone: deleting jobs for an owner that has none is already a harmless no-op, and a guard there would be dead code.

- [ ] **Step 6: Run the test and watch it pass**

Run: `./gradlew --no-daemon test --tests "*SharedScanGuardsTest" --console=plain`
Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/SharedScanSources.java \
        src/main/java/com/robsartin/setlistscout/scan/ScanJobListener.java \
        src/main/java/com/robsartin/setlistscout/scan/ScanJobBackfill.java \
        src/main/java/com/robsartin/setlistscout/expansion/ExpandJobListener.java \
        src/main/java/com/robsartin/setlistscout/expansion/ExpandJobBackfill.java \
        src/test/java/com/robsartin/setlistscout/scan/SharedScanGuardsTest.java
git commit -m "feat: guard shared-scan owners out of expansion and expensive sources (#163)"
```

---

### Task 3: `SharedArtistFinder` — the normalized intersection

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/catalog/SharedArtistFinder.java`
- Test: `src/test/java/com/robsartin/setlistscout/catalog/SharedArtistFinderTest.java`

**Interfaces:**
- Consumes: `ArtistRepository#findByOwnerAndStatusIn(String, List<ArtistStatus>)`, `ArtistNameNormalizer#normalize(String)`
- Produces: `SharedArtistFinder#findSharedArtistNames(String ownerA, String ownerB): List<String>`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/catalog/SharedArtistFinderTest.java`:

```java
package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #163. This class exists because an exact-name join looks correct and under-reports badly against
 * real data -- see {@link #normalizationIsWhatMakesTheIntersectionWork}.
 */
@SpringBootTest
@Testcontainers
class SharedArtistFinderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";
    private static final String DAVID = "david@example.com";
    private static final String STRANGER = "stranger@example.com";
    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    @Autowired private SharedArtistFinder finder;
    @Autowired private ArtistRepository artistRepository;

    @BeforeEach
    void clean() {
        artistRepository.deleteAll();
    }

    private void seed(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    @Test
    @DisplayName("matches two spellings that differ only by case -- the real production pair")
    void matchesCaseVariantNames() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).containsExactly("Tom Petty");
    }

    @Test
    @DisplayName("normalization is what makes the intersection work: exact match finds 1 of 4")
    void normalizationIsWhatMakesTheIntersectionWork() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);
        seed(ROB, "James Taylor", ArtistStatus.SEED);
        seed(DAVID, "James taylor", ArtistStatus.SEED);
        seed(ROB, "Bruce Springsteen", ArtistStatus.SEED);
        seed(DAVID, "Bruce springsteen", ArtistStatus.SEED);
        seed(ROB, "Brandi Carlile", ArtistStatus.SEED);
        seed(DAVID, "Brandi Carlile", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID))
                .containsExactly("Brandi Carlile", "Bruce Springsteen", "James Taylor", "Tom Petty");

        // The regression guard. An exact-name join over this same data finds only the single
        // identically-spelled pair. If findSharedArtistNames is ever "simplified" to string
        // equality, the assertion above drops to 1 -- this documents why that is wrong.
        List<String> davidNames = artistRepository.findByOwnerAndStatusIn(DAVID, ACTIVE)
                .stream().map(Artist::getName).toList();
        long exactMatches = artistRepository.findByOwnerAndStatusIn(ROB, ACTIVE)
                .stream().filter(a -> davidNames.contains(a.getName())).count();
        assertThat(exactMatches).isEqualTo(1);
    }

    @Test
    @DisplayName("only SEED and APPROVED are active on either side")
    void onlyActiveStatusesIntersect() {
        seed(ROB, "Approved Both", ArtistStatus.APPROVED);
        seed(DAVID, "Approved Both", ArtistStatus.SEED);
        seed(ROB, "Rejected By David", ArtistStatus.SEED);
        seed(DAVID, "Rejected By David", ArtistStatus.REJECTED);
        seed(ROB, "Removed By Rob", ArtistStatus.REMOVED);
        seed(DAVID, "Removed By Rob", ArtistStatus.SEED);
        seed(ROB, "Pending For David", ArtistStatus.SEED);
        seed(DAVID, "Pending For David", ArtistStatus.PENDING_REVIEW);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).containsExactly("Approved Both");
    }

    @Test
    @DisplayName("a third owner's artists never leak into the intersection")
    void ownerIsolation() {
        seed(ROB, "Shared", ArtistStatus.SEED);
        seed(DAVID, "Shared", ArtistStatus.SEED);
        seed(STRANGER, "Stranger Only", ArtistStatus.SEED);
        seed(ROB, "Stranger Only", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).containsExactly("Shared");
    }

    @Test
    @DisplayName("no overlap returns empty, not an error")
    void noOverlapIsEmpty() {
        seed(ROB, "Only Rob", ArtistStatus.SEED);
        seed(DAVID, "Only David", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).isEmpty();
    }

    @Test
    @DisplayName("results come back in ownerA's spelling, sorted case-insensitively")
    void returnsOwnerASpellingSorted() {
        seed(ROB, "zz top", ArtistStatus.SEED);
        seed(DAVID, "ZZ Top", ArtistStatus.SEED);
        seed(ROB, "ABBA", ArtistStatus.SEED);
        seed(DAVID, "abba", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).containsExactly("ABBA", "zz top");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*SharedArtistFinderTest" --console=plain`
Expected: FAIL — compilation error, `SharedArtistFinder` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/robsartin/setlistscout/catalog/SharedArtistFinder.java`:

```java
package com.robsartin.setlistscout.catalog;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Which artists do these two owners both actively follow?" -- the input to a shared scan (#163).
 * <p>
 * The match runs through {@link ArtistNameNormalizer}, never string equality, and that is the whole
 * point of this class. Measured against production before it was written: Rob has 1,269 active
 * artists, David has 5, and they genuinely share 4 -- but an exact-name join finds 1, because
 * David's entries are lowercased on the second word ("Tom petty" vs "Tom Petty"). An exact join
 * returns a plausible-looking non-empty answer, which is exactly why it would have shipped.
 * <p>
 * The asymmetry is structural, not incidental: the intersection is bounded by the SMALLER list.
 */
@Component
public class SharedArtistFinder {

    /**
     * The same definition of "active" as {@code ArtistActivationService#isActive}. REJECTED,
     * REMOVED and PENDING_REVIEW are excluded on BOTH sides -- an artist one participant has
     * rejected is not one they want to be sold tickets to.
     */
    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    private final ArtistRepository artistRepository;

    public SharedArtistFinder(ArtistRepository artistRepository) {
        this.artistRepository = artistRepository;
    }

    /**
     * @return one name per artist active for BOTH owners, in {@code ownerA}'s spelling, sorted
     * case-insensitively, with no duplicates. Which side's spelling wins does not affect search
     * results -- Ticketmaster and Bandsintown keyword search are both case-insensitive.
     */
    public List<String> findSharedArtistNames(String ownerA, String ownerB) {
        Set<String> keysForB = new HashSet<>();
        for (Artist artist : artistRepository.findByOwnerAndStatusIn(ownerB, ACTIVE)) {
            keysForB.add(ArtistNameNormalizer.normalize(artist.getName()));
        }

        // putIfAbsent, not put: if ownerA holds two case-variant rows for one artist (the condition
        // V13 merged and #118 guards), they collapse to one result rather than being searched twice.
        Map<String, String> displayNameByKey = new LinkedHashMap<>();
        for (Artist artist : artistRepository.findByOwnerAndStatusIn(ownerA, ACTIVE)) {
            String key = ArtistNameNormalizer.normalize(artist.getName());
            if (keysForB.contains(key)) {
                displayNameByKey.putIfAbsent(key, artist.getName());
            }
        }

        return displayNameByKey.values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew --no-daemon test --tests "*SharedArtistFinderTest" --console=plain`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/SharedArtistFinder.java \
        src/test/java/com/robsartin/setlistscout/catalog/SharedArtistFinderTest.java
git commit -m "feat: normalized intersection of two owners' active artists (#163)"
```

---

### Task 4: `SharedScanReconciler` — materialize the intersection

**The recursion hazard, read this first.** This class both *publishes* activation events (by creating artists under the shared key) and *listens* to them. Without an explicit guard it reconciles → creates an artist → `ArtistActivated(sharedKey)` fires → reconciles again, forever. The `isSharedScanKey` early return in `onArtistActivated` is what prevents that, and `reconcilingASharedOwnerDoesNotRecurse` is what pins it.

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScanReconciler.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/SharedScanReconcilerTest.java`

**Interfaces:**
- Consumes: `SharedArtistFinder#findSharedArtistNames`, `ArtistSeedService#addSeedIfNew(String owner, String rawName): boolean`, `ArtistActivationService#changeStatus(Long id, String owner, ArtistStatus)`, `ArtistRepository#findByOwnerAndStatusIn`, `SharedScanRepository#findByOwnerAIgnoreCaseOrOwnerBIgnoreCase`, `ArtistNameNormalizer#normalize`
- Produces: `SharedScanReconciler#reconcile(SharedScan): int` (returns the number of artists now active under the shared key)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/scan/SharedScanReconcilerTest.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SharedScanReconcilerTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";
    private static final String DAVID = "david@example.com";
    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    @Autowired private SharedScanReconciler reconciler;
    @Autowired private SharedScanRepository sharedScanRepository;
    @Autowired private ArtistRepository artistRepository;
    @Autowired private ScanJobRepository scanJobRepository;

    private SharedScan scan;

    @BeforeEach
    void setUp() {
        scanJobRepository.deleteAll();
        artistRepository.deleteAll();
        sharedScanRepository.deleteAll();
        scan = sharedScanRepository.save(
                new SharedScan(SharedScanOwner.newKey(), ROB, DAVID, "Rob & David"));
    }

    private void seed(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    private List<String> activeSharedNames() {
        return artistRepository.findByOwnerAndStatusIn(scan.getOwnerKey(), ACTIVE)
                .stream().map(Artist::getName).sorted().toList();
    }

    @Test
    @DisplayName("materializes the normalized intersection as artists under the shared key")
    void materializesIntersection() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);
        seed(ROB, "Only Rob", ArtistStatus.SEED);

        reconciler.reconcile(scan);

        assertThat(activeSharedNames()).containsExactly("Tom Petty");
    }

    @Test
    @DisplayName("is idempotent -- reconciling twice does not duplicate artists")
    void isIdempotent() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);

        reconciler.reconcile(scan);
        reconciler.reconcile(scan);

        assertThat(activeSharedNames()).containsExactly("Tom Petty");
    }

    @Test
    @DisplayName("removes an artist that has left the intersection")
    void removesDepartedArtist() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);
        reconciler.reconcile(scan);
        assertThat(activeSharedNames()).containsExactly("Tom Petty");

        // David rejects the artist -- it is no longer shared.
        Artist davids = artistRepository.findByOwnerAndName(DAVID, "Tom petty").orElseThrow();
        davids.setStatus(ArtistStatus.REJECTED);
        artistRepository.save(davids);

        reconciler.reconcile(scan);

        assertThat(activeSharedNames()).isEmpty();
    }

    @Test
    @DisplayName("creating shared artists enqueues their scan jobs via the normal event path")
    void enqueuesScanJobs() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);

        reconciler.reconcile(scan);

        List<ScanJob> jobs = awaitUntil(() -> scanJobRepository.findAll(), j -> !j.isEmpty());
        assertThat(jobs).isNotEmpty();
        assertThat(jobs).allSatisfy(j -> assertThat(j.getOwner()).isEqualTo(scan.getOwnerKey()));
        // Task 2's guard applies here too -- shared scans never get band-site.
        assertThat(jobs).extracting(ScanJob::getSource).containsOnly("ticketmaster", "bandsintown");
    }

    @Test
    @DisplayName("an activation for a shared owner does NOT trigger another reconcile -- no infinite loop")
    void reconcilingASharedOwnerDoesNotRecurse() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);

        reconciler.reconcile(scan);

        // Creating the shared artist publishes ArtistActivated(sharedKey). If the listener did not
        // ignore shared owners it would reconcile again, publish again, and never terminate.
        // Reaching a settled single artist at all is the assertion.
        List<String> settled = awaitUntil(this::activeSharedNames, names -> names.size() == 1);
        assertThat(settled).containsExactly("Tom Petty");
    }

    @Test
    @DisplayName("a participant activating an artist reconciles the shared scan automatically")
    void participantChangeTriggersReconcile() {
        seed(DAVID, "Tom petty", ArtistStatus.SEED);
        reconciler.reconcile(scan);
        assertThat(activeSharedNames()).isEmpty();

        // Rob now adds the same artist -- it becomes shared, and the listener should notice.
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        Artist robs = artistRepository.findByOwnerAndName(ROB, "Tom Petty").orElseThrow();
        reconciler.onParticipantArtistChanged(ROB, robs.getId());

        assertThat(awaitUntil(this::activeSharedNames, n -> !n.isEmpty())).containsExactly("Tom Petty");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*SharedScanReconcilerTest" --console=plain`
Expected: FAIL — compilation error, `SharedScanReconciler` does not exist.

- [ ] **Step 3: Write the reconciler**

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScanReconciler.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSeedService;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.catalog.SharedArtistFinder;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the artists under a shared scan's owner key equal to the normalized intersection of its
 * two participants' active artists (#163).
 * <p>
 * Both transitions go through {@code catalog}'s services rather than the repository, per CLAUDE.md.
 * That is not ceremony here: it is what publishes {@code ArtistActivated}/{@code ArtistDeactivated},
 * which is what makes {@code ScanJobListener} enqueue and cancel this shared scan's scan jobs. The
 * entire job lifecycle is inherited from that, not written here.
 */
@Service
public class SharedScanReconciler {

    private static final Logger log = LoggerFactory.getLogger(SharedScanReconciler.class);

    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    private final SharedScanRepository sharedScanRepository;
    private final SharedArtistFinder sharedArtistFinder;
    private final ArtistRepository artistRepository;
    private final ArtistSeedService seedService;
    private final ArtistActivationService activationService;

    public SharedScanReconciler(SharedScanRepository sharedScanRepository,
                                 SharedArtistFinder sharedArtistFinder,
                                 ArtistRepository artistRepository,
                                 ArtistSeedService seedService,
                                 ArtistActivationService activationService) {
        this.sharedScanRepository = sharedScanRepository;
        this.sharedArtistFinder = sharedArtistFinder;
        this.artistRepository = artistRepository;
        this.seedService = seedService;
        this.activationService = activationService;
    }

    /**
     * Brings {@code scan}'s artists into line with its participants' current intersection.
     *
     * @return how many artists are active under the shared key afterwards
     */
    public int reconcile(SharedScan scan) {
        String ownerKey = scan.getOwnerKey();
        List<String> shared = sharedArtistFinder.findSharedArtistNames(scan.getOwnerA(), scan.getOwnerB());

        Set<String> wanted = new HashSet<>();
        for (String name : shared) {
            wanted.add(ArtistNameNormalizer.normalize(name));
            // addSeedIfNew is idempotent and publishes ArtistActivated only when it actually
            // creates the row, so re-reconciling does not re-enqueue jobs.
            seedService.addSeedIfNew(ownerKey, name);
        }

        for (Artist existing : artistRepository.findByOwnerAndStatusIn(ownerKey, ACTIVE)) {
            if (!wanted.contains(ArtistNameNormalizer.normalize(existing.getName()))) {
                // REMOVED, not REJECTED: this artist was never a reviewed candidate, and REJECTED
                // would put it in a review queue that a shared scan has no reviewer for.
                activationService.changeStatus(existing.getId(), ownerKey, ArtistStatus.REMOVED);
            }
        }

        log.atInfo().addKeyValue("sharedScan", scan.getLabel()).addKeyValue("ownerKey", ownerKey)
                .addKeyValue("sharedArtists", shared.size()).log("shared scan reconciled");
        return shared.size();
    }

    @ApplicationModuleListener
    void onArtistActivated(ArtistActivated e) {
        onParticipantArtistChanged(e.owner(), e.artistId());
    }

    @ApplicationModuleListener
    void onArtistDeactivated(ArtistDeactivated e) {
        onParticipantArtistChanged(e.owner(), e.artistId());
    }

    /**
     * Re-reconciles every shared scan the given owner participates in.
     * <p>
     * <b>The early return is load-bearing, not defensive.</b> {@link #reconcile} creates artists
     * under a shared key, which publishes {@code ArtistActivated} for that key, which arrives back
     * here. Without this guard the reconcile would trigger another reconcile and never terminate.
     * A shared scan is also never a participant in another shared scan, so there is nothing to do
     * for one in any case.
     * <p>
     * Package-private rather than private so the reconcile-on-participant-change path can be driven
     * directly in tests without publishing an event and waiting on async delivery.
     */
    void onParticipantArtistChanged(String owner, Long artistId) {
        if (SharedScanOwner.isSharedScanKey(owner)) {
            return;
        }
        for (SharedScan scan : sharedScanRepository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(owner, owner)) {
            reconcile(scan);
        }
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew --no-daemon test --tests "*SharedScanReconcilerTest" --console=plain`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/SharedScanReconciler.java \
        src/test/java/com/robsartin/setlistscout/scan/SharedScanReconcilerTest.java
git commit -m "feat: reconcile shared-scan artists from the participant intersection (#163)"
```

---

### Task 5: Access, service, and the leakage audit

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScanService.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/SharedScanServiceTest.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/SharedOwnerIsolationTest.java`

**Interfaces:**
- Consumes: `SharedScanRepository`, `SettingsService#getOrCreateSettings(String)`, `SettingsService#updateSettings(String, String, int, int)`, `ShowRepository#findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc`, `SharedScanReconciler#reconcile`
- Produces: `SharedScanService#visibleTo(String email): List<SharedScan>`, `#requireVisible(String email, Long id): SharedScan` (throws 403/404 as documented), `#showsFor(SharedScan): List<Show>`, `#create(String label, String ownerA, String ownerB): SharedScan`

- [ ] **Step 1: Write the failing access test**

Create `src/test/java/com/robsartin/setlistscout/scan/SharedScanServiceTest.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class SharedScanServiceTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";
    private static final String DAVID = "david@example.com";
    private static final String STRANGER = "stranger@example.com";

    @Autowired private SharedScanService service;
    @Autowired private SharedScanRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("both participants see it; a third party sees nothing")
    void visibilityIsParticipantBased() {
        service.create("Rob & David", ROB, DAVID);

        assertThat(service.visibleTo(ROB)).hasSize(1);
        assertThat(service.visibleTo(DAVID)).hasSize(1);
        assertThat(service.visibleTo(STRANGER)).isEmpty();
    }

    @Test
    @DisplayName("visibility ignores address case")
    void visibilityIgnoresCase() {
        service.create("Rob & David", ROB, DAVID);

        assertThat(service.visibleTo("DAVID@EXAMPLE.COM")).hasSize(1);
    }

    @Test
    @DisplayName("a non-participant requesting one by id gets 404, not the scan")
    void nonParticipantCannotFetchById() {
        SharedScan scan = service.create("Rob & David", ROB, DAVID);

        assertThat(service.requireVisible(ROB, scan.getId())).isNotNull();
        assertThatThrownBy(() -> service.requireVisible(STRANGER, scan.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("an unauthenticated caller sees nothing and cannot fetch")
    void nullEmailSeesNothing() {
        SharedScan scan = service.create("Rob & David", ROB, DAVID);

        assertThat(service.visibleTo(null)).isEmpty();
        assertThatThrownBy(() -> service.requireVisible(null, scan.getId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("creating one gives it a synthetic owner key and its own settings row")
    void createProvisionsOwnerKeyAndSettings() {
        SharedScan scan = service.create("Rob & David", ROB, DAVID);

        assertThat(scan.getOwnerKey()).startsWith("shared:");
        assertThat(service.settingsFor(scan)).isNotNull();
    }
}
```

- [ ] **Step 2: Write the failing isolation test**

Create `src/test/java/com/robsartin/setlistscout/scan/SharedOwnerIsolationTest.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #163 spec section 4. This design widens {@code owner} from "a real user" to "a scan scope", and
 * these are the tests that contain that widening: a synthetic owner's rows must never surface on a
 * real user's pages. The isolation is by construction (different owner), which is exactly why it
 * deserves assertions rather than a comment -- nothing else would catch a regression.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SharedOwnerIsolationTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private ArtistRepository artistRepository;
    @Autowired private ShowRepository showRepository;

    private String sharedKey;

    @BeforeEach
    void setUp() {
        showRepository.deleteAll();
        artistRepository.deleteAll();
        sharedKey = SharedScanOwner.newKey();

        Artist sharedArtist = new Artist("Shared Only Artist", ArtistSource.SEED_LIST,
                ArtistStatus.SEED, null, null);
        sharedArtist.setOwner(sharedKey);
        artistRepository.save(sharedArtist);

        Show sharedShow = new Show("Shared Only Artist", LocalDateTime.now().plusDays(10),
                "Shared Only Venue", "Chicago", BigDecimal.TEN, "ticketmaster", "https://x");
        sharedShow.setOwner(sharedKey);
        showRepository.save(sharedShow);
    }

    private String pageAs(String path, String email) throws Exception {
        return mockMvc.perform(get(path).with(oidcLogin().idToken(t -> t.claim("email", email))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("a shared scan's shows never appear on a participant's own Shows page")
    void sharedShowsDoNotLeakIntoPersonalShows() throws Exception {
        assertThat(pageAs("/", ROB)).doesNotContain("Shared Only Venue");
    }

    @Test
    @DisplayName("a shared scan's artists never appear on a participant's Artists page")
    void sharedArtistsDoNotLeakIntoArtistsPage() throws Exception {
        assertThat(pageAs("/artists", ROB)).doesNotContain("Shared Only Artist");
    }

    @Test
    @DisplayName("a shared scan's artists never appear in the Candidates queue or its nav badge")
    void sharedArtistsDoNotLeakIntoCandidates() throws Exception {
        assertThat(pageAs("/artists/candidates", ROB)).doesNotContain("Shared Only Artist");
    }

    @Test
    @DisplayName("a synthetic owner key can never authenticate -- it is not an allow-listed address")
    void syntheticOwnerCannotAuthenticate() throws Exception {
        // SecurityConfig authorises against setlistscout.auth.allowed-emails; a generated key can
        // never match one. Asserting it here means a future change to that matcher trips this test.
        mockMvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", sharedKey))))
                .andExpect(status().is4xxClientError());
    }
}
```

- [ ] **Step 3: Run both and watch them fail**

Run: `./gradlew --no-daemon test --tests "*SharedScanServiceTest" --tests "*SharedOwnerIsolationTest" --console=plain`
Expected: FAIL — `SharedScanService` does not exist. `SharedOwnerIsolationTest` may already pass, since isolation holds by construction; that is fine and expected — it is a regression guard, not a red-to-green step. Note in the commit message which of its assertions were already satisfied.

- [ ] **Step 4: Write the service**

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScanService.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Which shared scans a user may see, and their contents (#163).
 * <p>
 * Access is participant-based, not admin-based: a shared scan is shows that two people would both
 * want, so both of them can see it. {@code AdminGuard} still gates CREATING one -- that is the
 * admin action -- but viewing is not admin-only.
 */
@Service
public class SharedScanService {

    private final SharedScanRepository sharedScanRepository;
    private final ShowRepository showRepository;
    private final ArtistRepository artistRepository;
    private final SettingsService settingsService;
    private final SharedScanReconciler reconciler;

    public SharedScanService(SharedScanRepository sharedScanRepository,
                              ShowRepository showRepository,
                              ArtistRepository artistRepository,
                              SettingsService settingsService,
                              SharedScanReconciler reconciler) {
        this.sharedScanRepository = sharedScanRepository;
        this.showRepository = showRepository;
        this.artistRepository = artistRepository;
        this.settingsService = settingsService;
        this.reconciler = reconciler;
    }

    /** Every shared scan {@code email} participates in. Empty for an unauthenticated caller. */
    public List<SharedScan> visibleTo(String email) {
        if (email == null || email.isBlank()) {
            return List.of();
        }
        return sharedScanRepository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(email, email);
    }

    /**
     * @throws ResponseStatusException 404 if no such shared scan exists OR the caller is not a
     * participant. Deliberately 404 rather than 403 for a non-participant: a 403 would confirm the
     * id exists, which is information a non-participant has no business receiving.
     */
    public SharedScan requireVisible(String email, Long id) {
        return sharedScanRepository.findById(id)
                .filter(scan -> scan.includes(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The shared scan's location/window settings, creating defaults on first access. */
    public SearchSettings settingsFor(SharedScan scan) {
        return settingsService.getOrCreateSettings(scan.getOwnerKey());
    }

    /**
     * How many artists are currently shared. The page needs this to tell "you two have nothing in
     * common" apart from "you share artists, none of them are playing there" -- collapsing those
     * into one empty state is the failure mode the spec calls out.
     */
    public int sharedArtistCount(SharedScan scan) {
        return artistRepository.findByOwnerAndStatusIn(scan.getOwnerKey(),
                List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)).size();
    }

    /** The shared scan's upcoming shows, in date order, within its configured window. */
    public List<Show> showsFor(SharedScan scan) {
        SearchSettings settings = settingsFor(scan);
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());
        return showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                scan.getOwnerKey(), start, end);
    }

    /**
     * Provision a new shared scan: allocate its synthetic owner key, create its settings row, and
     * populate its artists from the participants' current intersection.
     */
    @Transactional
    public SharedScan create(String label, String ownerA, String ownerB) {
        SharedScan scan = sharedScanRepository.save(
                new SharedScan(SharedScanOwner.newKey(), ownerA, ownerB, label));
        settingsService.getOrCreateSettings(scan.getOwnerKey());
        reconciler.reconcile(scan);
        return scan;
    }

    /** Update the shared scan's search location/window. Publishes SettingsChanged, which re-dues its scan jobs. */
    public SearchSettings updateSettings(SharedScan scan, String postalCode, int radiusMiles, int monthsAhead) {
        return settingsService.updateSettings(scan.getOwnerKey(), postalCode, radiusMiles, monthsAhead);
    }
}
```

- [ ] **Step 5: Run both tests and watch them pass**

Run: `./gradlew --no-daemon test --tests "*SharedScanServiceTest" --tests "*SharedOwnerIsolationTest" --console=plain`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/SharedScanService.java \
        src/test/java/com/robsartin/setlistscout/scan/SharedScanServiceTest.java \
        src/test/java/com/robsartin/setlistscout/scan/SharedOwnerIsolationTest.java
git commit -m "feat: participant-based access to shared scans, plus owner-isolation guards (#163)"
```

---

### Task 6: The `/shared` page

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScanController.java`
- Create: `src/main/resources/templates/shared.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/robsartin/setlistscout/scan/SharedScanControllerTest.java`

**Interfaces:**
- Consumes: `SharedScanService`, `AdminGuard#require()` / `#isAdmin()`, `CurrentUser#email()`, `SharedScanReconciler#reconcile`, `ScanJobRepository#redueAll(String, Instant)`
- Produces: `GET /shared`, `POST /shared/{id}/settings`, `POST /shared/{id}/scan-now`, `POST /shared` (create, admin only)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/scan/SharedScanControllerTest.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SharedScanControllerTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ADMIN = "rob@example.com";
    private static final String OTHER = "david@example.com";
    private static final String STRANGER = "stranger@example.com";

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("setlistscout.auth.admin-email", () -> ADMIN);
        registry.add("setlistscout.auth.allowed-emails", () -> ADMIN + "," + OTHER + "," + STRANGER);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private SharedScanService service;
    @Autowired private SharedScanRepository sharedScanRepository;
    @Autowired private ShowRepository showRepository;

    private SharedScan scan;

    @BeforeEach
    void setUp() {
        showRepository.deleteAll();
        sharedScanRepository.deleteAll();
        scan = service.create("Rob & David", ADMIN, OTHER);
    }

    private String pageAs(String email) throws Exception {
        return mockMvc.perform(get("/shared").with(oidcLogin().idToken(t -> t.claim("email", email))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("both participants can open the page")
    void bothParticipantsSeeIt() throws Exception {
        assertThat(pageAs(ADMIN)).contains("Rob &amp; David");
        assertThat(pageAs(OTHER)).contains("Rob &amp; David");
    }

    @Test
    @DisplayName("a non-participant sees no shared scan, not someone else's")
    void nonParticipantSeesNothing() throws Exception {
        assertThat(pageAs(STRANGER)).doesNotContain("Rob &amp; David");
    }

    @Test
    @DisplayName("only the admin may create a shared scan")
    void onlyAdminCanCreate() throws Exception {
        mockMvc.perform(post("/shared")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER)))
                        .with(csrf())
                        .param("label", "Sneaky").param("ownerB", STRANGER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a non-participant cannot change another pairing's settings")
    void nonParticipantCannotEditSettings() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/settings")
                        .with(oidcLogin().idToken(t -> t.claim("email", STRANGER)))
                        .with(csrf())
                        .param("postalCode", "60601").param("radiusMiles", "25").param("monthsAhead", "3"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a participant can set the location, and it is stored against the shared key")
    void participantCanSetLocation() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/settings")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER)))
                        .with(csrf())
                        .param("postalCode", "60601").param("radiusMiles", "25").param("monthsAhead", "3"))
                .andExpect(status().is3xxRedirection());

        assertThat(service.settingsFor(scan).getRadiusMiles()).isEqualTo(25);
        assertThat(service.settingsFor(scan).getMonthsAhead()).isEqualTo(3);
    }

    @Test
    @DisplayName("shows render in a semantic table with column headers")
    void showsRenderInASemanticTable() throws Exception {
        Show show = new Show("Tom Petty", LocalDateTime.now().plusDays(10), "Metro", "Chicago",
                new BigDecimal("42.50"), "ticketmaster", "https://example.test/tix");
        show.setOwner(scan.getOwnerKey());
        showRepository.save(show);

        String body = pageAs(ADMIN);

        assertThat(body).contains("<th scope=\"col\">Date</th>");
        assertThat(body).contains("<th scope=\"col\">Artist</th>");
        assertThat(body).contains("Metro");
        assertThat(body).contains("class=\"table-scroll\"");
    }

    @Test
    @DisplayName("the user picker is a labelled select, for the admin's create form")
    void createFormPickerIsLabelled() throws Exception {
        sharedScanRepository.deleteAll();
        String body = pageAs(ADMIN);

        assertThat(body).contains("<label for=\"shared-create-target\"");
        assertThat(body).contains("id=\"shared-create-target\"");
    }

    @Test
    @DisplayName("'no location yet' is its own message, not an empty show list")
    void noLocationIsItsOwnState() throws Exception {
        // A freshly created scan has settings but no geocode until a ZIP is saved.
        String body = pageAs(ADMIN);

        assertThat(body).contains("Set a location above");
        assertThat(body).doesNotContain("don't follow any of the same artists");
    }

    @Test
    @DisplayName("'no artists in common' and 'nothing playing there' are different messages")
    void emptyStatesDoNotCollapse() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/settings")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("postalCode", "60601").param("radiusMiles", "25").param("monthsAhead", "3"))
                .andExpect(status().is3xxRedirection());

        // No artists seeded for either participant, so the intersection is genuinely empty --
        // which must NOT read as "we searched and found nothing".
        String body = pageAs(ADMIN);
        assertThat(body).contains("don't follow any of the same artists");
        assertThat(body).doesNotContain("but none of them are");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew --no-daemon test --tests "*SharedScanControllerTest" --console=plain`
Expected: FAIL — no handler for `/shared` (404, not 200).

- [ ] **Step 3: Write the controller**

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScanController.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.AdminGuard;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;

/**
 * The shared-shows page (#163): shows for the artists two users both follow, at a location neither
 * of them has saved.
 * <p>
 * Viewing is participant-based, not admin-only -- these are shows both people would want. Creating
 * a pairing is the admin action. Every handler resolves the scan through
 * {@code SharedScanService#requireVisible}, so a non-participant gets a 404 rather than another
 * pairing's data.
 */
@Controller
public class SharedScanController {

    private final SharedScanService sharedScanService;
    private final SharedScanReconciler reconciler;
    private final ScanJobRepository scanJobRepository;
    private final CurrentUser currentUser;
    private final AdminGuard adminGuard;

    public SharedScanController(SharedScanService sharedScanService,
                                 SharedScanReconciler reconciler,
                                 ScanJobRepository scanJobRepository,
                                 CurrentUser currentUser,
                                 AdminGuard adminGuard) {
        this.sharedScanService = sharedScanService;
        this.reconciler = reconciler;
        this.scanJobRepository = scanJobRepository;
        this.currentUser = currentUser;
        this.adminGuard = adminGuard;
    }

    @GetMapping("/shared")
    public String page(Model model) {
        String email = currentUser.email();
        List<SharedScan> scans = sharedScanService.visibleTo(email);
        model.addAttribute("sharedScans", scans);
        if (!scans.isEmpty()) {
            SharedScan scan = scans.get(0);
            model.addAttribute("scan", scan);
            model.addAttribute("settings", sharedScanService.settingsFor(scan));
            model.addAttribute("shows", sharedScanService.showsFor(scan));
            model.addAttribute("otherParticipant", scan.otherParticipant(email));
            // Lets the page distinguish "you have nothing in common" from "nothing playing there".
            model.addAttribute("sharedArtistCount", sharedScanService.sharedArtistCount(scan));
        }
        return "shared";
    }

    /** Admin-only: create the pairing. The other participant comes from the allow-list dropdown. */
    @PostMapping("/shared")
    public String create(@RequestParam String label, @RequestParam String ownerB) {
        adminGuard.require();
        sharedScanService.create(label, currentUser.email(), ownerB);
        return "redirect:/shared";
    }

    /** Either participant may set where the shared scan looks. Publishes SettingsChanged, re-duing its jobs. */
    @PostMapping("/shared/{id}/settings")
    public String updateSettings(@PathVariable Long id,
                                  @RequestParam String postalCode,
                                  @RequestParam(defaultValue = "50") int radiusMiles,
                                  @RequestParam(defaultValue = "6") int monthsAhead) {
        SharedScan scan = sharedScanService.requireVisible(currentUser.email(), id);
        sharedScanService.updateSettings(scan, postalCode, radiusMiles, monthsAhead);
        return "redirect:/shared";
    }

    /**
     * Re-check the intersection and mark this shared scan's jobs due now. There is no synchronous
     * scan to wait on -- the paced poller drains them -- so this queues and returns, exactly like
     * the Shows page's own "Scan now".
     */
    @PostMapping("/shared/{id}/scan-now")
    public String scanNow(@PathVariable Long id) {
        SharedScan scan = sharedScanService.requireVisible(currentUser.email(), id);
        reconciler.reconcile(scan);
        scanJobRepository.redueAll(scan.getOwnerKey(), Instant.now());
        return "redirect:/shared";
    }
}
```

- [ ] **Step 4: Write the template**

Create `src/main/resources/templates/shared.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{fragments/layout :: page('Shared Shows', 'shared', ~{::content})}">
<body>
<div th:fragment="content">
    <h1>Shared Shows</h1>

    <!--/* No pairing yet. Only the admin can create one, so everyone else gets an explanation
           rather than a form they cannot submit. */-->
    <th:block th:if="${scan == null}">
        <p class="page-sub">A shared scan tracks the artists you and another user both follow, at a
            location you choose together.</p>

        <div class="settings" th:if="${isAdmin and not #lists.isEmpty(otherOwnerEmails)}">
            <form th:action="@{/shared}" method="post">
                <label for="shared-create-label">Name</label>
                <input id="shared-create-label" type="text" name="label" value="Shared" required/>
                <label for="shared-create-target">Shared with</label>
                <select id="shared-create-target" name="ownerB" required>
                    <option th:each="email : ${otherOwnerEmails}" th:value="${email}"
                            th:text="${email}">user@example.com</option>
                </select>
                <button type="submit">Create shared scan</button>
            </form>
        </div>
        <p th:unless="${isAdmin}">No shared scan has been set up yet.</p>
    </th:block>

    <th:block th:if="${scan != null}">
        <p class="page-sub">
            <span th:text="${scan.label}">Shared</span> &mdash; artists you and
            <span th:text="${otherParticipant}">someone</span> both follow.
        </p>

        <div class="settings">
            <form th:action="@{'/shared/' + ${scan.id} + '/settings'}" method="post">
                Near ZIP
                <label class="visually-hidden" for="shared-zip">Postal code</label>
                <input id="shared-zip" type="text" name="postalCode" th:value="${settings.postalCode}"
                       size="5" maxlength="5" pattern="[0-9]{5}" required/>
                within
                <label class="visually-hidden" for="shared-radius">Radius in miles</label>
                <input id="shared-radius" type="number" name="radiusMiles"
                       th:value="${settings.radiusMiles}" min="1" max="500"/> miles, next
                <label class="visually-hidden" for="shared-months">Months ahead</label>
                <input id="shared-months" type="number" name="monthsAhead"
                       th:value="${settings.monthsAhead}" min="1" max="24"/> months
                <button type="submit">Save</button>
                <span th:if="${settings.city != null}"
                      th:text="'(' + ${settings.city} + ', ' + ${settings.state} + ')'">(Chicago, IL)</span>
            </form>
            <form class="inline" th:action="@{'/shared/' + ${scan.id} + '/scan-now'}" method="post">
                <button type="submit">Scan now</button>
            </form>
        </div>

        <!--/* Three distinct states, and they must stay distinct. "We can't search yet",
               "you two follow no one in common", and "we searched and found nothing" are
               different answers; collapsing them into one "no results" is the failure mode the
               spec calls out. Order matters -- each th:if is guarded so exactly one can render. */-->
        <p th:if="${settings.latitude == null}">
            Set a location above before this can search &mdash; we don't have coordinates for that ZIP yet.
        </p>
        <p th:if="${settings.latitude != null and sharedArtistCount == 0}">
            You and <span th:text="${otherParticipant}">them</span> don't follow any of the same
            artists yet, so there's nothing to search for.
        </p>
        <p th:if="${settings.latitude != null and sharedArtistCount > 0 and #lists.isEmpty(shows)}">
            You share <strong th:text="${sharedArtistCount}">0</strong> artists, but none of them are
            playing near <span th:text="${settings.city} ?: ${settings.postalCode}">there</span> in the
            next <span th:text="${settings.monthsAhead}">6</span> months. Shows appear here as the
            scan runs in the background.
        </p>

        <div class="table-scroll" th:if="${not #lists.isEmpty(shows)}">
            <table>
                <caption class="visually-hidden">Shows for artists both users follow</caption>
                <thead>
                <tr>
                    <th scope="col">Date</th>
                    <th scope="col">Artist</th>
                    <th scope="col">Venue</th>
                    <th scope="col">City</th>
                    <th scope="col">Price</th>
                    <th scope="col">Source</th>
                    <th scope="col">Tickets</th>
                </tr>
                </thead>
                <tbody>
                <tr th:each="show : ${shows}">
                    <td th:text="${#temporals.format(show.eventDateTime, 'EEE, MMM d yyyy h:mm a')}">Date</td>
                    <td th:text="${show.artistName}">Artist</td>
                    <td th:text="${show.venueName}">Venue</td>
                    <td th:text="${show.venueCity}">City</td>
                    <td th:text="${show.price != null} ? '$' + ${show.price} : '—'">Price</td>
                    <td th:text="${show.source}">Source</td>
                    <td>
                        <a th:if="${show.ticketUrl != null}" th:href="${show.ticketUrl}"
                           th:aria-label="'Tickets for ' + ${show.artistName} + ' at ' + ${show.venueName}">Tickets</a>
                    </td>
                </tr>
                </tbody>
            </table>
        </div>
    </th:block>
</div>
</body>
</html>
```

- [ ] **Step 5: Add the nav link**

In `src/main/resources/templates/fragments/layout.html`, inside `<nav class="main" aria-label="Primary">`, immediately after the existing `Shows` link:

```html
            <a th:href="@{/shared}" th:attr="aria-current=${navActive == 'shared'} ? 'page' : null">Shared</a>
```

Not gated on `isAdmin` — both participants can see it.

- [ ] **Step 6: Add the missing focus style**

In `src/main/resources/static/css/app.css`, line 70 currently reads:

```css
button:focus-visible, a:focus-visible, input:focus-visible, summary:focus-visible { outline:2px solid var(--primary); outline-offset:2px; }
```

`select` is absent, and this page adds one. Add it:

```css
button:focus-visible, a:focus-visible, input:focus-visible, select:focus-visible, summary:focus-visible { outline:2px solid var(--primary); outline-offset:2px; }
```

- [ ] **Step 7: Run the test and watch it pass**

Run: `./gradlew --no-daemon test --tests "*SharedScanControllerTest" --console=plain`
Expected: PASS, 9 tests.

- [ ] **Step 8: Run the full gate**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain > /tmp/gate.log 2>&1
python3 scripts/check_adrs.py
```
Expected: BUILD SUCCESSFUL and the ADR check passing. `ModularityTests` must be green. If a single failure is `PollerFlowTest.expandHappyPath` with an `AssertionFailedError`, that is #172's flake — re-run that class with `--rerun` in isolation to confirm before treating it as real.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/SharedScanController.java \
        src/main/resources/templates/shared.html \
        src/main/resources/templates/fragments/layout.html \
        src/main/resources/static/css/app.css \
        src/test/java/com/robsartin/setlistscout/scan/SharedScanControllerTest.java
git commit -m "feat: /shared page -- participant-visible shared shows with editable location (#163)"
```

---

## Notes for the implementer

**Deliberately not built** (spec non-goals — do not add them): N-way sharing; multiple simultaneous locations; hiding/unhiding shared shows (#166 is personal-shows only); cross-source dedup (#79 owns it); band-site/LLM source for shared scans; notifications.

**Two things in this plan are load-bearing and easy to "simplify" into bugs:**

1. **`SharedScanReconciler#onParticipantArtistChanged`'s early return on shared keys.** It looks like a redundant defensive check. It is the only thing preventing infinite recursion: reconcile creates an artist under the shared key → `ArtistActivated(sharedKey)` → this listener → reconcile again. Never remove it.
2. **The source allow-list in `ScanJobListener`.** A deny-list would silently include any source added later. band-site is excluded because it bills an LLM call per artist, and a shared scan's artists are already covered by both participants' own scans.

**`AdminGuard` already exists** — extracted in commit `6da8bbf` earlier on this branch. Use it; do not re-create it.

**Task 5's `SharedOwnerIsolationTest` may pass before its implementation step.** Isolation holds by construction (a different owner value), so those assertions are regression guards rather than red-to-green steps. That is intended — say so in the commit rather than contriving a failure.
