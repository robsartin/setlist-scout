# Candidates keyboard focus restoration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After every Candidates-page action, focus lands on a sensible element instead of dropping to `<body>`, so a keyboard user can work down a 157-row group without re-tabbing past the nav and a ~294-entry sidebar after every decision (issue #155).

**Architecture:** Every action swaps `#candidates-app` with `hx-swap="outerHTML"`, destroying the focused element. The server decides which element the new fragment should focus and marks it with `autofocus`; htmx focuses it after the swap. No JavaScript file is added. A new pure record `review/ActionOutcome` carries that decision plus a screen-reader message, which is delivered to a persistent live region via an out-of-band swap.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith, Thymeleaf, vendored htmx 2.0.3, Postgres/Flyway, JUnit 5 + AssertJ + Mockito + MockMvc + Testcontainers, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-16-candidates-focus-restoration-design.md`

> **Superseded in one place — read the spec's Correction block before trusting this document on expand-now.**
> This plan's `ActionOutcome.keepFocus(message)` (Tasks 2 and 4) rests on the assumption that the expand-now
> button survives the swap and htmx's id-based restore re-focuses it. The final review **disproved that by
> measurement**: `hx-disabled-elt` blurs the trigger before the swap, so the restore never fires. What shipped is
> `ActionOutcome.trigger(triggerId, message)`, marking the re-rendered button `autofocus`. The plan text below is
> left as the historical record of what was planned, not what was built.

## Global Constraints

- **Never commit to `main`.** Work happens on branch `155-candidates-focus-restoration` (already created and holding the spec commit). Stop at the PR; the human merges.
- **TDD, always:** write the failing test, *run it and read the failure*, then implement, then run it green, then commit. "Should pass" is not evidence.
- **Green at every step** (Mikado). A task that leaves the build red is not done. Because `actionResult`'s signature changes in Task 3, every one of its call sites is updated in that same task.
- **Gradle cannot *launch* on JDK 25.** Always: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem` — the toolchain forks 25 for compile/test.
- **Docker Desktop must be running** for any `*IntegrationTest` / `@Testcontainers` test.
- **The full build exceeds the 600s tool cap.** Redirect to a log file and poll that log in a separate command; verify the log belongs to *this* run before believing a green line.
- **Owner-scope everything.** Every query and action is scoped by `owner` (email) and it is asserted in tests.
- **Thymeleaf only resolves expressions inside `th:*` attributes.** `hx-get="@{/x}"` ships the literal string.
- **No custom JavaScript file.** The whole point of the chosen mechanism is that the app keeps its zero-JS shape. If a step seems to need JS, it is the wrong step.
- **Exactly one `autofocus` per htmx response; zero on a full page render.** This is the invariant the design is built around.
- **No schema change**, therefore no Flyway migration.

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/java/com/robsartin/setlistscout/review/ActionOutcome.java` | **New.** Pure record: which element the next fragment focuses + the screen-reader message. No Spring, no I/O — the `CandidateGroups` pattern. |
| `src/test/java/com/robsartin/setlistscout/review/ActionOutcomeTest.java` | **New.** Plain JUnit unit test for the above. |
| `src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java` | Ordered candidate-row query. |
| `src/main/java/com/robsartin/setlistscout/review/ReviewController.java` | Computes the outcome per action and puts it on the model. |
| `src/main/java/com/robsartin/setlistscout/review/CandidateGroups.java` | `label(ArtistSource)` widened to package-private so bulk messages can name the relation type. |
| `src/main/resources/templates/candidates.html` | Renders `autofocus` on exactly one element; emits the OOB status update. |
| `src/main/resources/templates/fragments/layout.html` | Hosts the persistent `#sr-status` live region, outside every swap target. |
| `src/main/resources/static/css/app.css` | Focus ring for the group anchor. |
| `src/test/java/.../web/CandidateActionsTest.java` | Integration tests for focus + announcement on actions. |
| `src/test/java/.../web/CandidatesPageRenderTest.java` | Integration tests for render order and the no-autofocus-on-full-page rule. |
| `src/test/java/.../review/ReviewControllerTest.java` | Existing Mockito tests; updated for the renamed query. |
| `src/test/java/.../catalog/CandidateQueryTest.java` | Existing; updated for the renamed query. |
| `CLAUDE.md`, `docs/architecture-introduction.md` | Record the mechanism so a future session doesn't reflexively add JS. |

---

### Task 1: Deterministic A–Z row ordering

Without an `ORDER BY`, "the next row" is undefined and the visible order is whatever Postgres returns. Everything downstream depends on this.

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java:91-93`
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java:93` and `:147`
- Modify: `src/test/java/com/robsartin/setlistscout/review/ReviewControllerTest.java:137,153,175`
- Modify: `src/test/java/com/robsartin/setlistscout/catalog/CandidateQueryTest.java:72`
- Test: `src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java`

**Interfaces:**
- Produces: `ArtistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(String owner, ArtistStatus status, String discoveredVia, ArtistSource source)` returning `List<Artist>` sorted by name ascending. Tasks 2–5 depend on this name and this ordering.

- [ ] **Step 1: Write the failing test**

Add to `CandidatesPageRenderTest` (it already has `savePending`, `mockMvc`, and the `oidcLogin` pattern):

```java
    @Test
    void candidateRowsRenderAlphabeticallyWithinTheirRelationGroup() throws Exception {
        String owner = "candidates-row-order@example.com";
        // Saved deliberately out of order: insertion order is Zeta, Alpha, Mike.
        savePending(owner, "Zeta Reticuli", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(get("/artists/candidates").param("via", TOM_PETTY)
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body.indexOf("Alpha Centauri"))
                .as("rows render A-Z, not in insertion order")
                .isLessThan(body.indexOf("Mike Campbell"));
        assertThat(body.indexOf("Mike Campbell")).isLessThan(body.indexOf("Zeta Reticuli"));
    }
```

