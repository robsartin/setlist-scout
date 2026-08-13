# UI overhaul PR2 — split `/artists` + grouped, lazy-loaded candidates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Split the combined `/artists` page into three pages behind the existing top nav — **Artists** (active), **Candidates** (grouped, lazy-loaded pending review), **Rejected** — and make the candidates page scale (group by base artist × relation type, load rows on expand, per-item / per-group / global review actions). Closes #96. Builds on PR1 (the design system + shared layout + nav are already on `main`).

**Architecture:** Reuse the PR1 `fragments/layout.html :: page(title, navActive, content)` layout and `app.css`. The active list stays on `GET /artists` (`catalog.ArtistController`); the Candidates and Rejected pages are new GETs in `review.ReviewController` (which already owns the review/reject POSTs). The Candidates page renders only group headers + counts (a cheap grouped `COUNT`); each relation group's rows load via htmx when the `<details>` is expanded. Review actions map onto `catalog.ArtistActivationService.changeStatus` (which publishes the activation events), owner-scoped throughout.

**Tech Stack:** Spring Boot 3 + Spring Modulith, Spring Data JPA, Thymeleaf + vendored htmx, JUnit 5 + Testcontainers + MockMvc.

## Global Constraints

- **JDK 21:** `export JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem` before gradle. Run gradle FOREGROUND (blocking).
- **No new runtime deps; no CDN/SPA.** Server-rendered Thymeleaf + vendored htmx; progressive enhancement (per-item/per-group actions are real form POSTs, htmx-enhanced).
- **Owner isolation preserved everywhere** (every query + action scoped to `currentUser.email()`).
- **Modulith stays green** (`ModularityTests`): active GET in `catalog.ArtistController`; Candidates + Rejected GETs + candidate actions in `review.ReviewController`; new `ArtistRepository` queries are catalog API (already read by review). No new cross-module edge beyond catalog/settings/shared, which review already uses.
- **Reuse PR1 tokens/classes** (`.chip.member|similar|tribute`, `.btn-good|bad|primary|sm`, `.card`, `.table-scroll`, `.eyebrow`, `.count`, `.note`, `fieldset.decision`). Add new component styles to `app.css` via tokens only (no literal colors); auto light/dark already handled.
- **Accessibility:** native `<details>`/`<summary>` disclosure (keyboard-operable), `aria-current` nav, visible `:focus-visible`, `aria-live` for action feedback, AA both themes. Verification is part of acceptance.
- **Full gate before PR:** `./gradlew --no-daemon build` + `python3 scripts/check_adrs.py`. Docker up.
- **Branch:** `96-ui-overhaul-pr2` (off `main` after PR1 merged). Never commit to `main`. This PR **closes #96**.

## Data / naming reference (verified)

`Artist`: `owner` (email), `name`, `source` (`ArtistSource` enum: `MEMBER_EXPANSION`/`SIMILAR_EXPANSION`/`TRIBUTE_EXPANSION` for candidates), `status` (`ArtistStatus`: `PENDING_REVIEW`/`APPROVED`/`REJECTED`/`SEED`), `discoveredVia` (String, the base artist a candidate was expanded from; nullable in general but always set for expansion candidates), `note`. `ArtistActivationService.changeStatus(Long id, String owner, ArtistStatus status)` is the single Artist-status writer (publishes events). Relation label for the UI: MEMBER→"Members", SIMILAR→"Similar", TRIBUTE→"Tributes"; chip class `member`/`similar`/`tribute`.

---

