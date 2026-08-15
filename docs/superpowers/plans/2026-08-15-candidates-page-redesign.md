# Candidates Page Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Candidates page's render-all-295-groups accordion with focused single-group review: one group's full content at a time (biggest-first), a lightweight sidebar to jump elsewhere, and auto-advance when a group clears.

**Architecture:** `ReviewController` gains one shared response-building path used by the page's `GET` and every status-changing `POST` on it: resolve the "current" group from a `via` hint (falls back to biggest-first if that group has no pending rows left — this single fallback rule *is* the auto-advance mechanism, with no separate empty-check needed), populate the model, render either the full page or just the `candidatesApp` fragment. `CandidateGroups` (existing pure assembler, already unit-tested) gains the resolution logic as a second pure static method, so it stays testable without Spring.

**Tech Stack:** Spring Boot MVC (`ReviewController`), Thymeleaf + vendored htmx (`candidates.html`), existing `ArtistRepository`/`ArtistActivationService` (catalog module, no changes needed there beyond deleting one now-unused repository overload).

## Global Constraints

- Owner-scope every query and action; assert it in tests (per CLAUDE.md).
- Status changes go through `catalog.ArtistActivationService.changeStatus` — never a direct repository save.
- TDD: failing test → implement → green → commit, every task.
- Full gate before any task is done: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon clean build --console=plain` (redirect to a log file, poll separately — exceeds the 10-minute tool timeout) then `python3 scripts/check_adrs.py`. `PollerFlowTest.expandHappyPath` failing alone is a known pre-existing occasional flake, not this work's problem — anything else is.
- Docker Desktop must be running (Testcontainers).
- Accessibility: semantic HTML, visible focus states, `aria-current`/`aria-label` on interactive elements — match the patterns already in `candidates.html`/`app.css`, don't invent new ones.
- Do not push or open a PR until told to — this plan's execution stops after the final task's gate-green confirmation, matching every other piece of work in this session.

---

## File Structure

- **Modify** `src/main/java/com/robsartin/setlistscout/review/CandidateGroups.java` — add `ResolvedGroups` record + `resolve(List<BaseArtistGroup>, String)` static method (Task 1).
- **Modify** `src/test/java/com/robsartin/setlistscout/review/CandidateGroupsTest.java` — tests for `resolve` (Task 1).
- **Modify** `src/main/java/com/robsartin/setlistscout/review/ReviewController.java` — new shared `populateCandidates`/`actionResult` helpers; `candidates` GET rewritten (Task 2); `approve`/`reject` rewired (Task 3); `reviewGroup`/`approveAllPending`/`rejectAllPending`/`expandNow` rewired (Task 4); `candidateRows` endpoint deleted (Task 5).
- **Modify** `src/main/resources/templates/candidates.html` — new `candidatesApp` fragment replacing the accordion body (Task 2); `groupRows`/`rowDone` fragments deleted (Task 5).
- **Modify** `src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java` — delete the paginated `findByOwnerAndStatusAndDiscoveredViaAndSource` overload + now-unused `Pageable` import (Task 5).
- **Modify** `src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java` — replaced with tests for the new page shape (Task 2, capstone in Task 5).
- **Modify** `src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java` — replaced with tests for the new auto-advance response shape (Tasks 3–4, capstone in Task 5).

---

### Task 1: `CandidateGroups.resolve` — pure current-group resolution

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/review/CandidateGroups.java`
- Test: `src/test/java/com/robsartin/setlistscout/review/CandidateGroupsTest.java`

**Interfaces:**
- Consumes: `CandidateGroups.BaseArtistGroup` (existing, has `via()`/`total()`/`relationGroups()`).
- Produces: `CandidateGroups.ResolvedGroups(BaseArtistGroup current, List<BaseArtistGroup> others)` — `current` is `null` only when `groups` is empty. Used by Task 2's controller code.

- [ ] **Step 1: Write the failing tests**

Add to `CandidateGroupsTest.java` (existing file, existing `Row` test record and imports already in place):