- [ ] **Step 2: Run it and read the failure**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*CandidatesPageRenderTest.candidateRowsRenderAlphabeticallyWithinTheirRelationGroup' --console=plain
```

Expected: FAIL — the rows come back in insertion order, so `indexOf("Alpha Centauri")` is greater than `indexOf("Mike Campbell")`. If it *passes*, do not proceed: Postgres happened to return sorted rows by luck, which is exactly the instability this task removes. Re-check the assertion actually ran.

- [ ] **Step 3: Rename the query method**

In `ArtistRepository.java`, replace the declaration at `:91-93`:

```java
    /** All of one group's rows, in the order the page renders them (issue #155). */
    List<Artist> findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
        String owner, ArtistStatus status, String discoveredVia, ArtistSource source);
```

- [ ] **Step 4: Update every call site**

Six call sites, all a pure rename (`ReviewController.java:93` and `:147`; `ReviewControllerTest.java:137`, `:153`, `:175`; `CandidateQueryTest.java:72`):

```bash
grep -rln "findByOwnerAndStatusAndDiscoveredViaAndSource\b" src/ \
  | xargs sed -i '' 's/findByOwnerAndStatusAndDiscoveredViaAndSource(/findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(/g'
grep -rn "findByOwnerAndStatusAndDiscoveredViaAndSource" src/
```

The second `grep` must show only `...OrderByNameAsc` occurrences.

- [ ] **Step 5: Run the affected tests green**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*CandidatesPageRenderTest' --tests '*ReviewControllerTest' --tests '*CandidateQueryTest' --console=plain
```

Expected: PASS, including the new ordering test. `CandidateQueryTest`'s existing owner-isolation assertions now cover the renamed method, so owner scoping is still proven.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java \
        src/main/java/com/robsartin/setlistscout/review/ReviewController.java \
        src/test/java/com/robsartin/setlistscout/review/ReviewControllerTest.java \
        src/test/java/com/robsartin/setlistscout/catalog/CandidateQueryTest.java \
        src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java
git commit -m "#155: order candidate rows A-Z so \"the next row\" is well-defined"
```

---

### Task 2: `ActionOutcome` — the pure focus/message decision

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/review/ActionOutcome.java`
- Test: `src/test/java/com/robsartin/setlistscout/review/ActionOutcomeTest.java`