## Task 1: repository queries + candidate grouping view-model

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java`
- Create: `src/main/java/com/robsartin/setlistscout/catalog/CandidateGroupCount.java` (projection interface)
- Test: `src/test/java/com/robsartin/setlistscout/catalog/CandidateQueryTest.java` (create; Testcontainers `@DataJpaTest` or the project's `@SpringBootTest` repo-test style)

**Interfaces:**
- Produces on `ArtistRepository`:
  - `List<CandidateGroupCount> countByStatusGroupedByViaAndSource(String owner, ArtistStatus status)` — one row per `(discoveredVia, source)` with its count, for the owner.
  - `List<Artist> findByOwnerAndStatusAndDiscoveredViaAndSource(String owner, ArtistStatus status, String discoveredVia, ArtistSource source, org.springframework.data.domain.Pageable pageable)` — a page slice of a group's rows (for lazy load + "show more").
  - `List<Artist> findByOwnerAndStatusAndDiscoveredViaAndSource(String owner, ArtistStatus status, String discoveredVia, ArtistSource source)` — all of a group's rows (for per-group bulk actions).
  - `long countByOwnerAndStatus(String owner, ArtistStatus status)` — total pending (the nav badge).
- `CandidateGroupCount` (projection): `String getVia()`, `ArtistSource getSource()`, `long getCount()`.

- [ ] **Step 1: Write the failing test** in `CandidateQueryTest.java`

```java
@Test
void groupsPendingCandidatesByBaseArtistAndSource() {
    // OWNER: two base artists, mixed sources + statuses
    save("Mike Campbell",  MEMBER_EXPANSION,  PENDING_REVIEW, "Tom Petty");
    save("Benmont Tench",  MEMBER_EXPANSION,  PENDING_REVIEW, "Tom Petty");
    save("The Wallflowers", SIMILAR_EXPANSION, PENDING_REVIEW, "Tom Petty");
    save("Some Tribute",   TRIBUTE_EXPANSION, PENDING_REVIEW, "Tom Petty");
    save("Nels Cline",     MEMBER_EXPANSION,  PENDING_REVIEW, "Wilco");
    save("Already In",     MEMBER_EXPANSION,  APPROVED,       "Tom Petty");   // excluded (not pending)
    save("Other Owner",    MEMBER_EXPANSION,  PENDING_REVIEW, "Tom Petty", "someone@else.com"); // excluded (owner)

    var groups = repo.countByStatusGroupedByViaAndSource(OWNER, ArtistStatus.PENDING_REVIEW);
    // Tom Petty: Members 2, Similar 1, Tributes 1 ; Wilco: Members 1  => 4 groups
    assertThat(groups).hasSize(4);
    assertThat(groups).anySatisfy(g -> {
        assertThat(g.getVia()).isEqualTo("Tom Petty");
        assertThat(g.getSource()).isEqualTo(ArtistSource.MEMBER_EXPANSION);
        assertThat(g.getCount()).isEqualTo(2);
    });
    assertThat(repo.countByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).isEqualTo(5);

    var page = repo.findByOwnerAndStatusAndDiscoveredViaAndSource(
        OWNER, ArtistStatus.PENDING_REVIEW, "Tom Petty", ArtistSource.MEMBER_EXPANSION,
        org.springframework.data.domain.PageRequest.of(0, 1));
    assertThat(page).hasSize(1);   // "show more" slice
}
```

Use the class's fixture conventions; owner default = a non-seed email. `save(...)` builds + persists an `Artist`.

- [ ] **Step 2: Run it — FAIL** (methods/projection don't exist).

- [ ] **Step 3: Add `CandidateGroupCount`** projection interface:

```java
package com.robsartin.setlistscout.catalog;

public interface CandidateGroupCount {
    String getVia();
    ArtistSource getSource();
    long getCount();
}
```

- [ ] **Step 4: Add the queries** to `ArtistRepository`:

```java
    @org.springframework.data.jpa.repository.Query("""
        SELECT a.discoveredVia AS via, a.source AS source, COUNT(a) AS count
          FROM Artist a
         WHERE a.owner = :owner AND a.status = :status
         GROUP BY a.discoveredVia, a.source
        """)
    List<CandidateGroupCount> countByStatusGroupedByViaAndSource(String owner, ArtistStatus status);

    List<Artist> findByOwnerAndStatusAndDiscoveredViaAndSource(
        String owner, ArtistStatus status, String discoveredVia, ArtistSource source,
        org.springframework.data.domain.Pageable pageable);

    List<Artist> findByOwnerAndStatusAndDiscoveredViaAndSource(
        String owner, ArtistStatus status, String discoveredVia, ArtistSource source);

    long countByOwnerAndStatus(String owner, ArtistStatus status);