```java
    @Test
    void resolveReturnsTheRequestedGroupAsCurrentAndEverythingElseAsOthers() {
        List<CandidateGroupCount> counts = List.of(
                new Row("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION, 5),
                new Row("Wilco", ArtistSource.MEMBER_EXPANSION, 1));
        List<CandidateGroups.BaseArtistGroup> groups = CandidateGroups.from(counts);

        CandidateGroups.ResolvedGroups resolved = CandidateGroups.resolve(groups, "Wilco");

        assertThat(resolved.current().via()).isEqualTo("Wilco");
        assertThat(resolved.others()).extracting(CandidateGroups.BaseArtistGroup::via)
                .containsExactly("Tom Petty and the Heartbreakers");
    }

    @Test
    void resolveFallsBackToBiggestFirstWhenRequestedViaIsNull() {
        List<CandidateGroupCount> counts = List.of(
                new Row("Wilco", ArtistSource.MEMBER_EXPANSION, 1),
                new Row("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION, 5));
        List<CandidateGroups.BaseArtistGroup> groups = CandidateGroups.from(counts);

        CandidateGroups.ResolvedGroups resolved = CandidateGroups.resolve(groups, null);

        assertThat(resolved.current().via()).isEqualTo("Tom Petty and the Heartbreakers");
        assertThat(resolved.others()).extracting(CandidateGroups.BaseArtistGroup::via)
                .containsExactly("Wilco");
    }

    @Test
    void resolveFallsBackToBiggestFirstWhenRequestedViaHasNoPendingRowsLeft() {
        // This IS the auto-advance mechanism: after a group empties, it's no longer in `groups`
        // (freshly re-queried), so asking to resolve it again falls back to whatever's biggest now.
        List<CandidateGroupCount> counts = List.of(
                new Row("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION, 5));
        List<CandidateGroups.BaseArtistGroup> groups = CandidateGroups.from(counts);

        CandidateGroups.ResolvedGroups resolved = CandidateGroups.resolve(groups, "Wilco");

        assertThat(resolved.current().via()).isEqualTo("Tom Petty and the Heartbreakers");
        assertThat(resolved.others()).isEmpty();
    }

    @Test
    void resolveOnEmptyGroupsReturnsNullCurrentAndEmptyOthers() {
        CandidateGroups.ResolvedGroups resolved = CandidateGroups.resolve(List.of(), "anything");

        assertThat(resolved.current()).isNull();
        assertThat(resolved.others()).isEmpty();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.review.CandidateGroupsTest" --console=plain`
Expected: FAIL — `cannot find symbol: method resolve` / `cannot find symbol: class ResolvedGroups`.

- [ ] **Step 3: Implement `resolve`**

In `CandidateGroups.java`, add after the existing `from` method (before the private helper methods):

```java
    /**
     * Picks the "current" group to focus review on: {@code requestedVia} if it's still present in
     * {@code groups} (has pending rows), otherwise the biggest group ({@code groups} is already
     * sorted total-descending by {@link #from}). A {@code null} or stale/cleared {@code
     * requestedVia} falling through to that same biggest-first pick is the whole auto-advance
     * mechanism (issue #148) -- callers don't need a separate "is this group still there" check,
     * just re-resolve against a freshly re-queried {@code groups} after any status change.
     */
    public static ResolvedGroups resolve(List<BaseArtistGroup> groups, String requestedVia) {
        if (groups.isEmpty()) {
            return new ResolvedGroups(null, List.of());
        }
        BaseArtistGroup current = groups.stream()
                .filter(g -> g.via().equals(requestedVia))
                .findFirst()
                .orElse(groups.get(0));
        List<BaseArtistGroup> others = groups.stream().filter(g -> g != current).toList();
        return new ResolvedGroups(current, others);
    }
```

Add the record next to the existing `BaseArtistGroup`/`RelationGroup` records at the bottom of the class:

```java
    /** {@code current} is null only when there are no pending groups at all. */
    public record ResolvedGroups(BaseArtistGroup current, List<BaseArtistGroup> others) {
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.review.CandidateGroupsTest" --console=plain`
Expected: PASS, all 8 tests (4 existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/review/CandidateGroups.java src/test/java/com/robsartin/setlistscout/review/CandidateGroupsTest.java
git commit -m "#148: CandidateGroups.resolve -- current-group + auto-advance fallback logic"
```

---

### Task 2: Redesigned page — one group's full content + sidebar

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java`
- Modify: `src/main/resources/templates/candidates.html`
- Modify: `src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java` (replace entirely — the whole page shape changed)

**Interfaces:**
- Consumes: `CandidateGroups.resolve` (Task 1), existing `ArtistRepository.countByStatusGroupedByViaAndSource`, existing unpaginated `ArtistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource(owner, status, via, source)`.
- Produces: `ReviewController.populateCandidates(Model, String requestedVia)` returning the resolved current group's `via` (or `null`) — Task 3 and 4 reuse this exact method.

- [ ] **Step 1: Write the failing render tests**

Replace the full contents of `src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java`:

```java
package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real /artists/candidates focused-single-group page (issue #148) against a booted
 * context + Postgres, signed in as a test user, and checks multi-tenant isolation. Each test uses
 * a distinct owner so saved data can't leak between methods (no per-test rollback). Runs in CI
 * (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CandidatesPageRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    private static final String TOM_PETTY = "Tom Petty and the Heartbreakers";
    private static final String WILCO = "Wilco";

    private void savePending(String owner, String name, ArtistSource source, String discoveredVia) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, "note for " + name);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    /** Wilco (30 rows, the biggest group) and Tom Petty (2 rows, Members + Similar) for the owner. */
    private void seedTwoGroups(String owner) {
        for (int i = 1; i <= 30; i++) {
            savePending(owner, "Wilco Member " + i, ArtistSource.MEMBER_EXPANSION, WILCO);
        }
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);
    }

    @Test
    void landingWithNoViaShowsTheBiggestGroupInFullAndSidebarsTheRest() throws Exception {
        String owner = "candidates-land-biggest@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("/css/app.css");
        assertThat(body).contains(">Shows<");

        // Wilco is biggest (30 > 2) -- every one of its rows renders directly, no pagination.
        assertThat(body).contains(WILCO);
        assertThat(body).contains("Wilco Member 1<");
        assertThat(body).contains("Wilco Member 30<");
        assertThat(body).doesNotContain("Show more");

        // Tom Petty is in the sidebar (name + count), not expanded -- its rows are NOT on the page.
        assertThat(body).contains(TOM_PETTY);
        assertThat(body).doesNotContain("Mike Campbell");
        assertThat(body).doesNotContain("Jackson Browne");
    }

    @Test
    void viaParamShowsThatSpecificGroupRegardlessOfSize() throws Exception {
        String owner = "candidates-via-param@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates").param("via", TOM_PETTY)
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Mike Campbell");
        assertThat(body).contains("Jackson Browne");
        assertThat(body).doesNotContain("Wilco Member 1<");

        // Wilco is now in the sidebar instead.
        assertThat(body).contains(WILCO);
    }

    @Test
    void viaParamForAGroupWithNoPendingRowsFallsBackToBiggest() throws Exception {
        String owner = "candidates-via-stale@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates").param("via", "Not A Real Group")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Wilco Member 1<");
    }

    @Test
    void noPendingCandidatesShowsEmptyStateNotABrokenGroup() throws Exception {
        String owner = "candidates-empty@example.com";

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Nothing pending. Run expansion to find more.");
    }

    @Test
    void candidatesAreIsolatedByOwner() throws Exception {
        seedTwoGroups("candidates-owner-a@example.com");
        savePending("candidates-owner-b@example.com", "Bob Only Act", ArtistSource.SIMILAR_EXPANSION, "Dawes");

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", "candidates-owner-b@example.com"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Dawes");
        assertThat(body).doesNotContain(TOM_PETTY);
        assertThat(body).doesNotContain(WILCO);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.CandidatesPageRenderTest" --console=plain`
Expected: FAIL — the old template still renders collapsed groups with lazy rows; none of the new assertions match (e.g. `Wilco Member 30<` isn't on the page, "Show more" IS present).

- [ ] **Step 3: Implement — controller**

In `ReviewController.java`: add these imports —

```java
import com.robsartin.setlistscout.catalog.CandidateGroups;
import java.util.LinkedHashMap;
import java.util.Map;
```

Replace the existing `candidates` method:

```java
    /**
     * The Candidates page (issue #148): one group's full pending list at a time, biggest-first,
     * with a sidebar of the rest. {@code via} picks a specific group; omitted or stale (no pending
     * rows left) falls back to biggest-first via {@link CandidateGroups#resolve} -- the same
     * fallback rule every status-changing action below reuses as its auto-advance.
     */
    @GetMapping("/candidates")
    public String candidates(@RequestParam(required = false) String via, Model model) {
        populateCandidates(model, via);
        return "candidates";
    }

    /**
     * Resolves the current group and populates the model for either the full page or the
     * {@code candidatesApp} fragment. Returns the resolved current group's {@code via} (for
     * building a redirect URL after a non-htmx action), or {@code null} if nothing is pending.
     */
    private String populateCandidates(Model model, String requestedVia) {
        String owner = currentUser.email();
        var groups = CandidateGroups.from(
                artistRepository.countByStatusGroupedByViaAndSource(owner, ArtistStatus.PENDING_REVIEW));
        var resolved = CandidateGroups.resolve(groups, requestedVia);
        model.addAttribute("current", resolved.current());
        model.addAttribute("others", resolved.others());
        if (resolved.current() != null) {
            Map<ArtistSource, java.util.List<Artist>> rowsByType = new LinkedHashMap<>();
            for (var rg : resolved.current().relationGroups()) {
                rowsByType.put(rg.source(), artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource(
                        owner, ArtistStatus.PENDING_REVIEW, resolved.current().via(), rg.source()));
            }
            model.addAttribute("rowsByType", rowsByType);
        }
        return resolved.current() != null ? resolved.current().via() : null;
    }
```

Delete the `candidateRows` method (the `GET /candidates/rows` endpoint) and its javadoc entirely in this same step — Step 4 below replaces the whole template, including the `groupRows`/`rowDone` fragments that method rendered, so leaving it in place even briefly would mean dead code referencing a fragment that no longer exists. Add the missing import if not already present: `com.robsartin.setlistscout.catalog.Artist` (check the top of the file — it's likely already imported since `Artist` is used elsewhere in the class; add only if missing).

- [ ] **Step 4: Implement — template**

Replace the full contents of `src/main/resources/templates/candidates.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{fragments/layout :: page('Candidates', 'candidates', ~{::content})}">
<body>
<div th:fragment="content">
    <h1>Candidates</h1>
    <p class="page-sub">Reviewing one group at a time, biggest first. Approve or reject to move to the next.</p>

    <div th:replace="~{::candidatesApp}"></div>
</div>

<!--/* candidatesApp (#148): the single swap target for every action on this page (per-row
       approve/reject, per-relation-type bulk, global bulk, expand-now) -- one response always
       carries both the current group's full content AND the sidebar together, so there's never a
       moment where one reflects new state and the other doesn't (the old page's bug: bulk actions
       only patched a background counter, leaving cleared rows visibly stuck). */-->
<div th:fragment="candidatesApp" id="candidates-app" class="candidates-app" aria-live="polite">
    <p th:if="${current == null}">Nothing pending. Run expansion to find more.</p>

    <div th:if="${current != null}" class="candidates-layout">
        <aside class="group-sidebar card" aria-label="Other pending groups">
            <h2>Up next</h2>
            <ul>
                <li th:each="g : ${others}">
                    <a th:href="@{/artists/candidates(via=${g.via})}"
                       th:hx-get="@{/artists/candidates(via=${g.via})}"
                       hx-target="#candidates-app" hx-swap="outerHTML" hx-push-url="true">
                        <span th:text="${g.via}">Base artist</span>
                        <span class="count" th:text="${g.total}">0</span>
                    </a>
                </li>
                <li th:if="${#lists.isEmpty(others)}" class="lazy-hint">Nothing else queued.</li>
            </ul>
        </aside>

        <section class="artist-group card" aria-current="true">
            <div class="artist-group-header">
                <h2 th:text="${current.via}">Base artist</h2>
                <span class="count" th:text="${current.total}">0</span>
            </div>

            <div class="relgroup" th:each="rg : ${current.relationGroups}">
                <div class="relgroup-header">
                    <span class="chip" th:classappend="${rg.chipClass}" th:text="${rg.label}">Label</span>
                    <span class="count" th:text="${rg.count}">0</span>
                    <span class="group-actions">
                        <form class="inline" th:hx-post="@{/artists/candidates/group}"
                              hx-target="#candidates-app" hx-swap="outerHTML" method="post"
                              th:action="@{/artists/candidates/group}">
                            <input type="hidden" name="via" th:value="${current.via}"/>
                            <input type="hidden" name="type" th:value="${rg.source}"/>
                            <input type="hidden" name="decision" value="approve"/>
                            <button type="submit" class="btn-good btn-sm"
                                    th:aria-label="'Approve all ' + ${rg.label} + ' from ' + ${current.via}">Approve all</button>
                        </form>
                        <form class="inline" th:hx-post="@{/artists/candidates/group}"
                              hx-target="#candidates-app" hx-swap="outerHTML" method="post"
                              th:action="@{/artists/candidates/group}">
                            <input type="hidden" name="via" th:value="${current.via}"/>
                            <input type="hidden" name="type" th:value="${rg.source}"/>
                            <input type="hidden" name="decision" value="reject"/>
                            <button type="submit" class="btn-bad btn-sm"
                                    th:aria-label="'Reject all ' + ${rg.label} + ' from ' + ${current.via}">Reject all</button>
                        </form>
                    </span>
                </div>
                <div class="cand-list">
                    <div class="cand" th:each="a : ${rowsByType.get(rg.source)}">
                        <span class="cand-name" th:text="${a.name}">Name</span>
                        <span class="note" th:text="${a.note}">Note</span>
                        <form class="inline" th:hx-post="@{/artists/{id}/approve(id=${a.id})}"
                              hx-target="#candidates-app" hx-swap="outerHTML" method="post"
                              th:action="@{/artists/{id}/approve(id=${a.id})}">
                            <button type="submit" class="btn-good btn-sm" th:aria-label="'Approve ' + ${a.name}">Approve</button>
                        </form>
                        <form class="inline" th:hx-post="@{/artists/{id}/reject(id=${a.id})}"
                              hx-target="#candidates-app" hx-swap="outerHTML" method="post"
                              th:action="@{/artists/{id}/reject(id=${a.id})}">
                            <button type="submit" class="btn-bad btn-sm" th:aria-label="'Reject ' + ${a.name}">Reject</button>
                        </form>
                    </div>
                </div>
            </div>
        </section>
    </div>

    <div class="globalbar card">
        <form class="inline" th:if="${current != null}"
              th:hx-post="@{/artists/approve-all-pending}" hx-target="#candidates-app" hx-swap="outerHTML"
              method="post" th:action="@{/artists/approve-all-pending}">
            <button type="submit" class="btn-good">Approve all remaining</button>
        </form>
        <form class="inline" th:if="${current != null}"
              th:hx-post="@{/artists/reject-all-pending}" hx-target="#candidates-app" hx-swap="outerHTML"
              method="post" th:action="@{/artists/reject-all-pending}">
            <button type="submit" class="btn-bad btn-sm">Reject all remaining</button>
        </form>
        <form class="inline" th:hx-post="@{/artists/expand-now}" hx-target="#candidates-app" hx-swap="outerHTML"
              method="post" th:action="@{/artists/expand-now}">
            <input type="hidden" name="via" th:if="${current != null}" th:value="${current.via}"/>
            <button type="submit">Run expansion now</button>
        </form>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.CandidatesPageRenderTest" --console=plain`
Expected: PASS, all 5 tests. (Other test classes referencing the old page shape — `CandidateActionsTest`, `CandidateGroupsTest`'s existing `from` tests — are unaffected by this step; a full-suite run happens at the end of Task 5.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/review/ReviewController.java src/main/resources/templates/candidates.html src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java
git commit -m "#148: focused single-group Candidates page (GET only) -- sidebar + full rows, no pagination"
```

---

### Task 3: Row-level approve/reject auto-advances

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java`
- Modify: `src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java`

**Interfaces:**
- Consumes: `populateCandidates` (Task 2).
- Produces: `ReviewController.actionResult(String hxRequest, Model model, String via)` — Task 4 reuses this exact method for the remaining actions.

- [ ] **Step 1: Write the failing tests**

Replace the full contents of `src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java`:

```java
package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real per-item candidate review actions (approve/reject) against a booted context +
 * Postgres, signed in as a test user: status changes, owner isolation, and the auto-advance
 * response shape (issue #148). Runs in CI (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CandidateActionsTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    private static final String TOM_PETTY = "Tom Petty and the Heartbreakers";
    private static final String WILCO = "Wilco";

    private Long savePending(String owner, String name, ArtistSource source, String discoveredVia) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, "note for " + name);
        artist.setOwner(owner);
        return artistRepository.save(artist).getId();
    }

    @Test
    void approveChangesOnlyThatArtistToApproved() throws Exception {
        String owner = "actions-approve@example.com";
        Long id = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/{id}/approve", id)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ArtistStatus.APPROVED);
    }

    @Test
    void approveDoesNotTouchAnotherOwnersArtist() throws Exception {
        String owner = "actions-approve-owner-a@example.com";
        String otherOwner = "actions-approve-owner-b@example.com";
        Long othersId = savePending(otherOwner, "Not Yours", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/{id}/approve", othersId)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))));

        assertThat(artistRepository.findById(othersId).orElseThrow().getStatus())
                .isEqualTo(ArtistStatus.PENDING_REVIEW);
    }

    @Test
    void rejectChangesArtistToRejected() throws Exception {
        String owner = "actions-reject@example.com";
        Long id = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/{id}/reject", id)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ArtistStatus.REJECTED);
    }

    @Test
    void approvingTheLastRowInAGroupsOnlySectionAutoAdvancesToTheNextGroup() throws Exception {
        String owner = "actions-auto-advance@example.com";
        // Wilco is bigger, so it's the initial "current" group; Tom Petty has exactly one row.
        for (int i = 1; i <= 3; i++) {
            savePending(owner, "Wilco Member " + i, ArtistSource.MEMBER_EXPANSION, WILCO);
        }
        Long lastTomPettyRow = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        // Land on Tom Petty specifically (it's not the biggest, so this proves `via` navigation first).
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/artists/candidates").param("via", TOM_PETTY)
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk());

        String body = mockMvc.perform(post("/artists/{id}/approve", lastTomPettyRow)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Tom Petty had only that one row -- clearing it empties the group, so the response is the
        // NEXT group (Wilco, the only one left) shown in full, not a bare empty swap.
        assertThat(body).contains(WILCO);
        assertThat(body).contains("Wilco Member 1<");
        assertThat(body).doesNotContain(TOM_PETTY);
        assertThat(body).doesNotContain("<head").doesNotContain("topbar");
    }

    @Test
    void approvingOneRowWhenOthersRemainInTheSameSectionStaysOnTheCurrentGroup() throws Exception {
        String owner = "actions-stay-put@example.com";
        Long member1 = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Benmont Tench", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", member1)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Benmont Tench is still pending in the same group -- current group unchanged, just refreshed.
        assertThat(body).contains(TOM_PETTY);
        assertThat(body).contains("Benmont Tench");
        assertThat(body).doesNotContain("Mike Campbell");
    }

    @Test
    void clearingTheLastGroupShowsTheRealEmptyState() throws Exception {
        String owner = "actions-clear-last@example.com";
        Long onlyRow = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/reject", onlyRow)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Nothing pending. Run expansion to find more.");
    }

    @Test
    void groupRejectChangesOnlyThatGroupsPendingRows() throws Exception {
        String owner = "actions-group-reject@example.com";
        Long member1 = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long member2 = savePending(owner, "Benmont Tench", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long similar = savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);
        Long otherGroup = savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);

        mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", "MEMBER_EXPANSION")
                        .param("decision", "reject")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(member1).orElseThrow().getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(artistRepository.findById(member2).orElseThrow().getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(artistRepository.findById(similar).orElseThrow().getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(artistRepository.findById(otherGroup).orElseThrow().getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
    }

    @Test
    void groupActionOnlyTouchesRequestingOwnersRows() throws Exception {
        String owner = "actions-group-owner-a@example.com";
        String otherOwner = "actions-group-owner-b@example.com";
        Long mine = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long theirs = savePending(otherOwner, "Someone Else", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", "MEMBER_EXPANSION")
                        .param("decision", "approve")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(mine).orElseThrow().getStatus()).isEqualTo(ArtistStatus.APPROVED);
        assertThat(artistRepository.findById(theirs).orElseThrow().getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
    }
}
```

Note: this step's test file already includes `groupRejectChangesOnlyThatGroupsPendingRows` and `groupActionOnlyTouchesRequestingOwnersRows` (unchanged from the old suite — `reviewGroup`'s status-change behavior itself isn't changing, only its response shape, which Task 4 covers) so the file is complete and compiles once Task 4 lands; they'll pass already since `reviewGroup` doesn't need to change for THESE particular assertions (they only check status + redirect, not response body shape).

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.CandidateActionsTest" --console=plain`
Expected: FAIL on `approvingTheLastRowInAGroupsOnlySectionAutoAdvancesToTheNextGroup`, `approvingOneRowWhenOthersRemainInTheSameSectionStaysOnTheCurrentGroup`, `clearingTheLastGroupShowsTheRealEmptyState` — `approve`/`reject` still return the old bare `rowDone` fragment, which contains none of the asserted content. The rest pass already (status-change behavior unchanged).

- [ ] **Step 3: Implement**

In `ReviewController.java`, replace `approve`, `reject`, and the old `rowResult` helper:

```java
    /** Approve one candidate. Owner-scoped via changeStatus (no-op if this owner doesn't own {@code id}). */
    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          Model model) {
        String owner = currentUser.email();
        String via = artistRepository.findByIdAndOwner(id, owner).map(Artist::getDiscoveredVia).orElse(null);
        activationService.changeStatus(id, owner, ArtistStatus.APPROVED);
        return actionResult(hxRequest, model, via);
    }

    /** Reject one candidate. Owner-scoped via changeStatus (no-op if this owner doesn't own {@code id}). */
    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                         Model model) {
        String owner = currentUser.email();
        String via = artistRepository.findByIdAndOwner(id, owner).map(Artist::getDiscoveredVia).orElse(null);
        activationService.changeStatus(id, owner, ArtistStatus.REJECTED);
        return actionResult(hxRequest, model, via);
    }
```

Delete the old `rowResult` private method entirely (its javadoc describes exactly the stale-display behavior this issue fixes). Add its replacement, next to `populateCandidates`:

```java
    /**
     * Shared response for every status-changing action on this page (issue #148): re-resolves
     * against {@code via} (the group the action just happened in, or {@code null} for a
     * whole-owner action) via {@link #populateCandidates} -- which either keeps that group current
     * (rows remain) or auto-advances to the next biggest (it's now empty), and populates the model
     * either way. htmx request -&gt; the {@code candidatesApp} fragment (both the group and sidebar
     * regions, always in sync, never stale). Non-JS fallback -&gt; redirect back to
     * {@code /artists/candidates}, carrying the resolved via as a query param so a full page load
     * lands in the same place htmx would have.
     */
    private String actionResult(String hxRequest, Model model, String via) {
        String resolvedVia = populateCandidates(model, via);
        if (hxRequest != null) {
            return "candidates :: candidatesApp";
        }
        if (resolvedVia != null) {
            return "redirect:/artists/candidates?via="
                    + java.net.URLEncoder.encode(resolvedVia, java.nio.charset.StandardCharsets.UTF_8);
        }
        return "redirect:/artists/candidates";
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.CandidateActionsTest" --console=plain`
Expected: PASS, all 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/review/ReviewController.java src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java
git commit -m "#148: per-row approve/reject auto-advance when their group empties"
```

---

### Task 4: Group-level and global bulk actions reuse the same auto-advance

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java`
- Modify: `src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java`

**Interfaces:**
- Consumes: `actionResult` (Task 3).
- Produces: nothing new consumed by later tasks — Task 5 only deletes code.

- [ ] **Step 1: Write the failing tests**

Add to `CandidateActionsTest.java` (the file Task 3 wrote):

```java
    @Test
    void groupBulkRejectThatEmptiesTheWholeGroupAutoAdvances() throws Exception {
        String owner = "actions-group-bulk-advance@example.com";
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY); // Tom Petty's only row
        savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);
        savePending(owner, "Wilco Member 2", ArtistSource.MEMBER_EXPANSION, WILCO);

        String body = mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", "MEMBER_EXPANSION")
                        .param("decision", "reject")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(WILCO);
        assertThat(body).doesNotContain(TOM_PETTY);
    }

    @Test
    void globalApproveAllThatEmptiesEverythingShowsRealEmptyState() throws Exception {
        String owner = "actions-global-approve-all@example.com";
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);

        String body = mockMvc.perform(post("/artists/approve-all-pending")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Nothing pending. Run expansion to find more.");
    }

    @Test
    void globalRejectAllOnlyTouchesThisOwnersRows() throws Exception {
        String owner = "actions-global-reject-owner-a@example.com";
        String otherOwner = "actions-global-reject-owner-b@example.com";
        Long mine = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long theirs = savePending(otherOwner, "Someone Else", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/reject-all-pending")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(mine).orElseThrow().getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(artistRepository.findById(theirs).orElseThrow().getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
    }

    @Test
    void expandNowKeepsTheCurrentGroupInView() throws Exception {
        String owner = "actions-expand-now@example.com";
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);

        // Land on Tom Petty specifically, then run expansion -- nothing about pending rows changed
        // (expand-now only re-dues background jobs), so the same group should still be current.
        String body = mockMvc.perform(post("/artists/expand-now")
                        .param("via", TOM_PETTY)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(TOM_PETTY);
        assertThat(body).contains("Mike Campbell");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.CandidateActionsTest" --console=plain`
Expected: FAIL on all 4 new tests — `reviewGroup`/`approveAllPending`/`rejectAllPending`/`expandNow` still return the old `actionResult` (renamed in Task 3's diff, but these four callers weren't updated yet) which references the now-deleted `globalBar` fragment, or (for `expandNow`) doesn't accept a `via` param at all.

- [ ] **Step 3: Implement**

In `ReviewController.java`, update the four remaining callers of the old two-arg `actionResult(hxRequest, model)` to call Task 3's new three-arg version:

```java
    @PostMapping("/candidates/group")
    public String reviewGroup(@RequestParam String via, @RequestParam ArtistSource type,
                              @RequestParam String decision,
                              @RequestHeader(value = HX_REQUEST, required = false) String hxRequest, Model model) {
        ArtistStatus status;
        if ("approve".equals(decision)) {
            status = ArtistStatus.APPROVED;
        } else if ("reject".equals(decision)) {
            status = ArtistStatus.REJECTED;
        } else {
            return actionResult(hxRequest, model, via);
        }
        for (Artist a : artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource(
                currentUser.email(), ArtistStatus.PENDING_REVIEW, via, type)) {
            activationService.changeStatus(a.getId(), currentUser.email(), status);
        }
        return actionResult(hxRequest, model, via);
    }
```

```java
    @PostMapping("/approve-all-pending")
    public String approveAllPending(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                    Model model) {
        for (Artist a : artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW)) {
            activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.APPROVED);
        }
        return actionResult(hxRequest, model, null);
    }

    @PostMapping("/reject-all-pending")
    public String rejectAllPending(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                   Model model) {
        for (Artist a : artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW)) {
            activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.REJECTED);
        }
        return actionResult(hxRequest, model, null);
    }

    /** Manually request expansion: mark all of this owner's expand jobs due-now (the poller drains them). */
    @PostMapping("/expand-now")
    public String expandNow(@RequestParam(required = false) String via,
                            @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                            Model model) {
        expandJobRepository.redueAll(currentUser.email(), java.time.Instant.now());
        return actionResult(hxRequest, model, via);
    }
```

Delete the old `actionResult(String, Model)` (two-arg) method and its javadoc entirely — Task 3 already added the three-arg replacement; only one `actionResult` should exist in the file after this step.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.CandidateActionsTest" --console=plain`
Expected: PASS, all 12 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/review/ReviewController.java src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java
git commit -m "#148: group-level and global bulk actions share the same auto-advance response"
```

---

### Task 5: Remove dead code + capstone multi-group coverage

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java` (delete paginated overload)
- Verify only, no expected changes: `src/main/resources/templates/candidates.html` (Task 2 already replaced the whole file and Task 2 already removed the controller method that used the old `groupRows`/`rowDone` fragments — this task just confirms no leftovers)
- Modify: `src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java` (add the capstone test)

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: nothing further — this is the final task.

- [ ] **Step 1: Write the failing capstone test**

Add to `CandidatesPageRenderTest.java` (the file Task 2 wrote):

```java
    @Test
    void clearingEveryGroupOneByOneAutoAdvancesThroughAllOfThemToTheRealEmptyState() throws Exception {
        String owner = "candidates-capstone@example.com";
        Long tomPettyRow = artistRepository.save(pendingArtist(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY)).getId();
        Long wilcoRow = artistRepository.save(pendingArtist(owner, "Nels Cline", ArtistSource.MEMBER_EXPANSION, WILCO)).getId();
        Long dawesRow = artistRepository.save(pendingArtist(owner, "Taylor Goldsmith", ArtistSource.MEMBER_EXPANSION, "Dawes")).getId();

        // Land on whatever's biggest (all groups are size 1 here, so any consistent tie-break is fine)
        // -- clear it, then clear whatever's next, then the last, confirming each response carries
        // exactly the remaining groups and the final one is the real empty state.
        String afterFirst = mockMvc.perform(post("/artists/{id}/reject", tomPettyRow)
                        .header("HX-Request", "true").with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterFirst).doesNotContain(TOM_PETTY);
        assertThat(afterFirst).satisfiesAnyOf(
                b -> assertThat(b).contains(WILCO),
                b -> assertThat(b).contains("Dawes"));

        String afterSecond = mockMvc.perform(post("/artists/{id}/reject", wilcoRow)
                        .header("HX-Request", "true").with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterSecond).doesNotContain(TOM_PETTY).doesNotContain(WILCO);

        String afterThird = mockMvc.perform(post("/artists/{id}/reject", dawesRow)
                        .header("HX-Request", "true").with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterThird).contains("Nothing pending. Run expansion to find more.");
    }

    private Artist pendingArtist(String owner, String name, ArtistSource source, String discoveredVia) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, "note for " + name);
        artist.setOwner(owner);
        return artist;
    }
```

- [ ] **Step 2: Run the capstone test to verify it fails or passes for the right reason**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.CandidatesPageRenderTest" --console=plain`
Expected: PASS already — Tasks 1–4 already implement everything this test exercises. This step exists to prove the capstone scenario genuinely works end-to-end, not to drive new implementation. If it fails, that means Tasks 1–4 have a gap — stop and fix the earlier task, don't patch around it here.

- [ ] **Step 3: Delete dead code**

`candidateRows` was already deleted in Task 2 (moved there during pre-flight review, since the template fragment it depended on was removed in that same task). This step handles what's left:

In `ArtistRepository.java`, delete the paginated overload (the one taking a `Pageable` parameter, with the "A page slice of one group's rows, for lazy load + 'show more'" javadoc) and its now-unused `import org.springframework.data.domain.Pageable;` if nothing else in the file uses `Pageable` (check first — `grep -n "Pageable" src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java` should show only that one method + its import after this deletion is needed; if it shows nothing else, remove the import too).

Verify `candidates.html` has no leftover `groupRows`/`rowDone` fragments — it shouldn't, since Task 2 replaced the whole file, but run `grep -n "groupRows\|rowDone" src/main/resources/templates/candidates.html` to confirm zero matches.

- [ ] **Step 4: Run the full test suite**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew --no-daemon clean build --console=plain > /tmp/gate-148.log 2>&1 &` then poll `/tmp/gate-148.log` in a separate command for `BUILD SUCCESSFUL`/`BUILD FAILED` (exceeds the 10-minute tool timeout — do not wait synchronously).
Expected: `BUILD SUCCESSFUL`. `PollerFlowTest.expandHappyPath` failing ALONE is the known pre-existing occasional flake (rerun it in isolation to confirm before treating it as this work's problem); anything else failing means Task 5's deletions broke something Tasks 1-4 didn't anticipate — fix it here, don't skip it.

Run: `python3 scripts/check_adrs.py`
Expected: `ADR compliance check passed`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java
git commit -m "#148: remove dead paginated repo overload; capstone multi-group coverage"
```

---

## Self-Review Notes

- **Spec coverage**: §1 (resolution) → Task 1. §2 (layout) → Task 2. §3 (auto-advance) → Tasks 3–4. §4 (endpoint table: candidates GET → Task 2, approve/reject → Task 3, group bulk → Task 4, rows endpoint removed → Task 5). §5 (testing list) → covered across Tasks 2–5's test additions plus the Task 5 capstone. Non-goals (no search/filter, no expansion-logic change, no Rejected-page change) — no task touches any of those areas.
- **Type/name consistency checked**: `populateCandidates` (Task 2) → reused verbatim by `actionResult` (Task 3) → reused verbatim by all four Task 4 callers. `CandidateGroups.ResolvedGroups`/`resolve` (Task 1) → consumed only inside `populateCandidates`, never referenced directly by template or later tasks. Template model attribute names (`current`, `others`, `rowsByType`) set once in `populateCandidates` (Task 2) and never redefined elsewhere.
- **No placeholders**: every step has real code, real assertions, real commands.
