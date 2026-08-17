# Shared Show Scan Implementation Plan

**Superseded by `docs/superpowers/plans/2026-08-16-shared-scan-permanent.md`** — this plan implemented the transient, admin-only, run-and-discard design, which was replaced by a permanent, auto-refreshing one visible to both participants.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an admin-only page that answers "we're both going to be in Chicago that week — which shows do *we both* want to see?" by intersecting two owners' artist lists and searching a location neither of them has saved.

**Architecture:** Four layers, each independently testable. A shared `AdminGuard` replaces three copies of the admin check. `catalog.SharedArtistFinder` computes the **normalized** intersection of two owners' active artists. `scan.SharedScanService` geocodes an ad-hoc location, enforces a cap, and drives the existing `ShowSource` ports directly — no `ScanJob`, no poller, no persistence. `scan.SharedScanController` renders one new page.

**Tech Stack:** Java 21 source / JDK 25 toolchain, Spring Boot 3.5, Spring Modulith, Thymeleaf + vendored htmx 2.0.3, Postgres + Testcontainers, JUnit 5 + Mockito + AssertJ.

## Global Constraints

Every task's requirements implicitly include all of these.

- **The intersection must go through `catalog.ArtistNameNormalizer`.** Never hand-roll name equality. Verified against production: Rob has 1,269 active artists, David has 5, the true overlap is **4**, and an exact-name join finds **1**. This is the single most likely way to build this feature and have it silently under-report.
- **Active means `SEED` + `APPROVED` only** — matching `ArtistActivationService#isActive`. `REJECTED`, `REMOVED`, and `PENDING_REVIEW` are not active on either side.
- **Admin-only.** Reuse the existing `#136` config-driven gate. A hidden button is not access control — every endpoint re-checks server-side.
- **Transient results.** No schema change, no migration, no writes to `show_event`. `ShowSource.search` is documented query-only; keep it that way.
- **Sources are Ticketmaster + Bandsintown only**, selected by an **allow-list of source ids**, never a deny-list. `BandSiteShowSource` scrapes and falls back to `TourPageLlmService` (slow, billed per artist) — wrong inside a synchronous request.
- **Ticketmaster needs `geoPoint`, not `postalCode` (#152).** It silently returns HTTP 200 with zero results for postal codes it does not index. The entered location must be geocoded to lat/long **before** building any `ScanQuery`.
- **The four failure/empty states each render a distinct message.** Collapsing them is the main UX failure mode for this feature.
- **No custom JavaScript.** The app ships none; keep it that way. Use Thymeleaf `th:hx-*` attributes — a plain `hx-get="@{/x}"` ships the literal string.
- **`hx-swap-oob="innerHTML"` on `#sr-status` is load-bearing.** The default `hx-swap-oob="true"` replaces the node and drops `role`/`aria-live`, silently killing every announcement after the first.
- **Owner-scope everything and assert it in tests.** The scan reads exactly the two named owners; no third party's artists may leak in.
- **`ModularityTests` must stay green.** `shared` is OPEN; `scan → catalog` and `scan → settings` are already established (see `ShowController`).
- **TDD**: failing test → implement → green → commit. Commit after every task.

## The gate

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain
python3 scripts/check_adrs.py
```

Gradle cannot *launch* on JDK 25 — launch on 21; the toolchain forks 25. Docker Desktop must be running. The build exceeds the 600s tool cap: redirect to a log file and poll that file, and verify the log belongs to *this* run.

## File Structure

| File | Responsibility |
|---|---|
| `shared/AdminGuard.java` (new) | The one definition of "is the current user the admin", and the 403 throw. |
| `catalog/SharedArtistFinder.java` (new) | Normalized intersection of two owners' active artists. Knows nothing about scanning. |
| `scan/SharedScanRequest.java` (new) | One form submission: location + radius + window. |
| `scan/SharedScanResult.java` (new) | One outcome, with an explicit `Outcome` enum so the five states can't collapse. |
| `scan/SharedScanService.java` (new) | Geocode → intersect → cap → drive sources → merge/sort. No persistence. |
| `scan/SharedScanController.java` (new) | `GET`/`POST /shared-scan`, admin gate, model, htmx fragment vs full page. |
| `templates/shared-scan.html` (new) | The page: form, results table, four distinct empty states. |
| `templates/fragments/layout.html` (modify) | Nav link, admin-only. |
| `AppProperties.java` (modify) | `SharedScan` component carrying the cap. |
| `application.yml` (modify) | Cap default via `${...}` env-var convention. |
| `service/TestAppProperties.java` (modify) | The only `new AppProperties(...)` site in the codebase. |
| `ShowController.java`, `ReviewController.java`, `NavModelAdvice.java` (modify) | Migrate onto `AdminGuard`. |

---

### Task 1: Extract `AdminGuard`

The admin check exists **four** times today: byte-identical `private void requireAdmin()` in `ShowController:requireAdmin` and `ReviewController:347-352`, plus the same predicate inline in `NavModelAdvice#isAdmin`. This task does not add behavior — it stops #163 from becoming a fifth copy of a security rule.

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/shared/AdminGuard.java`
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ShowController.java`
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java`
- Modify: `src/main/java/com/robsartin/setlistscout/review/NavModelAdvice.java`
- Test: `src/test/java/com/robsartin/setlistscout/shared/AdminGuardTest.java`

**Interfaces:**
- Consumes: `shared.CurrentUser#email()`, `AppProperties#auth()#adminEmail()`
- Produces: `AdminGuard#isAdmin(): boolean`, `AdminGuard#require(): void` (throws `ResponseStatusException(FORBIDDEN)`)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/shared/AdminGuardTest.java`:

```java
package com.robsartin.setlistscout.shared;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.service.TestAppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminGuardTest {

    /** TestAppProperties#withKeys sets adminEmail to "owner@example.com". */
    private static final String ADMIN = "owner@example.com";

    private AdminGuard guardFor(String signedInEmail) {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(signedInEmail);
        AppProperties properties = TestAppProperties.withKeys();
        return new AdminGuard(currentUser, properties);
    }

    @Test
    @DisplayName("the configured admin is admin, and require() lets them through")
    void adminPasses() {
        AdminGuard guard = guardFor(ADMIN);
        assertThat(guard.isAdmin()).isTrue();
        guard.require();
    }

    @Test
    @DisplayName("admin match is case-insensitive -- OIDC casing must not decide access")
    void adminMatchIgnoresCase() {
        assertThat(guardFor("OWNER@EXAMPLE.COM").isAdmin()).isTrue();
    }

    @Test
    @DisplayName("a non-admin user is refused with 403")
    void nonAdminIsForbidden() {
        AdminGuard guard = guardFor("someone-else@example.com");
        assertThat(guard.isAdmin()).isFalse();
        assertThatThrownBy(guard::require)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("no authenticated principal is refused, not treated as admin")
    void nullEmailIsForbidden() {
        AdminGuard guard = guardFor(null);
        assertThat(guard.isAdmin()).isFalse();
        assertThatThrownBy(guard::require).isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "*AdminGuardTest" --console=plain`
Expected: FAIL — compilation error, `AdminGuard` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/robsartin/setlistscout/shared/AdminGuard.java`:

```java
package com.robsartin.setlistscout.shared;

import com.robsartin.setlistscout.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single definition of "is the current user the configured admin" (#136).
 * <p>
 * Extracted at #163, when a third endpoint needed it. Before this it lived as byte-identical
 * {@code private void requireAdmin()} copies in {@code ShowController} and {@code ReviewController}
 * plus the same predicate inline in {@code NavModelAdvice#isAdmin} -- four hand-maintained copies of
 * one security rule, which is how such rules drift apart.
 * <p>
 * Still a config check rather than a roles system, exactly as #136 decided: a Role enum, table, and
 * admin-toggle UI would be real infrastructure for an app with two allowed users. Revisit if it
 * grows past that. {@link #isAdmin()} drives UI visibility only -- a hidden button is not access
 * control, so every admin endpoint calls {@link #require()} itself.
 */
@Component
public class AdminGuard {

    private final CurrentUser currentUser;
    private final AppProperties appProperties;

    public AdminGuard(CurrentUser currentUser, AppProperties appProperties) {
        this.currentUser = currentUser;
        this.appProperties = appProperties;
    }

    /** True only for the configured admin. False when nobody is signed in. */
    public boolean isAdmin() {
        String owner = currentUser.email();
        return owner != null && owner.equalsIgnoreCase(appProperties.auth().adminEmail());
    }

    /** Throws {@code 403 FORBIDDEN} unless the current user is the configured admin. */
    public void require() {
        if (!isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "*AdminGuardTest" --console=plain`
Expected: PASS, 4 tests.

- [ ] **Step 5: Migrate `ShowController`**

In `src/main/java/com/robsartin/setlistscout/scan/ShowController.java`:

Add the import `import com.robsartin.setlistscout.shared.AdminGuard;`. Add `private final AdminGuard adminGuard;` beside the other fields, add `AdminGuard adminGuard` as the final constructor parameter, and assign `this.adminGuard = adminGuard;`.

Delete the entire `private void requireAdmin()` method **and its Javadoc block** (the one beginning "Placeholder admin gate for #136"), and replace the single call site inside `adminScanNow` — the line reading `requireAdmin();` — with:

```java
        adminGuard.require();
```

Then remove `appProperties` entirely from this class: the field, the constructor parameter, the assignment, and the `import com.robsartin.setlistscout.AppProperties;` line. This was verified before the plan was written — `appProperties` had exactly one reader in `ShowController`, the `adminEmail()` comparison inside `requireAdmin`, which you just deleted. Leaving an unused injected field behind is a review finding.

- [ ] **Step 6: Migrate `ReviewController`**

Apply the identical change in `src/main/java/com/robsartin/setlistscout/review/ReviewController.java`: inject `AdminGuard`, delete `requireAdmin()` at lines 347-352 along with its preceding Javadoc, and replace its call site(s) with `adminGuard.require();`.

Remove `appProperties` here too — same verification, same single reader (the `adminEmail()` comparison at line 349, which you just deleted).

- [ ] **Step 7: Migrate `NavModelAdvice`**

In `src/main/java/com/robsartin/setlistscout/review/NavModelAdvice.java`, replace the body of `isAdmin()` with a delegation, keeping the `@ModelAttribute("isAdmin")` annotation and the existing Javadoc:

```java
    @ModelAttribute("isAdmin")
    public boolean isAdmin() {
        return adminGuard.isAdmin();
    }
```

Inject `AdminGuard` as a constructor parameter and field. Keep `appProperties` — `otherOwnerEmails()` still reads it.

- [ ] **Step 8: Run the full gate**

Run:
```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain
```
Expected: BUILD SUCCESSFUL. `AdminCrossAccountActionsTest` is the test that proves the migration preserved behavior — it must still pass unchanged. This is a Mikado step: green before moving on.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/shared/AdminGuard.java \
        src/test/java/com/robsartin/setlistscout/shared/AdminGuardTest.java \
        src/main/java/com/robsartin/setlistscout/scan/ShowController.java \
        src/main/java/com/robsartin/setlistscout/review/ReviewController.java \
        src/main/java/com/robsartin/setlistscout/review/NavModelAdvice.java
git commit -m "refactor: extract AdminGuard from three copies of the admin check (#163)"
```

---

### Task 2: `SharedArtistFinder` — the normalized intersection

This is the load-bearing task. Everything else is plumbing; this is where the feature is silently wrong if built the obvious way.

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/catalog/SharedArtistFinder.java`
- Test: `src/test/java/com/robsartin/setlistscout/catalog/SharedArtistFinderTest.java`

**Interfaces:**
- Consumes: `ArtistRepository#findByOwnerAndStatusIn(String, List<ArtistStatus>)`, `ArtistNameNormalizer#normalize(String)`
- Produces: `SharedArtistFinder#findSharedArtistNames(String ownerA, String ownerB): List<String>` — one name per artist active for **both** owners, in `ownerA`'s spelling, sorted case-insensitively, no duplicates.

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
 * #163. The reason this class exists at all is that an exact-name join looks correct and
 * under-reports badly against real data -- see {@link #normalizationIsWhatMakesTheIntersectionWork}.
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

    @Autowired
    private SharedArtistFinder finder;

    @Autowired
    private ArtistRepository artistRepository;

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
        // The four genuinely-shared artists as they are actually spelled in production today.
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

        // The regression guard. An exact-name join over this exact data finds only the single
        // identically-spelled pair. If findSharedArtistNames is ever "simplified" to string
        // equality, the assertion above drops to 1 and this line documents why that is wrong.
        List<String> davidNames = artistRepository
                .findByOwnerAndStatusIn(DAVID, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED))
                .stream().map(Artist::getName).toList();
        long exactMatches = artistRepository
                .findByOwnerAndStatusIn(ROB, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED))
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

- [ ] **Step 2: Run test to verify it fails**

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
 * "Which artists do these two owners both actively follow?" -- the input to the shared show scan
 * (#163).
 * <p>
 * The match runs through {@link ArtistNameNormalizer}, never string equality, and that is the whole
 * point of this class. Measured against production before it was written: Rob has 1,269 active
 * artists, David has 5, and they genuinely share 4 -- but an exact-name join finds 1, because
 * David's entries are lowercased on the second word ("Tom petty" vs "Tom Petty"). An exact join
 * returns a plausible-looking non-empty answer, which is exactly why it would have shipped.
 * <p>
 * Note the asymmetry is structural rather than incidental: the intersection is bounded by the
 * SMALLER list. That is what makes the caller's synchronous execution safe -- cost scales with the
 * smaller user, not the larger.
 */
@Component
public class SharedArtistFinder {

    /**
     * The same definition of "active" as {@code ArtistActivationService#isActive}: a hand-curated
     * seed or a reviewed-and-approved candidate. REJECTED/REMOVED/PENDING_REVIEW are deliberately
     * excluded on BOTH sides -- an artist one owner has rejected is not one they want to be sold
     * tickets to.
     */
    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    private final ArtistRepository artistRepository;

    public SharedArtistFinder(ArtistRepository artistRepository) {
        this.artistRepository = artistRepository;
    }

    /**
     * @return one name per artist active for BOTH owners, in {@code ownerA}'s spelling, sorted
     * case-insensitively, with no duplicates. Which side's spelling wins does not affect search
     * results -- Ticketmaster and Bandsintown keyword search are both case-insensitive -- so
     * {@code ownerA}'s is used because ownerA is the admin looking at the page, and seeing a
     * different user's capitalisation of your own artist reads as a bug.
     */
    public List<String> findSharedArtistNames(String ownerA, String ownerB) {
        Set<String> keysForB = new HashSet<>();
        for (Artist artist : artistRepository.findByOwnerAndStatusIn(ownerB, ACTIVE)) {
            keysForB.add(ArtistNameNormalizer.normalize(artist.getName()));
        }

        // putIfAbsent, not put: if ownerA somehow holds two case-variant rows for one artist (the
        // condition V13 merged and #118 guards against), they collapse to a single result here
        // rather than searching the same artist twice.
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "*SharedArtistFinderTest" --console=plain`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/SharedArtistFinder.java \
        src/test/java/com/robsartin/setlistscout/catalog/SharedArtistFinderTest.java
git commit -m "feat: normalized intersection of two owners' active artists (#163)"
```

---

### Task 3: `SharedScanService` — geocode, cap, drive the sources

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScanRequest.java`
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScanResult.java`
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScanService.java`
- Modify: `src/main/java/com/robsartin/setlistscout/AppProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/robsartin/setlistscout/service/TestAppProperties.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/SharedScanServiceTest.java`

**Interfaces:**
- Consumes: `SharedArtistFinder#findSharedArtistNames(String, String): List<String>`, `GeocodingService#geocode(String): Optional<GeoResult>`, `GeocodingService#geocodeCity(String, String): Optional<GeoResult>`, `ShowSource#id(): String`, `ShowSource#search(ScanQuery): List<Show>`
- Produces:
  - `record SharedScanRequest(String postalCode, String city, String state, int radiusMiles, int monthsAhead)`
  - `record SharedScanResult(Outcome outcome, List<String> sharedArtistNames, int sharedArtistCount, int cap, List<Show> shows, String locationLabel)` with `enum Outcome { OK, GEOCODING_FAILED, NO_SHARED_ARTISTS, OVER_CAP, NO_SHOWS }` and factories `geocodingFailed()`, `noSharedArtists()`, `overCap(int, int)`, `noShows(List<String>, String)`, `ok(List<String>, List<Show>, String)`
  - `SharedScanService#scan(String ownerA, String ownerB, SharedScanRequest): SharedScanResult`
  - `AppProperties.SharedScan(int maxSharedArtists)` reachable as `appProperties.sharedScan().maxSharedArtists()`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/scan/SharedScanServiceTest.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.catalog.SharedArtistFinder;
import com.robsartin.setlistscout.scan.source.ScanQuery;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.GeocodingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #163. Every assertion here is about a decision the spec makes explicitly: which sources run,
 * that the location reaches Ticketmaster as lat/long (#152), and that each refusal path costs
 * zero API calls.
 */
class SharedScanServiceTest {

    private static final String ROB = "rob@example.com";
    private static final String DAVID = "david@example.com";

    private final SharedArtistFinder finder = mock(SharedArtistFinder.class);
    private final GeocodingService geocoding = mock(GeocodingService.class);

    /** Records every ScanQuery it is handed, so tests can assert on call count AND contents. */
    private static final class RecordingSource implements ShowSource {
        private final String id;
        private final List<Show> results;
        final List<ScanQuery> queries = new ArrayList<>();

        RecordingSource(String id, List<Show> results) {
            this.id = id;
            this.results = results;
        }

        @Override public String id() { return id; }

        @Override public List<Show> search(ScanQuery query) {
            queries.add(query);
            return results;
        }
    }

    private static Show show(String artist, LocalDateTime when) {
        return new Show(artist, when, "The Venue", "Chicago", BigDecimal.TEN, "ticketmaster", "https://x");
    }

    private SharedScanService serviceWith(List<ShowSource> sources, int cap) {
        AppProperties properties = new AppProperties(
                new AppProperties.Auth(List.of(ROB, DAVID), ROB, ROB),
                new AppProperties.Apis("tm", "bit", "agent", "discogs", "lastfm", "anthropic"),
                new AppProperties.Defaults("78701", "Austin", "TX", 50, 6),
                new AppProperties.SharedScan(cap));
        return new SharedScanService(finder, geocoding, sources, properties);
    }

    private static SharedScanRequest chicagoZip() {
        return new SharedScanRequest("60601", null, null, 25, 3);
    }

    private void geocodeSucceeds() {
        when(geocoding.geocode(anyString()))
                .thenReturn(Optional.of(new GeocodingService.GeoResult(41.885, -87.622, "Chicago", "IL")));
    }

    @Test
    @DisplayName("happy path: one query per shared artist per allowed source, sorted by date")
    void searchesEachSharedArtistWithEachSource() {
        geocodeSucceeds();
        when(finder.findSharedArtistNames(ROB, DAVID)).thenReturn(List.of("Tom Petty", "James Taylor"));

        LocalDateTime later = LocalDateTime.now().plusDays(10);
        LocalDateTime sooner = LocalDateTime.now().plusDays(2);
        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of(show("Tom Petty", later)));
        RecordingSource bandsintown = new RecordingSource("bandsintown", List.of(show("James Taylor", sooner)));

        SharedScanResult result = serviceWith(List.of(ticketmaster, bandsintown), 25).scan(ROB, DAVID, chicagoZip());

        assertThat(result.outcome()).isEqualTo(SharedScanResult.Outcome.OK);
        assertThat(ticketmaster.queries).hasSize(2);
        assertThat(bandsintown.queries).hasSize(2);
        assertThat(result.shows()).extracting(Show::getEventDateTime).containsExactly(sooner, later);
        assertThat(result.locationLabel()).isEqualTo("Chicago, IL");
    }

    @Test
    @DisplayName("sources are chosen by allow-list: band-site never runs here")
    void excludesBandSiteSource() {
        geocodeSucceeds();
        when(finder.findSharedArtistNames(ROB, DAVID)).thenReturn(List.of("Tom Petty"));

        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of());
        RecordingSource bandSite = new RecordingSource("band-site", List.of());

        serviceWith(List.of(ticketmaster, bandSite), 25).scan(ROB, DAVID, chicagoZip());

        assertThat(ticketmaster.queries).hasSize(1);
        assertThat(bandSite.queries).isEmpty();
    }

    @Test
    @DisplayName("the query carries lat/long -- Ticketmaster needs a geoPoint, not a bare ZIP (#152)")
    void queryCarriesGeocodedLatLong() {
        geocodeSucceeds();
        when(finder.findSharedArtistNames(ROB, DAVID)).thenReturn(List.of("Tom Petty"));
        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of());

        serviceWith(List.of(ticketmaster), 25).scan(ROB, DAVID, chicagoZip());

        ScanQuery query = ticketmaster.queries.get(0);
        assertThat(query.latitude()).isEqualTo(41.885);
        assertThat(query.longitude()).isEqualTo(-87.622);
        assertThat(query.artistName()).isEqualTo("Tom Petty");
        assertThat(query.radiusMiles()).isEqualTo(25);
        assertThat(query.city()).isEqualTo("Chicago");
        assertThat(query.state()).isEqualTo("IL");
    }

    @Test
    @DisplayName("geocoding failure performs zero source calls")
    void geocodingFailurePerformsNoSearches() {
        when(geocoding.geocode(anyString())).thenReturn(Optional.empty());
        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of());

        SharedScanResult result = serviceWith(List.of(ticketmaster), 25).scan(ROB, DAVID, chicagoZip());

        assertThat(result.outcome()).isEqualTo(SharedScanResult.Outcome.GEOCODING_FAILED);
        assertThat(ticketmaster.queries).isEmpty();
    }

    @Test
    @DisplayName("no shared artists is its own outcome, and performs zero source calls")
    void noSharedArtistsPerformsNoSearches() {
        geocodeSucceeds();
        when(finder.findSharedArtistNames(ROB, DAVID)).thenReturn(List.of());
        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of());

        SharedScanResult result = serviceWith(List.of(ticketmaster), 25).scan(ROB, DAVID, chicagoZip());

        assertThat(result.outcome()).isEqualTo(SharedScanResult.Outcome.NO_SHARED_ARTISTS);
        assertThat(ticketmaster.queries).isEmpty();
    }

    @Test
    @DisplayName("over the cap: refuses, names both numbers, performs zero source calls")
    void overCapPerformsNoSearches() {
        geocodeSucceeds();
        when(finder.findSharedArtistNames(ROB, DAVID))
                .thenReturn(List.of("A", "B", "C", "D", "E", "F"));
        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of());

        SharedScanResult result = serviceWith(List.of(ticketmaster), 5).scan(ROB, DAVID, chicagoZip());

        assertThat(result.outcome()).isEqualTo(SharedScanResult.Outcome.OVER_CAP);
        assertThat(result.sharedArtistCount()).isEqualTo(6);
        assertThat(result.cap()).isEqualTo(5);
        assertThat(ticketmaster.queries).isEmpty();
    }

    @Test
    @DisplayName("exactly at the cap still runs -- the limit is inclusive")
    void atCapStillRuns() {
        geocodeSucceeds();
        when(finder.findSharedArtistNames(ROB, DAVID)).thenReturn(List.of("A", "B", "C", "D", "E"));
        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of());

        SharedScanResult result = serviceWith(List.of(ticketmaster), 5).scan(ROB, DAVID, chicagoZip());

        assertThat(result.outcome()).isEqualTo(SharedScanResult.Outcome.NO_SHOWS);
        assertThat(ticketmaster.queries).hasSize(5);
    }

    @Test
    @DisplayName("shared artists but no shows is distinct from no shared artists")
    void sharedArtistsButNoShows() {
        geocodeSucceeds();
        when(finder.findSharedArtistNames(ROB, DAVID)).thenReturn(List.of("Tom Petty"));

        SharedScanResult result = serviceWith(List.of(new RecordingSource("ticketmaster", List.of())), 25)
                .scan(ROB, DAVID, chicagoZip());

        assertThat(result.outcome()).isEqualTo(SharedScanResult.Outcome.NO_SHOWS);
        assertThat(result.sharedArtistCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a city/state location geocodes via geocodeCity when no ZIP is given")
    void cityStateLocationIsGeocoded() {
        when(geocoding.geocodeCity("Chicago", "IL"))
                .thenReturn(Optional.of(new GeocodingService.GeoResult(41.885, -87.622, "Chicago", "IL")));
        when(finder.findSharedArtistNames(ROB, DAVID)).thenReturn(List.of("Tom Petty"));
        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of());

        SharedScanResult result = serviceWith(List.of(ticketmaster), 25)
                .scan(ROB, DAVID, new SharedScanRequest(null, "Chicago", "IL", 25, 3));

        assertThat(result.outcome()).isEqualTo(SharedScanResult.Outcome.NO_SHOWS);
        assertThat(ticketmaster.queries).hasSize(1);
    }

    @Test
    @DisplayName("no location at all is a geocoding failure, not a crash")
    void blankLocationIsGeocodingFailure() {
        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of());

        SharedScanResult result = serviceWith(List.of(ticketmaster), 25)
                .scan(ROB, DAVID, new SharedScanRequest("  ", null, null, 25, 3));

        assertThat(result.outcome()).isEqualTo(SharedScanResult.Outcome.GEOCODING_FAILED);
        assertThat(ticketmaster.queries).isEmpty();
    }

    @Test
    @DisplayName("a source that throws does not fail the whole scan")
    void oneSourceFailingDoesNotFailTheScan() {
        geocodeSucceeds();
        when(finder.findSharedArtistNames(ROB, DAVID)).thenReturn(List.of("Tom Petty"));

        ShowSource exploding = mock(ShowSource.class);
        when(exploding.id()).thenReturn("bandsintown");
        when(exploding.search(any())).thenThrow(new RuntimeException("403 from Bandsintown"));

        LocalDateTime when = LocalDateTime.now().plusDays(3);
        RecordingSource ticketmaster = new RecordingSource("ticketmaster", List.of(show("Tom Petty", when)));

        SharedScanResult result = serviceWith(List.of(ticketmaster, exploding), 25).scan(ROB, DAVID, chicagoZip());

        assertThat(result.outcome()).isEqualTo(SharedScanResult.Outcome.OK);
        assertThat(result.shows()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "*SharedScanServiceTest" --console=plain`
Expected: FAIL — compilation errors; none of `SharedScanService`, `SharedScanRequest`, `SharedScanResult`, or `AppProperties.SharedScan` exist.

- [ ] **Step 3: Add the cap to `AppProperties`**

In `src/main/java/com/robsartin/setlistscout/AppProperties.java`, add `SharedScan sharedScan` as a fourth record component (after `Defaults defaults`) and add the nested record:

Match the existing `//`-comment style the other nested records in this file use — not a Javadoc block:

```java
    public record SharedScan(
            // #163: hard ceiling on how many shared artists one synchronous run will search. Each
            // shared artist costs one API call per enabled source and this page runs inside a
            // single HTTP request, so over the cap we refuse and say so -- never truncate silently,
            // never hang. Config rather than a literal so it can be raised without a redeploy.
            int maxSharedArtists
    ) {}
```

- [ ] **Step 4: Add the default to `application.yml`**

In `src/main/resources/application.yml`, under the existing `setlistscout:` block (the one containing `scan-poller-enabled`), add:

```yaml
  shared-scan:
    max-shared-artists: ${SHARED_SCAN_MAX_ARTISTS:25}
```

- [ ] **Step 5: Update the one `AppProperties` construction site**

In `src/test/java/com/robsartin/setlistscout/service/TestAppProperties.java`, add the fourth argument to the existing `new AppProperties(...)` call:

```java
                new AppProperties.Defaults("78701", "Austin", "TX", 50, 6),
                new AppProperties.SharedScan(25));
```

- [ ] **Step 6: Write `SharedScanRequest`**

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScanRequest.java`:

```java
package com.robsartin.setlistscout.scan;

/**
 * One shared-scan form submission (#163): where to look and how far ahead.
 * <p>
 * Location is either a ZIP or a city/state pair -- ZIP wins when both are supplied. Unlike a normal
 * scan this is deliberately NOT read from anyone's {@code SearchSettings}: the whole point of the
 * feature is searching somewhere neither user lives.
 */
public record SharedScanRequest(String postalCode, String city, String state,
                                 int radiusMiles, int monthsAhead) {
}
```

- [ ] **Step 7: Write `SharedScanResult`**

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScanResult.java`:

```java
package com.robsartin.setlistscout.scan;

import java.util.List;

/**
 * One shared-scan outcome (#163).
 * <p>
 * Deliberately carries an explicit {@link Outcome} rather than letting the caller infer state from
 * an empty show list. The spec calls out four non-success states that each need a DIFFERENT message
 * -- "we couldn't understand that location", "you two share no artists", "you share too many", and
 * "you share N, none playing there" -- and collapsing them into one "no results" is the main UX
 * failure mode this feature has. An enum makes that collapse impossible to do by accident.
 */
public record SharedScanResult(Outcome outcome,
                                List<String> sharedArtistNames,
                                int sharedArtistCount,
                                int cap,
                                List<Show> shows,
                                String locationLabel) {

    public enum Outcome { OK, GEOCODING_FAILED, NO_SHARED_ARTISTS, OVER_CAP, NO_SHOWS }

    /** The entered location could not be geocoded -- nothing was searched. */
    public static SharedScanResult geocodingFailed() {
        return new SharedScanResult(Outcome.GEOCODING_FAILED, List.of(), 0, 0, List.of(), null);
    }

    /** The two lists do not overlap at all. */
    public static SharedScanResult noSharedArtists() {
        return new SharedScanResult(Outcome.NO_SHARED_ARTISTS, List.of(), 0, 0, List.of(), null);
    }

    /** More shared artists than one synchronous run will search. Carries both numbers so the page can name them. */
    public static SharedScanResult overCap(int sharedArtistCount, int cap) {
        return new SharedScanResult(Outcome.OVER_CAP, List.of(), sharedArtistCount, cap, List.of(), null);
    }

    /** Shared artists exist and were searched, but none are playing there in the window. */
    public static SharedScanResult noShows(List<String> sharedArtistNames, String locationLabel) {
        return new SharedScanResult(Outcome.NO_SHOWS, sharedArtistNames, sharedArtistNames.size(),
                0, List.of(), locationLabel);
    }

    /** Shows found. */
    public static SharedScanResult ok(List<String> sharedArtistNames, List<Show> shows, String locationLabel) {
        return new SharedScanResult(Outcome.OK, sharedArtistNames, sharedArtistNames.size(),
                0, shows, locationLabel);
    }
}
```

- [ ] **Step 8: Write `SharedScanService`**

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScanService.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.catalog.SharedArtistFinder;
import com.robsartin.setlistscout.scan.source.ScanQuery;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.GeocodingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs a shared show scan synchronously: the artists two owners both follow, searched at a location
 * neither of them has saved (#163).
 * <p>
 * No {@code ScanJob}, no poller, no persistence. {@link ShowSource#search} is documented query-only,
 * so the durable-job machinery is simply not involved -- results are rendered and discarded. That is
 * affordable because the intersection is bounded by the SMALLER of the two artist lists: the real
 * case today is 4 artists x 2 sources = 8 API calls, not the ~40 the issue originally feared.
 * {@link #maxSharedArtists} is the guard that keeps it that way.
 */
@Service
public class SharedScanService {

    private static final Logger log = LoggerFactory.getLogger(SharedScanService.class);

    /**
     * An allow-list, not a deny-list. {@code BandSiteShowSource} scrapes and falls back to
     * {@code TourPageLlmService} -- slow and billed per artist, which is wrong inside a synchronous
     * request. Allow-listing also means any source added later is excluded by default rather than
     * silently joining this page and making it slow.
     */
    private static final Set<String> ALLOWED_SOURCE_IDS = Set.of("ticketmaster", "bandsintown");

    private final SharedArtistFinder sharedArtistFinder;
    private final GeocodingService geocodingService;
    private final List<ShowSource> sources;
    private final int maxSharedArtists;

    public SharedScanService(SharedArtistFinder sharedArtistFinder,
                             GeocodingService geocodingService,
                             List<ShowSource> sources,
                             AppProperties appProperties) {
        this.sharedArtistFinder = sharedArtistFinder;
        this.geocodingService = geocodingService;
        // Filtered once at construction. A source disabled by @ConditionalOnProperty (#139) simply
        // isn't in the injected list, so this needs no knowledge of which are switched on.
        this.sources = sources.stream().filter(s -> ALLOWED_SOURCE_IDS.contains(s.id())).toList();
        this.maxSharedArtists = appProperties.sharedScan().maxSharedArtists();
    }

    /**
     * @param ownerA the admin running the scan -- shared artist names come back in their spelling
     * @param ownerB the other user
     */
    public SharedScanResult scan(String ownerA, String ownerB, SharedScanRequest request) {
        Optional<GeocodingService.GeoResult> geocoded = resolveLocation(request);
        if (geocoded.isEmpty()) {
            log.atInfo().addKeyValue("ownerA", ownerA).addKeyValue("ownerB", ownerB)
                    .log("shared scan refused: location could not be geocoded");
            return SharedScanResult.geocodingFailed();
        }

        List<String> shared = sharedArtistFinder.findSharedArtistNames(ownerA, ownerB);
        if (shared.isEmpty()) {
            return SharedScanResult.noSharedArtists();
        }
        if (shared.size() > maxSharedArtists) {
            log.atInfo().addKeyValue("ownerA", ownerA).addKeyValue("ownerB", ownerB)
                    .addKeyValue("sharedArtists", shared.size()).addKeyValue("cap", maxSharedArtists)
                    .log("shared scan refused: over cap");
            return SharedScanResult.overCap(shared.size(), maxSharedArtists);
        }

        GeocodingService.GeoResult location = geocoded.get();
        String locationLabel = location.city() + ", " + location.state();
        LocalDateTime windowStart = LocalDateTime.now();
        LocalDateTime windowEnd = windowStart.plusMonths(request.monthsAhead());

        List<Show> found = new ArrayList<>();
        for (String artistName : shared) {
            ScanQuery query = new ScanQuery(
                    artistName,
                    null, // officialSiteUrl: only BandSiteShowSource reads it, and it never runs here
                    request.postalCode(),
                    location.latitude(),
                    location.longitude(),
                    request.radiusMiles(),
                    location.city(),
                    location.state(),
                    windowStart,
                    windowEnd);
            for (ShowSource source : sources) {
                found.addAll(searchQuietly(source, query));
            }
        }

        found.sort(Comparator.comparing(Show::getEventDateTime));
        log.atInfo().addKeyValue("ownerA", ownerA).addKeyValue("ownerB", ownerB)
                .addKeyValue("sharedArtists", shared.size()).addKeyValue("sources", sources.size())
                .addKeyValue("location", locationLabel).addKeyValue("shows", found.size())
                .log("shared scan complete");

        return found.isEmpty()
                ? SharedScanResult.noShows(shared, locationLabel)
                : SharedScanResult.ok(shared, found, locationLabel);
    }

    /**
     * Sources already swallow their own failures and return empty, but this page runs several in a
     * row inside one request: one source throwing something unexpected must degrade that source to
     * empty rather than take down the whole scan. Bandsintown is 403ing for unrelated credential
     * reasons right now, so this path is live, not theoretical.
     */
    private List<Show> searchQuietly(ShowSource source, ScanQuery query) {
        try {
            return source.search(query);
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).addKeyValue("source", source.id())
                    .addKeyValue("artist", query.artistName())
                    .log("shared scan source failed; continuing without it");
            return List.of();
        }
    }

    /** ZIP wins when both are supplied; a blank/absent location is a geocoding failure, not a crash. */
    private Optional<GeocodingService.GeoResult> resolveLocation(SharedScanRequest request) {
        if (request.postalCode() != null && !request.postalCode().isBlank()) {
            return geocodingService.geocode(request.postalCode().trim());
        }
        if (request.city() != null && !request.city().isBlank()
                && request.state() != null && !request.state().isBlank()) {
            return geocodingService.geocodeCity(request.city().trim(), request.state().trim());
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 9: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "*SharedScanServiceTest" --console=plain`
Expected: PASS, 11 tests.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/SharedScanRequest.java \
        src/main/java/com/robsartin/setlistscout/scan/SharedScanResult.java \
        src/main/java/com/robsartin/setlistscout/scan/SharedScanService.java \
        src/main/java/com/robsartin/setlistscout/AppProperties.java \
        src/main/resources/application.yml \
        src/test/java/com/robsartin/setlistscout/service/TestAppProperties.java \
        src/test/java/com/robsartin/setlistscout/scan/SharedScanServiceTest.java
git commit -m "feat: shared scan service -- geocode, cap, drive Ticketmaster + Bandsintown (#163)"
```

---

### Task 4: The page — controller, template, nav

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/SharedScanController.java`
- Create: `src/main/resources/templates/shared-scan.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Test: `src/test/java/com/robsartin/setlistscout/scan/SharedScanControllerTest.java`

**Interfaces:**
- Consumes: `SharedScanService#scan(String, String, SharedScanRequest): SharedScanResult`, `AdminGuard#require()`, `CurrentUser#email()`, `NavModelAdvice`'s existing `isAdmin` / `otherOwnerEmails` model attributes
- Produces: `GET /shared-scan` (full page), `POST /shared-scan` (htmx → `shared-scan :: resultsRegion`, otherwise full page)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/scan/SharedScanControllerTest.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.scan.source.ScanQuery;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #163. Two things are pinned here that nothing else can pin: the admin gate is enforced
 * server-side (not just by hiding the nav link), and each of the four non-success states renders
 * its OWN message rather than a generic "no results".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(SharedScanControllerTest.StubSourceConfig.class)
class SharedScanControllerTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ADMIN = "rob@example.com";
    private static final String OTHER = "david@example.com";

    /** The one artist StubSourceConfig returns a show for -- see its Javadoc. */
    private static final String RENDERING_ARTIST = "The Gaslight Anthem";

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("setlistscout.auth.admin-email", () -> ADMIN);
        registry.add("setlistscout.auth.allowed-emails", () -> ADMIN + "," + OTHER);
        registry.add("setlistscout.shared-scan.max-shared-artists", () -> 3);
        // No real Ticketmaster/Bandsintown credentials in tests: both sources are switched off so
        // this test exercises the page's states without reaching the network. The service's
        // source-fan-out is covered by SharedScanServiceTest instead.
        registry.add("setlistscout.sources.ticketmaster", () -> false);
        registry.add("setlistscout.sources.bandsintown", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    @BeforeEach
    void clean() {
        artistRepository.deleteAll();
    }

    private void seed(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    private String runScan(String signedInAs, String targetOwner, String zip) throws Exception {
        return mockMvc.perform(post("/shared-scan")
                        .with(oidcLogin().idToken(t -> t.claim("email", signedInAs)))
                        .with(csrf())
                        .param("targetOwner", targetOwner)
                        .param("postalCode", zip)
                        .param("radiusMiles", "25")
                        .param("monthsAhead", "3"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("a non-admin is refused by the server, not merely by a hidden nav link")
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/shared-scan")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/shared-scan")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER)))
                        .with(csrf())
                        .param("targetOwner", ADMIN)
                        .param("postalCode", "60601"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the admin gets the page, with the other user selectable")
    void adminSeesThePage() throws Exception {
        String body = mockMvc.perform(get("/shared-scan")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(OTHER);
        // Accessibility: the user picker is a real labelled select, per the spec.
        assertThat(body).contains("<label for=\"shared-scan-target\"");
        assertThat(body).contains("id=\"shared-scan-target\"");
    }

    @Test
    @DisplayName("no shared artists renders its own message")
    void noSharedArtistsMessage() throws Exception {
        seed(ADMIN, "Only Rob", ArtistStatus.SEED);
        seed(OTHER, "Only David", ArtistStatus.SEED);

        assertThat(runScan(ADMIN, OTHER, "60601")).contains("no artists in common");
    }

    @Test
    @DisplayName("over the cap names the actual count and the limit, and is not the empty message")
    void overCapMessage() throws Exception {
        for (String name : new String[] {"A", "B", "C", "D"}) {
            seed(ADMIN, name, ArtistStatus.SEED);
            seed(OTHER, name, ArtistStatus.SEED);
        }

        String body = runScan(ADMIN, OTHER, "60601");
        assertThat(body).contains("4").contains("3");
        assertThat(body).doesNotContain("no artists in common");
    }

    @Test
    @DisplayName("an unusable location renders the geocoding message, distinct from the others")
    void geocodingFailureMessage() throws Exception {
        seed(ADMIN, "Tom Petty", ArtistStatus.SEED);
        seed(OTHER, "Tom petty", ArtistStatus.SEED);

        String body = runScan(ADMIN, OTHER, "");
        assertThat(body).contains("couldn't understand that location");
        assertThat(body).doesNotContain("no artists in common");
    }

    @Test
    @DisplayName("shared artists with no shows says so, and is distinct from having none in common")
    void sharedButNoShowsMessage() throws Exception {
        seed(ADMIN, "Tom Petty", ArtistStatus.SEED);
        seed(OTHER, "Tom petty", ArtistStatus.SEED);

        String body = runScan(ADMIN, OTHER, "60601");
        assertThat(body).contains("1").contains("none of them playing");
        assertThat(body).doesNotContain("no artists in common");
    }

    @Test
    @DisplayName("the intersection reaches the page normalized -- the case-variant pair counts as shared")
    void normalizedIntersectionReachesThePage() throws Exception {
        seed(ADMIN, "Tom Petty", ArtistStatus.SEED);
        seed(OTHER, "Tom petty", ArtistStatus.SEED);

        // If this went through exact-name matching it would render the "no artists in common"
        // message instead -- the same 1-of-4 failure SharedArtistFinderTest guards at its level.
        assertThat(runScan(ADMIN, OTHER, "60601")).doesNotContain("no artists in common");
    }

    /**
     * Supplies a stub source under the "ticketmaster" id so the OK path -- the one that actually
     * renders the table -- is reachable without network access. The real TicketmasterShowSource is
     * switched off by the property above, so there is no duplicate-bean conflict.
     * <p>
     * It returns a show for exactly ONE artist name, {@link #RENDERING_ARTIST}, and nothing for any
     * other. That keeps this stub from contaminating the sibling tests in this class: they seed
     * other artists and must still observe a genuinely empty result. A stub that returned a show
     * for every query would silently turn {@code sharedButNoShowsMessage} green for the wrong
     * reason -- or red.
     */
    @TestConfiguration
    static class StubSourceConfig {
        @Bean
        ShowSource stubTicketmaster() {
            return new ShowSource() {
                @Override public String id() { return "ticketmaster"; }

                @Override public List<Show> search(ScanQuery query) {
                    if (!RENDERING_ARTIST.equals(query.artistName())) {
                        return List.of();
                    }
                    return List.of(new Show(query.artistName(),
                            LocalDateTime.now().plusDays(20), "Metro", "Chicago",
                            new BigDecimal("42.50"), "ticketmaster", "https://example.test/tix"));
                }
            };
        }
    }

    @Test
    @DisplayName("found shows render in a semantic table with column headers")
    void showsRenderInASemanticTable() throws Exception {
        seed(ADMIN, RENDERING_ARTIST, ArtistStatus.SEED);
        seed(OTHER, "the gaslight anthem", ArtistStatus.SEED);

        String body = runScan(ADMIN, OTHER, "60601");

        assertThat(body).contains("<table>");
        assertThat(body).contains("<th scope=\"col\">Date</th>");
        assertThat(body).contains("<th scope=\"col\">Artist</th>");
        assertThat(body).contains("<th scope=\"col\">Venue</th>");
        assertThat(body).contains("Metro");
        assertThat(body).contains(RENDERING_ARTIST);
        // Wide tables scroll inside their own container rather than scrolling the page sideways.
        assertThat(body).contains("class=\"table-scroll\"");
    }

    @Test
    @DisplayName("an htmx submit updates the shared live region out-of-band with innerHTML")
    void htmxSubmitAnnouncesOutOfBand() throws Exception {
        seed(ADMIN, "Tom Petty", ArtistStatus.SEED);
        seed(OTHER, "Tom petty", ArtistStatus.SEED);

        String body = mockMvc.perform(post("/shared-scan")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .header("HX-Request", "true")
                        .param("targetOwner", OTHER)
                        .param("postalCode", "60601")
                        .param("radiusMiles", "25")
                        .param("monthsAhead", "3"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // hx-swap-oob="innerHTML" is load-bearing: the default "true" replaces the node and drops
        // role/aria-live, silently killing every announcement after the first (see CLAUDE.md).
        assertThat(body).contains("id=\"sr-status\"").contains("hx-swap-oob=\"innerHTML\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "*SharedScanControllerTest" --console=plain`
Expected: FAIL — no handler for `/shared-scan` (404 rather than 200/403).

- [ ] **Step 3: Write the controller**

Create `src/main/java/com/robsartin/setlistscout/scan/SharedScanController.java`:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.AdminGuard;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The shared show scan page (#163): the artists two users both follow, at a location neither of
 * them has saved.
 * <p>
 * Lives in {@code scan} because it drives {@code scan.source.ShowSource}; reading {@code catalog}
 * from here is the same established cross-module read {@code ShowController} already does. Admin
 * only -- {@code NavModelAdvice} hides the nav link, and {@link AdminGuard#require()} is what
 * actually enforces it, since a hidden link is not access control.
 * <p>
 * Results are transient by design: nothing is written, so a reload re-runs the scan rather than
 * showing a stale answer, and no owner's {@code show_event} is polluted with shows in a city they
 * do not live in.
 */
@Controller
public class SharedScanController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    private static final int DEFAULT_RADIUS_MILES = 50;
    private static final int DEFAULT_MONTHS_AHEAD = 6;

    private final SharedScanService sharedScanService;
    private final CurrentUser currentUser;
    private final AdminGuard adminGuard;

    public SharedScanController(SharedScanService sharedScanService,
                                 CurrentUser currentUser,
                                 AdminGuard adminGuard) {
        this.sharedScanService = sharedScanService;
        this.currentUser = currentUser;
        this.adminGuard = adminGuard;
    }

    @GetMapping("/shared-scan")
    public String page(Model model) {
        adminGuard.require();
        model.addAttribute("form", new SharedScanRequest(null, null, null,
                DEFAULT_RADIUS_MILES, DEFAULT_MONTHS_AHEAD));
        return "shared-scan";
    }

    @PostMapping("/shared-scan")
    public String run(@RequestParam String targetOwner,
                      @RequestParam(required = false) String postalCode,
                      @RequestParam(required = false) String city,
                      @RequestParam(required = false) String state,
                      @RequestParam(defaultValue = "50") int radiusMiles,
                      @RequestParam(defaultValue = "6") int monthsAhead,
                      @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                      Model model) {
        adminGuard.require();
        SharedScanRequest form = new SharedScanRequest(postalCode, city, state, radiusMiles, monthsAhead);
        SharedScanResult result = sharedScanService.scan(currentUser.email(), targetOwner, form);

        model.addAttribute("form", form);
        model.addAttribute("targetOwner", targetOwner);
        model.addAttribute("result", result);
        model.addAttribute("announcement", announcementFor(result, targetOwner));

        return hxRequest != null ? "shared-scan :: resultsRegion" : "shared-scan";
    }

    /**
     * One sentence per outcome for the shared {@code #sr-status} live region. Deliberately mirrors
     * the visible message rather than saying "done": a screen-reader user gets the same information
     * a sighted user reads, not a bare completion beep.
     */
    private static String announcementFor(SharedScanResult result, String targetOwner) {
        return switch (result.outcome()) {
            case GEOCODING_FAILED -> "Couldn't understand that location. Nothing was searched.";
            case NO_SHARED_ARTISTS -> "You and " + targetOwner + " have no artists in common.";
            case OVER_CAP -> "You share " + result.sharedArtistCount()
                    + " artists, more than the limit of " + result.cap() + ". Nothing was searched.";
            case NO_SHOWS -> "You share " + result.sharedArtistCount()
                    + " artists, none of them playing " + result.locationLabel() + " in that window.";
            case OK -> result.shows().size() + " shared shows found in " + result.locationLabel() + ".";
        };
    }
}
```

- [ ] **Step 4: Write the template**

Create `src/main/resources/templates/shared-scan.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{fragments/layout :: page('Shared Shows', 'shared-scan', ~{::content})}">
<body>
<div th:fragment="content">
    <h1>Shared Shows</h1>
    <p class="page-sub">Artists you and another user both follow, playing somewhere neither of you lives.
        Results are not saved &mdash; run it again whenever you need it.</p>

    <div class="settings">
        <!--/* Both a plain action and th:hx-post, matching shows.html: htmx swaps the results region
               in place (which is what makes the #sr-status announcement below work), and the form
               still submits normally without it. hx-disabled-elt + hx-indicator give the in-flight
               feedback #164 added app-wide -- this scan makes real API calls and is not instant. */-->
        <form th:action="@{/shared-scan}" method="post"
              th:hx-post="@{/shared-scan}" hx-target="#shared-scan-results" hx-swap="outerHTML"
              hx-disabled-elt="find button" hx-indicator="find button">
            <label for="shared-scan-target">Shows shared with</label>
            <select id="shared-scan-target" name="targetOwner" required>
                <option th:each="email : ${otherOwnerEmails}" th:value="${email}" th:text="${email}"
                        th:selected="${email == targetOwner}">user@example.com</option>
            </select>

            <label for="shared-scan-zip">near ZIP</label>
            <input id="shared-scan-zip" type="text" name="postalCode" th:value="${form.postalCode}"
                   size="5" maxlength="5" pattern="[0-9]{5}"/>

            <label for="shared-scan-city">or city</label>
            <input id="shared-scan-city" type="text" name="city" th:value="${form.city}" size="14"/>
            <label for="shared-scan-state">state</label>
            <input id="shared-scan-state" type="text" name="state" th:value="${form.state}"
                   size="2" maxlength="2"/>

            <label for="shared-scan-radius">within</label>
            <input id="shared-scan-radius" type="number" name="radiusMiles" th:value="${form.radiusMiles}"
                   min="1" max="500"/> miles,

            <label for="shared-scan-months">next</label>
            <input id="shared-scan-months" type="number" name="monthsAhead" th:value="${form.monthsAhead}"
                   min="1" max="24"/> months

            <button type="submit">Find shared shows</button>
        </form>
    </div>

    <!--/*
      resultsRegion is the htmx swap target. tabindex="-1" + th:autofocus makes it the focus anchor
      after a run: an outerHTML swap destroys whatever had focus and it would otherwise drop to
      <body>. htmx focuses an [autofocus] element in swapped-in content, so this needs no JS.

      The announcement itself goes out-of-band to #sr-status in fragments/layout.html, which lives
      OUTSIDE every swap target on purpose -- a live region that is itself replaced reads as a brand
      new region to a screen reader and its content is not announced. hx-swap-oob="innerHTML" is
      required for the same reason: the default replaces the node and loses role/aria-live.
    */-->
    <div th:fragment="resultsRegion" id="shared-scan-results" tabindex="-1"
         th:autofocus="${result != null}">

        <span th:if="${announcement != null}" id="sr-status" hx-swap-oob="innerHTML"
              th:text="${announcement}"></span>

        <th:block th:if="${result != null}" th:switch="${result.outcome.name()}">

            <!--/* Plain <p>, no class: matches how shows.html and candidates.html render their
                   empty states. Do NOT invent a `.empty` class -- it has no rule in app.css. */-->
            <p th:case="'GEOCODING_FAILED'">
                We couldn't understand that location, so nothing was searched.
                Enter a 5-digit US ZIP, or a city and its 2-letter state.
            </p>

            <p th:case="'NO_SHARED_ARTISTS'">
                You and <span th:text="${targetOwner}">them</span> have no artists in common,
                so there's nothing to search for.
            </p>

            <p th:case="'OVER_CAP'">
                You share <strong th:text="${result.sharedArtistCount}">0</strong> artists, which is more
                than this page will search at once (limit
                <strong th:text="${result.cap}">25</strong>). Nothing was searched.
            </p>

            <p th:case="'NO_SHOWS'">
                You share <strong th:text="${result.sharedArtistCount}">0</strong> artists, but none of them
                are playing <span th:text="${result.locationLabel}">there</span> in the next
                <span th:text="${form.monthsAhead}">6</span> months.
            </p>

            <th:block th:case="'OK'">
                <p>
                    <strong th:text="${#lists.size(result.shows)}">0</strong> shared shows in
                    <span th:text="${result.locationLabel}">there</span>, from
                    <strong th:text="${result.sharedArtistCount}">0</strong> artists you both follow.
                </p>
                <!--/* table-scroll wrapper: the app's existing pattern for wide tables
                       (shows.html:90) -- the table scrolls inside it instead of the page
                       scrolling sideways on a narrow screen. */-->
                <div class="table-scroll">
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
                    <tr th:each="show : ${result.shows}">
                        <td th:text="${#temporals.format(show.eventDateTime, 'EEE, MMM d yyyy h:mm a')}">Date</td>
                        <td th:text="${show.artistName}">Artist</td>
                        <td th:text="${show.venueName}">Venue</td>
                        <td th:text="${show.venueCity}">City</td>
                        <td th:text="${show.price != null} ? '$' + ${show.price} : '&mdash;'">Price</td>
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
        </th:block>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 5: Add the nav link**

In `src/main/resources/templates/fragments/layout.html`, inside `<nav class="main" aria-label="Primary">`, add this immediately after the existing `Shows` link (the spec places it under Shows). It is admin-only, using the `isAdmin` attribute `NavModelAdvice` already supplies to every page:

```html
            <a th:if="${isAdmin}" th:href="@{/shared-scan}"
               th:attr="aria-current=${navActive == 'shared-scan'} ? 'page' : null">Shared</a>
```

- [ ] **Step 6: Close the two focus gaps in CSS**

The spec makes "visible focus throughout" an acceptance criterion, and this page has two elements the current stylesheet does not cover.

In `src/main/resources/static/css/app.css`, line 70 currently reads:

```css
button:focus-visible, a:focus-visible, input:focus-visible, summary:focus-visible { outline:2px solid var(--primary); outline-offset:2px; }
```

`select` is absent — and this page introduces the app's second `<select>`. Add it:

```css
button:focus-visible, a:focus-visible, input:focus-visible, select:focus-visible, summary:focus-visible { outline:2px solid var(--primary); outline-offset:2px; }
```

Then, beside the existing `#current-group:focus` rule (line 75), add the same treatment for this page's focus anchor. It is focused programmatically via `autofocus` after a swap, so it needs `:focus` rather than `:focus-visible` for exactly the reason the comment above `#current-group` already gives:

```css
/* #163: same programmatic-focus case as #current-group above -- the results region receives
   focus after an htmx swap, and :focus-visible would suppress the ring for a focus the user
   did not initiate with the keyboard. */
#shared-scan-results:focus { outline:2px solid var(--primary); outline-offset:2px; }
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "*SharedScanControllerTest" --console=plain`
Expected: PASS, 9 tests.

- [ ] **Step 8: Run the full gate**

Run:
```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon clean build --console=plain
python3 scripts/check_adrs.py
```
Expected: BUILD SUCCESSFUL, ADR check passes. `ModularityTests` must be green — if it fails, the new `scan → catalog` read is the thing to look at, though `ShowController` already establishes it.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/SharedScanController.java \
        src/main/resources/templates/shared-scan.html \
        src/main/resources/templates/fragments/layout.html \
        src/main/resources/static/css/app.css \
        src/test/java/com/robsartin/setlistscout/scan/SharedScanControllerTest.java
git commit -m "feat: shared shows page -- admin-only, four distinct empty states (#163)"
```

---

## Notes for the implementer

**Deliberately not built** (spec non-goals — do not add them):

- No persistence of results or scan history.
- No band-site / LLM source.
- No non-admin access.
- No "only one of you follows this" near-miss section.
- No change to the existing per-owner scan, job, or Shows behavior.

**Cross-source duplicate shows are not deduplicated.** If Ticketmaster and Bandsintown both return the same concert, it appears twice, adjacent (the list is date-sorted). This is intentional: the spec does not ask for it, `#79` tracks cross-source dedup/merge as its own future work, and Bandsintown is 403ing today so the case is currently hypothetical. Do not add dedup here — raise it if the reviewer disagrees.

**Bandsintown will contribute nothing right now.** It returns 403 for unrelated credential reasons. The page must degrade to Ticketmaster-only without looking broken — that is what `searchQuietly` and the distinct empty states are for.

**CSS classes are reused, not invented.** The template deliberately uses `.page-sub` and `.table-scroll`, both of which already exist in `app.css` (lines 44 and 96), and plain unclassed `<p>` for empty states, matching `shows.html:151`. An earlier draft of this plan used `.lede` and `.empty`; neither has any rule in the stylesheet, which would have shipped two dead classes. If you find yourself adding a class, check `app.css` first.

**`select` was genuinely missing from the focus rule.** Step 6 of Task 4 is not boilerplate — the app's `:focus-visible` rule covers `button`, `a`, `input`, and `summary` but not `select`, and this page introduces the app's second `<select>`. Without that one-word change the spec's "visible focus throughout" is not met on the user picker.

**`List<ShowSource>` injection, and why the stub source is shaped the way it is.** `SharedScanControllerTest` disables the real `ticketmaster` and `bandsintown` sources so the page's states can be exercised without network access, then supplies `StubSourceConfig` under the `ticketmaster` id so the OK path — the only one that renders the table — is still reachable. Two things there are load-bearing:

- The stub returns a show for **exactly one** artist name (`RENDERING_ARTIST`). A stub that answered every query would break `sharedButNoShowsMessage` and `geocodingFailureMessage`, which need a genuinely empty result. Do not "simplify" it into an unconditional return.
- `BandSiteShowSource` stays enabled (`matchIfMissing = true` on `setlistscout.sources.band-site`), which is what `excludesBandSiteSource` relies on at the service level. Constructor-injecting an empty `List<T>` fails in Spring, so leaving at least one real `ShowSource` bean registered also keeps the context bootable. **Do not disable all three sources.**