```

(The projection alias names `via`/`source`/`count` must match the getter properties.)

- [ ] **Step 5: Run the test — PASS.**

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/catalog/ArtistRepository.java \
        src/main/java/com/robsartin/setlistscout/catalog/CandidateGroupCount.java \
        src/test/java/com/robsartin/setlistscout/catalog/CandidateQueryTest.java
git commit -m "#96 PR2: candidate grouping queries (grouped counts, paged group rows, pending count)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: nav count + Rejected page + move Rejected off the combined page

**Files:**
- Modify: `src/main/resources/templates/fragments/layout.html` (add Candidates + Rejected nav items; Candidates shows the count)
- Create: `src/main/java/com/robsartin/setlistscout/review/NavModelAdvice.java` (`@ControllerAdvice` adding `pendingCount`)
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java` (add `GET /artists/rejected`)
- Create: `src/main/resources/templates/rejected.html`
- Modify: `src/main/resources/templates/artists.html` (remove the Rejected section)
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistController.java` (stop populating `rejected` on `GET /artists`)
- Test: `src/test/java/com/robsartin/setlistscout/web/RejectedPageRenderTest.java` (create); update `ArtistPageRenderTest`

**Interfaces:**
- Consumes: Task 1's `countByOwnerAndStatus`.
- Produces: `GET /artists/rejected` (view `rejected`, model `rejected` list); every page's model carries `pendingCount` (via the advice); nav `navActive` values now include `'candidates'`, `'rejected'`.

- [ ] **Step 1: Write failing `RejectedPageRenderTest`** (copy Testcontainers+MockMvc+oidcLogin+CSRF from `ArtistPageRenderTest`; non-seed OWNER): `GET /artists/rejected` renders (200), contains the nav with `aria-current="page"` on Rejected, the `/css/app.css` link, a rejected artist's name + its Unreject form (`/artists/{id}/unreject`); a second owner sees none of the first's rejected rows (owner isolation). Also assert the nav shows the pending count.

- [ ] **Step 2: Run it — FAIL** (no route/template).

- [ ] **Step 3: Add the nav items** to `layout.html` (between Artists and Settings):

```html
    <a th:href="@{/artists/candidates}" th:attr="aria-current=${navActive == 'candidates'} ? 'page' : null">
        Candidates <span class="count" th:if="${pendingCount != null and pendingCount > 0}" th:text="${pendingCount}">0</span>
    </a>
    <a th:href="@{/artists/rejected}" th:attr="aria-current=${navActive == 'rejected'} ? 'page' : null">Rejected</a>
```

- [ ] **Step 4: Add `NavModelAdvice`** — a global `@ControllerAdvice` that puts `pendingCount` on every model:

```java
package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Supplies the top-nav's pending-candidates badge to every page. */
@ControllerAdvice
public class NavModelAdvice {
    private final ArtistRepository artistRepository;
    private final CurrentUser currentUser;
    public NavModelAdvice(ArtistRepository artistRepository, CurrentUser currentUser) {
        this.artistRepository = artistRepository;
        this.currentUser = currentUser;
    }
    @ModelAttribute("pendingCount")
    public long pendingCount() {
        return artistRepository.countByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW);
    }
}
```

(If `CurrentUser.email()` can be called outside an authenticated request — e.g. an error page — guard it: return 0 when there's no principal. Match how other components resolve the owner.)

- [ ] **Step 5: Add `GET /artists/rejected`** to `ReviewController`:

```java
    @org.springframework.web.bind.annotation.GetMapping("/rejected")
    public String rejected(org.springframework.ui.Model model) {
        model.addAttribute("rejected",
            artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.REJECTED));
        return "rejected";
    }