**Interfaces:**
- Consumes: `ArtistRepository...OrderByNameAsc` ordering from Task 1 (as the caller's list order), `catalog.Artist#getId()`.
- Produces, relied on by Tasks 3–5:
  - `ActionOutcome.Focus` enum: `NONE`, `ANCHOR`, `ROW`
  - `static ActionOutcome afterRow(List<Artist> orderedGroupRows, long actedId, String decision, String message)`
  - `static ActionOutcome anchor(String message)`
  - `static ActionOutcome keepFocus(String message)`
  - `ActionOutcome withoutRowFocus()`
  - `boolean focusesRow(Long id, String decision)` — template predicate
  - `boolean focusesAnchor()` — template predicate
  - accessors `focus()`, `artistId()`, `decision()`, `message()`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/review/ActionOutcomeTest.java`:

```java
package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActionOutcomeTest {

    private static Artist row(String name, long id) {
        Artist artist = new Artist(name, ArtistSource.MEMBER_EXPANSION, ArtistStatus.PENDING_REVIEW,
                "Tom Petty and the Heartbreakers", "note");
        ReflectionTestUtils.setField(artist, "id", id);
        return artist;
    }

    @Test
    @DisplayName("afterRow focuses the row that follows the acted-on one")
    void afterRowPicksTheSuccessor() {
        List<Artist> rows = List.of(row("Alpha", 1L), row("Bravo", 2L), row("Charlie", 3L));

        ActionOutcome outcome = ActionOutcome.afterRow(rows, 2L, "approve", "Approved Bravo.");

        assertThat(outcome.focus()).isEqualTo(ActionOutcome.Focus.ROW);
        assertThat(outcome.artistId()).isEqualTo(3L);
        assertThat(outcome.decision()).isEqualTo("approve");
        assertThat(outcome.message()).isEqualTo("Approved Bravo.");
    }

    @Test
    @DisplayName("afterRow falls back to the group anchor when the acted-on row was last")
    void afterRowOnLastRowFallsBackToAnchor() {
        List<Artist> rows = List.of(row("Alpha", 1L), row("Bravo", 2L));

        ActionOutcome outcome = ActionOutcome.afterRow(rows, 2L, "reject", "Rejected Bravo.");

        assertThat(outcome.focus()).isEqualTo(ActionOutcome.Focus.ANCHOR);
        assertThat(outcome.artistId()).isNull();
        assertThat(outcome.message()).isEqualTo("Rejected Bravo.");
    }

    @Test
    @DisplayName("afterRow falls back to the anchor for a single-row list, an empty list, and an unknown id")
    void afterRowDegradesToAnchor() {
        assertThat(ActionOutcome.afterRow(List.of(row("Alpha", 1L)), 1L, "approve", null).focus())
                .isEqualTo(ActionOutcome.Focus.ANCHOR);
        assertThat(ActionOutcome.afterRow(List.of(), 1L, "approve", null).focus())
                .isEqualTo(ActionOutcome.Focus.ANCHOR);
        assertThat(ActionOutcome.afterRow(List.of(row("Alpha", 1L), row("Bravo", 2L)), 99L, "approve", null).focus())
                .isEqualTo(ActionOutcome.Focus.ANCHOR);
    }

    @Test
    @DisplayName("focusesRow matches only its own id AND its own decision")
    void focusesRowIsExact() {
        ActionOutcome outcome = ActionOutcome.afterRow(
                List.of(row("Alpha", 1L), row("Bravo", 2L)), 1L, "approve", null);

        assertThat(outcome.focusesRow(2L, "approve")).isTrue();
        assertThat(outcome.focusesRow(2L, "reject")).as("the other button in the same row").isFalse();
        assertThat(outcome.focusesRow(1L, "approve")).isFalse();
        assertThat(outcome.focusesAnchor()).isFalse();
    }

    @Test
    @DisplayName("anchor and keepFocus carry no row, and keepFocus focuses nothing at all")
    void anchorAndKeepFocus() {
        assertThat(ActionOutcome.anchor("Approved all 12 Members from Wilco.").focusesAnchor()).isTrue();
        assertThat(ActionOutcome.anchor("m").focusesRow(1L, "approve")).isFalse();

        ActionOutcome kept = ActionOutcome.keepFocus("Expansion requested.");
        assertThat(kept.focus()).isEqualTo(ActionOutcome.Focus.NONE);
        assertThat(kept.focusesAnchor()).isFalse();
        assertThat(kept.focusesRow(1L, "approve")).isFalse();
        assertThat(kept.message()).isEqualTo("Expansion requested.");
    }

    @Test
    @DisplayName("withoutRowFocus downgrades ROW to the anchor and leaves the message and other kinds alone")
    void withoutRowFocusDowngrades() {
        ActionOutcome row = ActionOutcome.afterRow(
                List.of(row("Alpha", 1L), row("Bravo", 2L)), 1L, "approve", "Approved Alpha.");

        ActionOutcome downgraded = row.withoutRowFocus();

        assertThat(downgraded.focusesAnchor()).isTrue();
        assertThat(downgraded.artistId()).isNull();
        assertThat(downgraded.message()).isEqualTo("Approved Alpha.");

        ActionOutcome kept = ActionOutcome.keepFocus("Expansion requested.");
        assertThat(kept.withoutRowFocus()).isEqualTo(kept);
    }
}
```

- [ ] **Step 2: Run it and read the failure**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*ActionOutcomeTest' --console=plain
```

Expected: FAIL — compilation error, `ActionOutcome` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/robsartin/setlistscout/review/ActionOutcome.java`:

```java
package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.Artist;

import java.util.List;

/**
 * What one Candidates-page action tells the client (issue #155): which element the swapped-in
 * fragment should focus, and what the persistent screen-reader status region should announce.
 * <p>
 * Every action on that page swaps {@code #candidates-app} with {@code outerHTML}, which destroys
 * the element holding focus -- the browser then drops focus to {@code <body>}, costing a keyboard
 * user a full re-tab through the nav and the sidebar after every decision. The fix is for the
 * server to name the element to focus and mark it {@code autofocus}: htmx focuses it after the
 * swap (verified against the vendored 2.0.3 build), so no client-side JavaScript is needed.
 * <p>
 * Pure -- no Spring, no I/O -- and unit-testable in isolation, like {@link CandidateGroups}.
 */
public record ActionOutcome(Focus focus, Long artistId, String decision, String message) {

    /**
     * {@code ROW} focuses one candidate's Approve/Reject button; {@code ANCHOR} focuses the current
     * group (or the empty state when nothing is pending); {@code NONE} emits no {@code autofocus}
     * at all, for actions whose own trigger survives the swap and is re-focused by htmx's built-in
     * id-based restore.
     */
    public enum Focus { NONE, ANCHOR, ROW }

    /**
     * Focus the row after {@code actedId} in {@code orderedGroupRows} -- the list as the page
     * renders it, so "after" means visually below. Falls back to the group anchor when the acted-on
     * row has no successor (it was last, the list is empty, or the id isn't there at all).
     * <p>
     * Callers must resolve this BEFORE mutating, while the acted-on row is still in the list.
     */
    public static ActionOutcome afterRow(List<Artist> orderedGroupRows, long actedId, String decision,
                                         String message) {
        Long successor = successorOf(orderedGroupRows, actedId);
        return successor == null ? anchor(message) : new ActionOutcome(Focus.ROW, successor, decision, message);
    }

    /** Focus the current group's anchor -- the auto-advance, bulk-action and navigation landing spot. */
    public static ActionOutcome anchor(String message) {
        return new ActionOutcome(Focus.ANCHOR, null, null, message);
    }

    /** Emit no {@code autofocus}: the triggering element survives the swap and htmx re-focuses it by id. */
    public static ActionOutcome keepFocus(String message) {
        return new ActionOutcome(Focus.NONE, null, null, message);
    }

    /**
     * Downgrades {@code ROW} to the group anchor, for when the chosen successor turns out not to be
     * rendered after all (a concurrent decision in another tab). Keeps the "exactly one autofocus
     * per response" invariant true by construction rather than by convention.
     */
    public ActionOutcome withoutRowFocus() {
        return focus == Focus.ROW ? anchor(message) : this;
    }

    /** Template predicate: is this the one button that should carry {@code autofocus}? */
    public boolean focusesRow(Long id, String decision) {
        return focus == Focus.ROW && artistId.equals(id) && this.decision.equals(decision);
    }

    /** Template predicate: should the group anchor (or the empty state) carry {@code autofocus}? */
    public boolean focusesAnchor() {
        return focus == Focus.ANCHOR;
    }

    private static Long successorOf(List<Artist> orderedGroupRows, long actedId) {
        for (int i = 0; i < orderedGroupRows.size() - 1; i++) {
            Long id = orderedGroupRows.get(i).getId();
            if (id != null && id == actedId) {
                return orderedGroupRows.get(i + 1).getId();
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run the test green**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*ActionOutcomeTest' --console=plain
```

Expected: PASS, all six tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/review/ActionOutcome.java \
        src/test/java/com/robsartin/setlistscout/review/ActionOutcomeTest.java
git commit -m "#155: add ActionOutcome, the pure focus-target + announcement decision"
```

---

### Task 3: Wire per-row focus through the controller and template

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java` (`candidates`, `populateCandidates`, `approve`, `reject`, `actionResult`, and every other `actionResult` call site)
- Modify: `src/main/resources/templates/candidates.html:63-67` (anchor) and `:116-125` (row buttons)
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java`, `src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java`, `src/test/java/com/robsartin/setlistscout/review/ReviewControllerTest.java`

**Interfaces:**
- Consumes: everything `ActionOutcome` produces (Task 2); the ordered query (Task 1).
- Produces, relied on by Tasks 4–5: model attribute `outcome` (an `ActionOutcome`, absent on full-page renders); `private String actionResult(String hxRequest, Model model, String via, ActionOutcome outcome)`; `private String populateCandidates(Model model, String requestedVia, ActionOutcome outcome)`; DOM ids `current-group` (unchanged) and `current-group-title` (new, on the group `<h2>`).

Note: `actionResult`'s signature change breaks all seven call sites at once. This task updates every one of them — bulk and expand-now handlers get `ActionOutcome.anchor(null)` as a deliberate placeholder, refined in Task 4 — so the build stays green.

- [ ] **Step 1: Write the failing tests**

Add to `CandidateActionsTest`:

```java
    @Test
    void approvingARowFocusesTheNextRowsApproveButton() throws Exception {
        String owner = "actions-focus-next@example.com";
        Long alpha = savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Charlie Watts", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", alpha)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Exactly one autofocus, and it is on Bravo's Approve button -- the row that took Alpha's place.
        assertThat(countOccurrences(body, "autofocus")).isEqualTo(1);
        assertThat(autofocusedButtonLabel(body)).isEqualTo("Approve Bravo Company");
    }

    @Test
    void rejectingARowFocusesTheNextRowsRejectButton() throws Exception {
        String owner = "actions-focus-next-reject@example.com";
        Long alpha = savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/reject", alpha)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countOccurrences(body, "autofocus")).isEqualTo(1);
        assertThat(autofocusedButtonLabel(body)).isEqualTo("Reject Bravo Company");
    }

    @Test
    void decidingTheLastRowOfARelationGroupFocusesTheGroupAnchor() throws Exception {
        String owner = "actions-focus-anchor@example.com";
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long bravo = savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", bravo)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Bravo was last in the list, so there is no successor: focus goes to the group anchor.
        assertThat(countOccurrences(body, "autofocus")).isEqualTo(1);
        assertThat(body).containsPattern("id=\"current-group\"[^>]*autofocus");
    }

    @Test
    void autoAdvancingToTheNextGroupFocusesItsAnchor() throws Exception {
        String owner = "actions-advance-focus@example.com";
        for (int i = 1; i <= 3; i++) {
            savePending(owner, "Wilco Member " + i, ArtistSource.MEMBER_EXPANSION, WILCO);
        }
        Long lastTomPettyRow = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", lastTomPettyRow)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Tom Petty had one row; clearing it advances to Wilco. Focus lands on the new group's
        // anchor -- never on a row of a group the user hasn't seen yet.
        assertThat(body).contains(WILCO);
        assertThat(countOccurrences(body, "autofocus")).isEqualTo(1);
        assertThat(body).containsPattern("id=\"current-group\"[^>]*autofocus");
    }

    /** Occurrences of {@code needle} in {@code haystack} -- used to assert the one-autofocus invariant. */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * The aria-label of the single button carrying autofocus. Both attributes live on the same
     * <button> tag, in either order, so the match is anchored on the tag rather than on ordering.
     */
    private static String autofocusedButtonLabel(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<button[^>]*autofocus[^>]*aria-label=\"([^\"]+)\"|<button[^>]*aria-label=\"([^\"]+)\"[^>]*autofocus")
                .matcher(body);
        assertThat(m.find()).as("a <button> carrying autofocus").isTrue();
        return m.group(1) != null ? m.group(1) : m.group(2);
    }
```

And add to `CandidatesPageRenderTest`:

```java
    @Test
    void aFullPageLoadNeverStealsFocus() throws Exception {
        String owner = "candidates-no-autofocus@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // autofocus is for htmx swaps only -- on a normal page load the browser would honour it
        // natively and yank the user into the middle of the list.
        assertThat(body).doesNotContain("autofocus");
    }
```

And add to `ReviewControllerTest` — the concurrency guard is the one piece that can't be reached from an integration test, but its mocked repository makes it trivial:

```java
    @Test
    @DisplayName("focus falls back to the group anchor when the chosen successor is no longer pending")
    void focusDowngradesWhenTheSuccessorVanishes() {
        Artist acted = pending("Alpha Centauri", ArtistSource.MEMBER_EXPANSION, 1L);
        Artist successor = pending("Bravo Company", ArtistSource.MEMBER_EXPANSION, 2L);
        Artist other = pending("Charlie Watts", ArtistSource.MEMBER_EXPANSION, 3L);
        when(artistRepository.findByIdAndOwner(1L, OWNER)).thenReturn(java.util.Optional.of(acted));
        when(artistRepository.countByStatusGroupedByViaAndSource(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(new GroupCountRow("Tom Petty and the Heartbreakers",
                        ArtistSource.MEMBER_EXPANSION, 1)));
        when(artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                OWNER, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers",
                ArtistSource.MEMBER_EXPANSION))
                // First call is the pre-mutation successor lookup: Bravo follows Alpha. Second is
                // the render, by which point another tab has decided Bravo -- so the successor the
                // response would point autofocus at isn't there any more.
                .thenReturn(List.of(acted, successor), List.of(other));

        ConcurrentModel model = new ConcurrentModel();
        controller.approve(1L, "hx", model);

        ActionOutcome outcome = (ActionOutcome) model.asMap().get("outcome");
        assertThat(outcome.focusesAnchor()).as("downgraded from ROW so no autofocus dangles").isTrue();
        assertThat(outcome.focusesRow(2L, "approve")).isFalse();
        assertThat(outcome.message()).isEqualTo("Approved Alpha Centauri.");
    }
```

- [ ] **Step 2: Run them and read the failures**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*CandidateActionsTest' --tests '*CandidatesPageRenderTest' --tests '*ReviewControllerTest' --console=plain
```

Expected: the four new `CandidateActionsTest` tests FAIL (zero `autofocus` occurrences, expected 1) and `focusDowngradesWhenTheSuccessorVanishes` FAILS to compile (`ActionOutcome` isn't on the model yet). `aFullPageLoadNeverStealsFocus` PASSES already — it guards against a regression this very task could introduce, so its value is that it must *still* pass at the end.

- [ ] **Step 3: Thread the outcome through `ReviewController`**

Replace `populateCandidates` (currently `:82-99`) with a version that takes the outcome and publishes it, and add the downgrade guard:

```java
    private String populateCandidates(Model model, String requestedVia, ActionOutcome outcome) {
        String owner = currentUser.email();
        var groups = CandidateGroups.from(
                artistRepository.countByStatusGroupedByViaAndSource(owner, ArtistStatus.PENDING_REVIEW));
        var resolved = CandidateGroups.resolve(groups, requestedVia);
        model.addAttribute("current", resolved.current());
        model.addAttribute("others", resolved.others());
        model.addAttribute("pendingCount", groups.stream().mapToLong(CandidateGroups.BaseArtistGroup::total).sum());
        Map<ArtistSource, List<Artist>> rowsByType = new LinkedHashMap<>();
        if (resolved.current() != null) {
            for (var rg : resolved.current().relationGroups()) {
                rowsByType.put(rg.source(), artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                        owner, ArtistStatus.PENDING_REVIEW, resolved.current().via(), rg.source()));
            }
            model.addAttribute("rowsByType", rowsByType);
        }
        ActionOutcome focusable = focusable(outcome, rowsByType);
        if (focusable != null) {
            model.addAttribute("outcome", focusable);
        }
        return resolved.current() != null ? resolved.current().via() : null;
    }

    /**
     * Downgrades ROW focus to the group anchor when the successor picked before the mutation isn't
     * among the rows about to render -- another tab decided it in the meantime, or the group
     * auto-advanced. Without this the response would carry an {@code autofocus} for an element that
     * isn't there, and focus would silently drop to {@code <body>} again.
     */
    private static ActionOutcome focusable(ActionOutcome outcome, Map<ArtistSource, List<Artist>> rowsByType) {
        if (outcome == null || outcome.focus() != ActionOutcome.Focus.ROW) {
            return outcome;
        }
        boolean stillPending = rowsByType.values().stream()
                .flatMap(List::stream)
                .anyMatch(a -> outcome.artistId().equals(a.getId()));
        return stillPending ? outcome : outcome.withoutRowFocus();
    }
```

Update the `GET` handler (`:59-66`) so only fragment responses carry a focus target:

```java
    @GetMapping("/candidates")
    public String candidates(@RequestParam(required = false) String via,
                             @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                             @RequestHeader(value = HX_HISTORY_RESTORE_REQUEST, required = false) String historyRestore,
                             Model model) {
        boolean fragment = hxRequest != null && historyRestore == null;
        // A fragment swap (sidebar navigation) destroys the focused element like any other action,
        // so it gets the anchor. A full page render -- including a history restore -- must not carry
        // autofocus at all: the browser would honour it natively on load.
        populateCandidates(model, via, fragment ? ActionOutcome.anchor(null) : null);
        return fragment ? "candidates :: candidatesApp" : "candidates";
    }
```

Replace `approve` and `reject` (`:109-127`) with thin wrappers over one shared decision path:

```java
    /** Approve one candidate. Owner-scoped via changeStatus (no-op if this owner doesn't own {@code id}). */
    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          Model model) {
        return decide(id, ArtistStatus.APPROVED, "approve", "Approved", hxRequest, model);
    }

    /** Reject one candidate. Owner-scoped via changeStatus (no-op if this owner doesn't own {@code id}). */
    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                         Model model) {
        return decide(id, ArtistStatus.REJECTED, "reject", "Rejected", hxRequest, model);
    }

    /**
     * The shared per-row decision path (issue #155). Resolves the focus successor BEFORE mutating,
     * while the acted-on row is still in the list: doing it afterwards would mean comparing names in
     * Java against an order Postgres produced, and the two disagree on case and punctuation.
     */
    private String decide(Long id, ArtistStatus status, String decision, String verb, String hxRequest, Model model) {
        String owner = currentUser.email();
        Artist acted = artistRepository.findByIdAndOwner(id, owner).orElse(null);
        String via = acted != null ? acted.getDiscoveredVia() : null;
        ActionOutcome outcome = acted == null
                ? ActionOutcome.anchor(null)
                : ActionOutcome.afterRow(
                        artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                                owner, ArtistStatus.PENDING_REVIEW, via, acted.getSource()),
                        id, decision, verb + " " + acted.getName() + ".");
        activationService.changeStatus(id, owner, status);
        return actionResult(hxRequest, model, via, outcome);
    }
```

Change `actionResult`'s signature and its `populateCandidates` call:

```java
    private String actionResult(String hxRequest, Model model, String via, ActionOutcome outcome) {
        String resolvedVia = populateCandidates(model, via, outcome);
```

Then fix the remaining four call sites so it compiles — `reviewGroup` (both `return actionResult(...)` statements), `approveAllPending`, `rejectAllPending`, `expandNow`, `adminExpandNow` — by passing `ActionOutcome.anchor(null)` as the last argument. Task 4 replaces those placeholders with real outcomes and messages.

- [ ] **Step 4: Render the focus target in the template**

In `candidates.html`, the group anchor (`:63-67`) becomes:

```html
        <section class="artist-group card" id="current-group" tabindex="-1"
                 aria-labelledby="current-group-title"
                 th:autofocus="${outcome != null and outcome.focusesAnchor()}">
            <div class="artist-group-header" aria-live="polite">
                <h2 id="current-group-title" th:text="${current.via}">Base artist</h2>
```

and the two per-row buttons (`:119` and `:124`) gain one attribute each:

```html
                            <button type="submit" class="btn-good btn-sm"
                                    th:autofocus="${outcome != null and outcome.focusesRow(a.id, 'approve')}"
                                    th:aria-label="'Approve ' + ${a.name}">Approve</button>
```

```html
                            <button type="submit" class="btn-bad btn-sm"
                                    th:autofocus="${outcome != null and outcome.focusesRow(a.id, 'reject')}"
                                    th:aria-label="'Reject ' + ${a.name}">Reject</button>
```

`th:autofocus` is Thymeleaf's fixed-value boolean-attribute processor: true renders `autofocus="autofocus"`, false omits the attribute entirely. If the Step 5 run shows the attribute missing or always present, swap that one attribute for the equivalent explicit form and re-run — do not reach for JavaScript:

```html
th:attr="autofocus=${outcome != null and outcome.focusesAnchor()} ? 'autofocus' : null"
```

- [ ] **Step 5: Run the tests green**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*CandidateActionsTest' --tests '*CandidatesPageRenderTest' --tests '*ReviewControllerTest' --console=plain
```

Expected: PASS, including `aFullPageLoadNeverStealsFocus` (proving the new attributes stay off full-page renders) and every pre-existing test in those classes.

- [ ] **Step 6: Give the anchor a visible focus ring**

The group anchor is a full-width `<section>` that until now only received focus via the skip link; this change makes focusing it routine, so it should use the app's own focus treatment rather than the raw UA outline. Append to `app.css` next to the existing `:focus-visible` rule at `:70`:

```css
/* #155: the group anchor is focused programmatically after every swap (and by the skip link), so
   it gets the same ring as interactive elements. :focus, not :focus-visible -- programmatic focus
   on a tabindex="-1" element is not reliably "visible" across browsers, and a keyboard user being
   moved somewhere new should always see where they landed. */
#current-group:focus { outline:2px solid var(--primary); outline-offset:2px; }
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/review/ReviewController.java \
        src/main/resources/templates/candidates.html \
        src/main/resources/static/css/app.css \
        src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java \
        src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java \
        src/test/java/com/robsartin/setlistscout/review/ReviewControllerTest.java
git commit -m "#155: focus the next row after a per-row approve/reject"
```

---

### Task 4: Focus targets for the bulk, navigation and expansion actions

Replaces the `ActionOutcome.anchor(null)` placeholders from Task 3 with real outcomes, and makes the empty state focusable.

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java` (`reviewGroup`, `approveAllPending`, `rejectAllPending`, `expandNow`, `adminExpandNow`)
- Modify: `src/main/java/com/robsartin/setlistscout/review/CandidateGroups.java:78` (`label` visibility)
- Modify: `src/main/resources/templates/candidates.html:36` (empty state) and `:149-167` (expansion buttons)
- Test: `src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java`

**Interfaces:**
- Consumes: `ActionOutcome.anchor`, `ActionOutcome.keepFocus` (Task 2); `outcome` model attribute and `actionResult(hxRequest, model, via, outcome)` (Task 3).
- Produces: `CandidateGroups.label(ArtistSource)` as package-private; DOM ids `expand-now` and `admin-expand-now`.

- [ ] **Step 1: Write the failing tests**

Add to `CandidateActionsTest` (reusing `countOccurrences` and the helpers added in Task 3):

```java
    @Test
    void aRelationTypeBulkActionFocusesTheGroupAnchor() throws Exception {
        String owner = "actions-bulk-anchor@example.com";
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", ArtistSource.MEMBER_EXPANSION.name())
                        .param("decision", "approve")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countOccurrences(body, "autofocus")).isEqualTo(1);
        assertThat(body).containsPattern("id=\"current-group\"[^>]*autofocus");
    }

    @Test
    void clearingEverythingFocusesTheEmptyState() throws Exception {
        String owner = "actions-empty-focus@example.com";
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);

        String body = mockMvc.perform(post("/artists/reject-all-pending")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // No group left, so the anchor doesn't render -- the empty-state paragraph takes the focus.
        assertThat(countOccurrences(body, "autofocus")).isEqualTo(1);
        assertThat(body).containsPattern("<p[^>]*tabindex=\"-1\"[^>]*autofocus|<p[^>]*autofocus[^>]*tabindex=\"-1\"");
        assertThat(body).contains("Nothing pending");
    }

    @Test
    void sidebarNavigationFocusesTheNewGroupsAnchor() throws Exception {
        String owner = "actions-sidebar-focus@example.com";
        for (int i = 1; i <= 3; i++) {
            savePending(owner, "Wilco Member " + i, ArtistSource.MEMBER_EXPANSION, WILCO);
        }
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/artists/candidates").param("via", TOM_PETTY)
                        .header("HX-Request", "true")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countOccurrences(body, "autofocus")).isEqualTo(1);
        assertThat(body).containsPattern("id=\"current-group\"[^>]*autofocus");
    }

    @Test
    void expandNowLeavesFocusOnItsOwnButton() throws Exception {
        String owner = "actions-expand-focus@example.com";
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/expand-now")
                        .param("via", TOM_PETTY)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The button survives the swap, so htmx's built-in id-based restore keeps focus on it --
        // moving focus away with autofocus would be worse than doing nothing.
        assertThat(body).doesNotContain("autofocus");
        assertThat(body).contains("id=\"expand-now\"");
    }