```

- [ ] **Step 6: Create `rejected.html`** on the layout (navActive `'rejected'`) — the rejected table (Name / Why suggested / Unreject) moved verbatim from `artists.html`, restyled with tokens (`.card`, `.table-scroll`, `.btn-sm`).

- [ ] **Step 7: Remove the Rejected `<h1>` + table from `artists.html`** and stop populating `rejected` in `ArtistController`'s GET. Keep the active + pending sections for now (pending moves in Task 3).

- [ ] **Step 8: Update `ArtistPageRenderTest`** — the artists page no longer contains the rejected table; add/adjust that assertion. Keep all other assertions.

- [ ] **Step 9: Run the affected tests — PASS.**

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.RejectedPageRenderTest" \
  --tests "com.robsartin.setlistscout.web.ArtistPageRenderTest" --console=plain
```

- [ ] **Step 10: Commit** (`#96 PR2: Rejected page + nav count; move rejected off the combined page`).

---

## Task 3: Candidates page — grouped headers + counts + lazy-loaded rows

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java` (`GET /artists/candidates`, `GET /artists/candidates/rows`)
- Create: `src/main/java/com/robsartin/setlistscout/review/CandidateGroups.java` (view-model assembler — base artist → relation groups with counts)
- Create: `src/main/resources/templates/candidates.html` (grouped page + a `groupRows` fragment)
- Modify: `src/main/resources/templates/artists.html` (remove the Pending section; relocate "Run expansion now" to candidates)
- Modify: `src/main/java/com/robsartin/setlistscout/catalog/ArtistController.java` (stop populating pending on `GET /artists` — it's active-only now)
- Modify: `src/main/resources/static/css/app.css` (group card + details styles, tokens only — mirror the mockup's `.artist-group`/`.relgroup`/`.cand`)
- Test: `src/test/java/com/robsartin/setlistscout/web/CandidatesPageRenderTest.java` (create)

**Interfaces:**
- Consumes: Task 1's `countByStatusGroupedByViaAndSource` + `findByOwnerAndStatusAndDiscoveredViaAndSource(..., Pageable)`.
- Produces: `GET /artists/candidates` (page: base-artist groups, each with relation subgroups + counts, no rows); `GET /artists/candidates/rows?via=&type=&offset=` (htmx `candidates :: groupRows` fragment: that group's rows + a "Show more" if `offset+limit < count`). View-model `CandidateGroups`: an ordered list of `BaseArtistGroup(via, total, List<RelationGroup>)` where `RelationGroup(source, label, chipClass, count)`.

- [ ] **Step 1: Write failing `CandidatesPageRenderTest`** (Testcontainers + MockMvc + oidcLogin + CSRF; non-seed OWNER). Seed pending candidates across 2 base artists × relation types, then:
  - `GET /artists/candidates` → 200; nav `aria-current="page"` on Candidates; contains each base-artist name (`Tom Petty`, `Wilco`) and the relation-group counts (e.g. `Members` + `2`); contains `<details` (collapsible groups); does **NOT** contain the individual candidate names yet (rows are lazy — assert a member name is absent from the initial page).
  - `GET /artists/candidates/rows?via=Tom Petty&type=MEMBER_EXPANSION` → 200; contains the member candidate names + their per-item Approve/Reject controls; is a **bare fragment** (no `<head`/`topbar`).
  - A group larger than the page limit yields a "Show more" control with the next offset.
  - Owner isolation: a second owner's `GET /artists/candidates` shows none of the first's groups.

- [ ] **Step 2: Run it — FAIL.**

- [ ] **Step 3: Build `CandidateGroups`** assembler (in `review`): given `List<CandidateGroupCount>`, produce base-artist groups ordered by total desc, each holding its relation subgroups (with label + chip class + count) in a stable order (Members, Similar, Tributes). Pure function over the counts — unit-testable. Handle a null `discoveredVia` by grouping under a literal like "Ungrouped" (defensive; expansion candidates always have it).

- [ ] **Step 4: Add the two GET endpoints** to `ReviewController`. `/candidates` loads the grouped counts → `CandidateGroups` → model (+ pendingCount comes from the advice). `/candidates/rows` reads a page slice via the Pageable query (limit e.g. 25) for `(owner, PENDING_REVIEW, via, source)`, computes whether more remain (compare `offset+limit` to the group's count), and returns `"candidates :: groupRows"`.

```java
    private static final int ROWS_PAGE = 25;

    @GetMapping("/candidates")
    public String candidates(Model model) {
        var counts = artistRepository.countByStatusGroupedByViaAndSource(
            currentUser.email(), ArtistStatus.PENDING_REVIEW);
        model.addAttribute("groups", CandidateGroups.from(counts));
        return "candidates";
    }

    @GetMapping("/candidates/rows")
    public String candidateRows(@RequestParam String via, @RequestParam ArtistSource type,
                                @RequestParam(defaultValue = "0") int offset, Model model) {
        var rows = artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource(
            currentUser.email(), ArtistStatus.PENDING_REVIEW, via, type,
            PageRequest.of(offset / ROWS_PAGE, ROWS_PAGE));
        model.addAttribute("rows", rows);
        model.addAttribute("via", via);
        model.addAttribute("type", type);
        model.addAttribute("nextOffset", offset + ROWS_PAGE);
        model.addAttribute("hasMore", rows.size() == ROWS_PAGE);   // simple heuristic; refine with the group count if needed
        return "candidates :: groupRows";
    }
```

- [ ] **Step 5: Create `candidates.html`** on the layout (navActive `'candidates'`). Structure (mirror the approved mockup):
  - A global bar: `pendingCount` + "Approve all remaining" / "Reject all remaining" (the existing `/artists/approve-all-pending` / `/artists/reject-all-pending`, htmx-boosted) + a "Run expansion now" button (`/artists/expand-now`).
  - For each base-artist group: a `.artist-group` card with header (via + total); for each relation subgroup a `<details class="relgroup">` whose `<summary>` shows the chip + label + count + per-group bulk buttons, and whose body lazy-loads:

```html
<div class="body"
     hx-get="@{/artists/candidates/rows(via=${g.via},type=${rg.source})}"
     hx-trigger="toggle once from:closest details"
     hx-target="this" hx-swap="innerHTML">
  <p class="lazy-hint">Loading…</p>
</div>
```

  - The `groupRows` fragment (`th:fragment="groupRows"`): the candidate rows (name + note + per-item Approve/Reject forms) and, when `hasMore`, a "Show more" button that `hx-get`s `/artists/candidates/rows` with `offset=${nextOffset}` and appends (`hx-swap="afterend"` on the show-more container, or `beforeend` on the list). Empty state when a group has no rows left.
  - **htmx trigger note:** `toggle once from:closest details` loads the body the first time its `<details>` opens. If that idiom misbehaves in this htmx version, fall back to `hx-trigger="intersect once"` (the body becomes visible on open → intersects) — verify whichever fires exactly once on first expand.

- [ ] **Step 6: Add group/details styles to `app.css`** (`.artist-group`, `.relgroup`, `summary`, `.cand`, `.lazy-hint`, `.globalbar`) — tokens only, mirror the mockup. `<details>`/`<summary>` keyboard behavior is native; add `summary { cursor:pointer }` and a caret.

- [ ] **Step 7: Remove the Pending section** from `artists.html` (the approve-all/reject-all/review-radio block + "Run expansion now"); stop populating `pendingTributes`/`pendingOthers` in `ArtistController`'s GET. `artists.html` is now active-only (active list + add/upload/site-url/remove). The `activeSection` fragment stays.

- [ ] **Step 8: Run the tests — PASS** (candidates page shows headers+counts, not rows; rows endpoint bare + has controls + show-more; owner isolation; artists page active-only).

- [ ] **Step 9: Commit** (`#96 PR2: Candidates page — grouped headers + lazy-loaded rows`).