```

- [ ] **Step 2: Run them and read the failures**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*CandidateActionsTest' --console=plain
```

Expected: `clearingEverythingFocusesTheEmptyState` FAILS (0 autofocus — the empty state has no `tabindex` or `autofocus` yet) and `expandNowLeavesFocusOnItsOwnButton` FAILS (an `autofocus` is present from Task 3's placeholder, and there is no `id="expand-now"`). The other two may already pass on Task 3's placeholders; they are the regression net that keeps them passing once the placeholders are replaced.

- [ ] **Step 3: Widen `CandidateGroups.label` so bulk messages can name the relation type**

In `CandidateGroups.java`, drop `private` from the label helper (`ReviewController` is in the same package):

```java
    /** Display name for a relation type -- also used for bulk-action announcements (issue #155). */
    static String label(ArtistSource source) {
```

- [ ] **Step 4: Replace the placeholder outcomes in `ReviewController`**

`reviewGroup` — count the rows it actually changes and name the relation type:

```java
        List<Artist> rows = artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                currentUser.email(), ArtistStatus.PENDING_REVIEW, via, type);
        for (Artist a : rows) {
            activationService.changeStatus(a.getId(), currentUser.email(), status);
        }
        String verb = status == ArtistStatus.APPROVED ? "Approved" : "Rejected";
        return actionResult(hxRequest, model, via, ActionOutcome.anchor(
                verb + " " + rows.size() + " " + CandidateGroups.label(type) + " from " + via + "."));
```

Its malformed-decision early return keeps `ActionOutcome.anchor(null)` — nothing happened, so there is nothing to announce.

`approveAllPending` and `rejectAllPending` — same shape, whole-owner:

```java
        List<Artist> pending = artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW);
        for (Artist a : pending) {
            activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.APPROVED);
        }
        return actionResult(hxRequest, model, null,
                ActionOutcome.anchor("Approved all " + pending.size() + " remaining candidates."));
```

(and the `REJECTED` / `"Rejected all "` counterpart in `rejectAllPending`).

`expandNow` and `adminExpandNow` — the trigger survives the swap, so announce without moving focus:

```java
        return actionResult(hxRequest, model, via, ActionOutcome.keepFocus("Expansion requested."));
```

```java
        return actionResult(hxRequest, model, null,
                ActionOutcome.keepFocus("Expansion requested for " + targetOwner + "."));
```

- [ ] **Step 5: Make the empty state focusable and give the expansion buttons stable ids**

In `candidates.html`, the empty state (`:36`):

```html
    <p th:if="${current == null}" tabindex="-1"
       th:autofocus="${outcome != null and outcome.focusesAnchor()}">Nothing pending. Run expansion to find more.</p>
```

The "Run expansion now" button (`:152`) and the admin one (`:166`) each gain an id, so htmx's built-in id-based focus restore can find them in the swapped-in content:

```html
                <button type="submit" id="expand-now">Run expansion now</button>
```

```html
            <button type="submit" id="admin-expand-now">Run expansion for user</button>
```

- [ ] **Step 6: Run the tests green**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*CandidateActionsTest' --tests '*CandidatesPageRenderTest' --tests '*ReviewControllerTest' --console=plain
```

Expected: PASS, all four new tests plus everything from Task 3.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/review/ReviewController.java \
        src/main/java/com/robsartin/setlistscout/review/CandidateGroups.java \
        src/main/resources/templates/candidates.html \
        src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java
git commit -m "#155: focus targets for the bulk, navigation and expansion actions"
```

---

### Task 5: Announce actions through a persistent live region

**Files:**
- Modify: `src/main/resources/templates/fragments/layout.html:10-11`
- Modify: `src/main/resources/templates/candidates.html:34-36` (fragment head) and `:64` (remove `aria-live`)
- Test: `src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java`, `src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java`

**Interfaces:**
- Consumes: `outcome.message()` (Task 2), the `outcome` model attribute (Task 3), the messages set in Task 4.
- Produces: DOM id `sr-status`, a permanent `role="status"` region in the layout — reusable by #154 and by any other page later.

- [ ] **Step 1: Write the failing tests**

Add to `CandidateActionsTest`:

```java
    @Test
    void anActionAnnouncesWhatHappenedOutOfBand() throws Exception {
        String owner = "actions-announce@example.com";
        Long alpha = savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", alpha)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // innerHTML, not the default outerHTML: the live-region NODE has to survive, or a screen
        // reader treats each swap as a brand-new region and announces nothing.
        assertThat(body).containsPattern("id=\"sr-status\"[^>]*hx-swap-oob=\"innerHTML\"|hx-swap-oob=\"innerHTML\"[^>]*id=\"sr-status\"");
        assertThat(body).contains("Approved Alpha Centauri.");
        assertThat(body).contains("1 left in " + TOM_PETTY);
    }

    @Test
    void clearingTheQueueAnnouncesThatNothingIsLeft() throws Exception {
        String owner = "actions-announce-empty@example.com";
        Long only = savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/reject", only)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Rejected Alpha Centauri.");
        assertThat(body).contains("Nothing left to review.");
    }

    @Test
    void aRelationTypeBulkActionAnnouncesTheCountAndRelationType() throws Exception {
        String owner = "actions-announce-bulk@example.com";
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", ArtistSource.MEMBER_EXPANSION.name())
                        .param("decision", "reject")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Rejected 2 Members from " + TOM_PETTY + ".");
    }
```

And add to `CandidatesPageRenderTest`:

```java
    @Test
    void theLiveRegionIsPermanentAndOutsideTheSwapTarget() throws Exception {
        String owner = "candidates-live-region@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The region ships empty with every page, from the shared layout.
        assertThat(body).contains("id=\"sr-status\"");
        assertThat(body).contains("role=\"status\"");
        // ...and a full page render never carries an out-of-band update.
        assertThat(body).doesNotContain("hx-swap-oob");
        // The old inert live region -- inside the swap target, so replaced wholesale every time --
        // is gone, and with it the re-announcement of the group title and count on every action.
        assertThat(body).doesNotContain("<div class=\"artist-group-header\" aria-live=\"polite\">");
    }
```

- [ ] **Step 2: Run them and read the failures**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*CandidateActionsTest' --tests '*CandidatesPageRenderTest' --console=plain
```

Expected: the three announcement tests FAIL (no `sr-status` anywhere), and `theLiveRegionIsPermanentAndOutsideTheSwapTarget` FAILS on the missing `id="sr-status"`.

- [ ] **Step 3: Add the permanent live region to the shared layout**

In `layout.html`, immediately after `<body>` (`:10`), before `<header class="topbar">`:

```html
<!--/* #155: the one live region for the whole app. It lives OUTSIDE <main>, and therefore outside
       every htmx swap target, on purpose: a region that is itself replaced by a swap is a brand-new
       region as far as a screen reader is concerned, and its arriving content is generally not
       announced. Actions update it out-of-band with hx-swap-oob="innerHTML" so this node persists
       and only its contents change. Ships empty on every page load. */-->
<p id="sr-status" role="status" aria-live="polite" class="visually-hidden"></p>
```

- [ ] **Step 4: Emit the out-of-band update and delete the old inert region**

In `candidates.html`, add the OOB paragraph as the first child of the `candidatesApp` fragment, immediately after the opening `<div th:fragment="candidatesApp" ...>` tag (`:35`):

```html
    <!--/* #155: out-of-band update for the layout's permanent #sr-status region. htmx extracts
           this before the primary swap, so it never lands inside #candidates-app -- and because it
           is emitted only when there's a message, a full page render can't produce a duplicate id.
           Nested OOB is fine here: the vendored build defaults allowNestedOobSwaps to true. The
           two halves are "what happened" (from the controller) and "where you are now" (from the
           already-resolved model), which is why no count has to be threaded through. */-->
    <p id="sr-status" hx-swap-oob="innerHTML" class="visually-hidden"
       th:if="${outcome != null and outcome.message != null}">
        <span th:text="${outcome.message}">Approved Mike Campbell.</span>
        <span th:if="${current != null}"
              th:text="' ' + ${current.total} + ' left in ' + ${current.via} + '.'"> </span>
        <span th:if="${current == null}"> Nothing left to review.</span>
    </p>
```

Then strip `aria-live="polite"` from the group header (`:64`), leaving:

```html
            <div class="artist-group-header">
```

- [ ] **Step 5: Run the tests green**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests '*CandidateActionsTest' --tests '*CandidatesPageRenderTest' --console=plain
```

Expected: PASS. Watch the one-`autofocus` assertions from Tasks 3–4 in particular: the OOB paragraph must not have introduced another one.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/fragments/layout.html \
        src/main/resources/templates/candidates.html \
        src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java \
        src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java
git commit -m "#155: announce actions through a persistent live region, updated out of band"
```

---

### Task 6: Document the mechanism, run the full gate, walk it by keyboard

**Files:**
- Modify: `CLAUDE.md` (the "Gotchas that have wasted real time" section)
- Modify: `docs/architecture-introduction.md:285-291` (the htmx paragraph)

**Interfaces:**
- Consumes: everything above. Produces no code.

- [ ] **Step 1: Record the mechanism in `CLAUDE.md`**

The point is that a future session reaches for `autofocus` rather than reflexively adding a JS file. Add to the gotchas list, after the existing `th:hx-get` bullet:

```markdown
- **htmx focus after a swap**: an `outerHTML` swap destroys the focused element and focus drops to
  `<body>`. The fix is server-side and needs no JavaScript — htmx focuses an `[autofocus]` element in
  swapped-in content (after its own id-based restore, so `autofocus` wins), and `review.ActionOutcome`
  decides which single element gets it. **The app ships no custom JS; keep it that way.**
```

- [ ] **Step 2: Record it in the architecture introduction**

Append to the **htmx** paragraph at `docs/architecture-introduction.md:285-291`:

```markdown
Focus is managed server-side: because every action swaps a whole region with
`outerHTML`, the focused element is destroyed and focus would drop to `<body>`.
`review.ActionOutcome` picks exactly one element per response — the next row's
same-decision button, the current group's anchor, or nothing when the trigger
itself survives — and the template marks it `autofocus`, which htmx honours in
swapped-in content. The app deliberately ships **no custom JavaScript**. The same
response carries an `hx-swap-oob="innerHTML"` update for `#sr-status`, the one
permanent `role="status"` region (in the layout, outside every swap target).
```

- [ ] **Step 3: Run the full gate**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon clean build --console=plain > /tmp/gate-155.log 2>&1
```

This runs ~11 minutes and will be backgrounded past the tool cap. Poll the log in a *separate* command, and confirm the log is from this run before believing it:

```bash
tail -30 /tmp/gate-155.log && grep -c "BUILD SUCCESSFUL" /tmp/gate-155.log
```

Expected: `BUILD SUCCESSFUL`. Hikari / `eventPublicationRegistry` shutdown WARNs at the end are harmless.

- [ ] **Step 4: Run the ADR check**

```bash
python3 scripts/check_adrs.py
```

Expected: passes. This change adds no ADR (the chosen mechanism preserves the app's existing shape rather than changing it).

- [ ] **Step 5: Walk it by keyboard**

Automated tests prove the app emits exactly one `autofocus` on the right element; the browser spike proved htmx honours it. The seam between them is only covered here. Run the app, open Candidates on a group with several rows, and with **Tab and Enter only** (no mouse):

1. Approve a middle row → focus is on the *next* row's Approve button; the list did not jump to the top.
2. Reject a row → focus is on the next row's Reject button.
3. Decide the last row of a relation group → focus is on the group heading, with a visible ring.
4. Clear a whole group → focus is on the new group's heading and the group name is announced.
5. "Run expansion now" → focus stays on that button.
6. With VoiceOver on (Cmd-F5): each decision announces "Approved <name>. N left in <group>." once — not the whole list.
7. Reload the page normally → focus is at the top of the document, *not* pulled into the list.

Record the result in the PR description, including anything that behaved differently from the above.

- [ ] **Step 6: Commit and open the PR**

```bash
git add CLAUDE.md docs/architecture-introduction.md
git commit -m "#155: document server-driven focus restoration and the shared live region"
```

Then push and open the PR against `main` referencing #155, per CLAUDE.md's workflow (`gh pr create`, falling back to `gh api -X POST repos/<owner>/<repo>/pulls --input -` if GraphQL is rate-limited). Stop at the PR — the human merges.

---

## Follow-ups (do NOT do in this branch)

- **#154** — the nav badge is still stale. It now needs only an id on the badge `<span>` and one more OOB element in the same response path this branch built.
- **`shows.html:45`** — `aria-live` on `#shows-region`, which is itself the swap target: the same inert-live-region defect fixed here. Worth its own issue now that `#sr-status` exists.