---

## Task 4: candidate review actions (per-item / per-group / global) + retire the batch-radio flow

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/review/ReviewController.java` (per-item approve/reject; per-group bulk; remove the batch `/review`)
- Modify: `src/main/resources/templates/candidates.html` (wire the action forms/fragments)
- Test: `src/test/java/com/robsartin/setlistscout/web/CandidateActionsTest.java` (create)

**Interfaces:**
- Consumes: `ArtistActivationService.changeStatus`, Task 1's group-rows query (non-paged, for bulk).
- Produces: `POST /artists/{id}/approve`, `POST /artists/{id}/reject` (per-item); `POST /artists/candidates/group` with `via`,`type`,`decision` (per-group bulk). Global stays `/artists/approve-all-pending` / `/artists/reject-all-pending`. The batch-radio `POST /artists/review` is removed.

- [ ] **Step 1: Write failing `CandidateActionsTest`** (Testcontainers + MockMvc): seed pending candidates for OWNER; then
  - `POST /artists/{id}/approve` → that artist is now `APPROVED` (owner-scoped; a different owner's id is untouched / 4xx-or-noop), and the htmx response is a bare fragment (no layout chrome).
  - `POST /artists/{id}/reject` → `REJECTED`.
  - `POST /artists/candidates/group` `via=Tom Petty&type=MEMBER_EXPANSION&decision=reject` → all of that group's PENDING members become `REJECTED`, none of another group's.
  - Owner isolation on every action.

- [ ] **Step 2: Run it — FAIL.**

- [ ] **Step 3: Add the actions** to `ReviewController` (all via `activationService.changeStatus(id, currentUser.email(), …)`, owner-scoped):

```java
    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, @RequestHeader(value="HX-Request", required=false) String hx, Model model) {
        activationService.changeStatus(id, currentUser.email(), ArtistStatus.APPROVED);
        return actionResult(hx, model);   // bare fragment on htmx, else redirect:/artists/candidates
    }
    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestHeader(value="HX-Request", required=false) String hx, Model model) {
        activationService.changeStatus(id, currentUser.email(), ArtistStatus.REJECTED);
        return actionResult(hx, model);
    }
    @PostMapping("/candidates/group")
    public String reviewGroup(@RequestParam String via, @RequestParam ArtistSource type,
                              @RequestParam String decision, @RequestHeader(value="HX-Request", required=false) String hx, Model model) {
        var status = "approve".equals(decision) ? ArtistStatus.APPROVED : ArtistStatus.REJECTED;
        for (Artist a : artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource(
                 currentUser.email(), ArtistStatus.PENDING_REVIEW, via, type)) {
            activationService.changeStatus(a.getId(), currentUser.email(), status);
        }
        return actionResult(hx, model);   // return the (now emptied) group / refreshed page
    }
```

Decide the htmx swap shape (keep it simple + honest): **per-item** returns a bare fragment that removes the row (e.g. an empty/`✓`-flash swapped for the row via `hx-swap="outerHTML"` on the row); **per-group** returns the refreshed group (now empty → an "All reviewed" state) so the group count is correct; **global** reuses the existing approve-all/reject-all handlers (redirect/refresh). The nav `pendingCount` and each summary count re-sync on the next full page load — acceptable for v1; note (do not build now) an htmx out-of-band count update as a follow-up. Define `actionResult(hx, model)`: if `hx != null` return the appropriate `"candidates :: …"` fragment, else `"redirect:/artists/candidates"`.

- [ ] **Step 4: Wire the forms in `candidates.html`** — per-item Approve/Reject forms in `groupRows` (`hx-post` to `/artists/{id}/approve|reject`, target the row, `hx-swap="outerHTML"`); per-group bulk buttons in each `<summary>` (`hx-post` `/artists/candidates/group` with `via`/`type`/`decision` hidden inputs, target the group). Keep them real `<form>`s so they work without JS (progressive enhancement).

- [ ] **Step 5: Remove the batch-radio flow** — delete `POST /artists/review` from `ReviewController` and confirm nothing references it (the radio form was removed from `artists.html` in Task 3). Grep `\"/artists/review\"` / `review(` and clean any test that drove it (or repoint it at the new per-item action).

- [ ] **Step 6: Run the tests — PASS** (`CandidateActionsTest` + the render tests).

- [ ] **Step 7: Commit** (`#96 PR2: candidate review actions (per-item/per-group/global); retire batch-radio review`).

---

## Task 5: accessibility/keyboard verification + full gate + PR

**Files:** none (or a small `app.css`/template a11y fix). Verification + gate.

- [ ] **Step 1: Accessibility pass.** Verify from the rendered markup + a keyboard walkthrough: `<details>`/`<summary>` groups open/close with keyboard (native); every action is a real `<button>`/`<form>`; `aria-current="page"` on exactly the active nav item across all four pages (Shows/Artists/Candidates/Rejected); visible `:focus-visible` on nav, summaries, buttons; an `aria-live` region announces action results (e.g. wrap the candidate rows region so a removed/approved row is announced); the lazy "Loading…"/empty states are not keyboard traps. Confirm AA contrast still holds for any new component (the tokens are unchanged, so it should — spot-check the group card + summary text on both grounds). Fix anything that fails.

- [ ] **Step 2: Full gate (FOREGROUND, blocking)**

```bash
export JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem
python3 scripts/check_adrs.py && echo "ADRs OK"
./gradlew --no-daemon clean build --console=plain
```

Expected: `ADRs OK` + BUILD SUCCESSFUL (all unit + Testcontainers render/action tests + `ModularityTests`; owner isolation intact; the four pages render; htmx fragments bare).

- [ ] **Step 3: Commit** any a11y fix.

- [ ] **Step 4: Final whole-branch review + PR.** (subagent-driven-development wrapper: whole-branch review over `main..HEAD`, one fix wave if needed, then push + open the PR to `main` — the PR body notes this is PR2 of 2 and **closes #96**; the split + grouped/lazy candidates + the three action tiers. Stop at the PR — do not merge.)

---

## Self-Review

- **Spec coverage (§3 routing/split, §4 grouped+lazy candidates, §5 actions, §6 a11y):** Task 1 = the grouping data; Task 2 = Rejected page + nav count + Rejected split; Task 3 = Candidates page grouped headers + lazy rows + Pending split (artists page becomes active-only); Task 4 = the three action tiers + retire the batch flow; Task 5 = a11y + gate + PR. The active `/artists` GET stays in `catalog.ArtistController`; Candidates/Rejected in `review.ReviewController` (spec §3 recommendation).
- **Behavior/data safety:** all status changes go through `ArtistActivationService.changeStatus` (events fire → jobs enqueue), owner-scoped; owner isolation asserted on every new page + action. Grouped counts + paged rows keep the initial candidates page small regardless of the ~1,169 pending.
- **htmx risk:** the lazy-load trigger (`toggle once from:closest details`) and the bare-fragment responses are the two real risks; each has an explicit test (rows fragment has no `<head`/`topbar`; page has no rows until fetched) and a documented fallback for the trigger.
- **Type/name consistency:** `countByStatusGroupedByViaAndSource` / `findByOwnerAndStatusAndDiscoveredViaAndSource` / `countByOwnerAndStatus` used identically across tasks; `CandidateGroupCount` getters (`via`/`source`/`count`) match the JPQL aliases; `navActive` values `'candidates'`/`'rejected'` match the nav; fragment `candidates :: groupRows` used by both the lazy-load and show-more.
- **Placeholder scan:** endpoints, queries, and test intents are concrete; the one flexible spot (per-item htmx swap shape + count re-sync) is called out with a v1 decision and a named follow-up, not left vague.
- **YAGNI:** no OOB live count sync (deferred, noted); no manual theme toggle; no new deps; global actions reuse existing endpoints.
