# Candidates page: keyboard focus restoration after every action (#155)

**Status:** approved 2026-08-16 (brainstormed interactively with Rob).
**Issue:** #155 (`ready`). **Follows:** `2026-08-15-candidates-page-redesign-design.md` (#148), which introduced the
whole-region swap this design has to live with.

## Goal

Every action on the Candidates page targets `#candidates-app` with `hx-swap="outerHTML"`, replacing the entire
region. That destroys the DOM element holding focus, so the browser drops focus to `<body>` after every
approve/reject. Reviewing by keyboard therefore costs a full re-tab through the topbar nav and the ~294-entry
sidebar **after every single decision**, and production groups reach 157 candidates. Keyboard review is
effectively unusable at scale, against a project constraint that treats WCAG AA / keyboard operability as
first-class.

Make the server name the element that should receive focus, and have the swapped-in fragment carry it, so a
keyboard user can work down a long list without ever losing their place.

Fold in the related announcement defect found in the same review: `aria-live` sits on `.artist-group-header`
*inside* the swap target, so the live-region node itself is replaced on every swap.

## Decision: no JavaScript is added

The obvious fix — an `htmx:afterSwap` listener — would introduce the first custom JS file to an app that
deliberately vendors htmx locally and ships none. That is not necessary: **htmx honors the `autofocus`
attribute inside swapped-in content**, so the server can name the focus target declaratively.

Verified empirically against this repo's own vendored `htmx.min.js` (2.0.3), driving real `outerHTML` swaps in
a browser:

| Case | Result |
| --- | --- |
| `autofocus` on a `<button>` in the swapped-in content | focus lands on it |
| `autofocus` on `<h2 tabindex="-1">` | focus lands on it |
| no `autofocus`, pressed button removed (today's behaviour) | focus drops to `<body>` — reproduces the bug |

The mechanism in the vendored source: `outerHTML` → `insertNodesBefore` → a per-inserted-node settle task →
`focus([autofocus])`, scoped to the inserted subtree. It runs **after** htmx's built-in id-based focus restore,
so `autofocus` wins wherever both could apply. `settleDelay` defaults to 20 ms, so the focus lands one tick
after the swap.

Rejected alternatives:

- **Small JS file + `htmx:afterSwap`** — more flexible, but adds the app's first custom JS and cannot be
  covered by any test harness in this repo.
- **Position-keyed button ids** (let htmx's built-in id restore land on whatever row slid into the slot) —
  template-only and zero JS, but breaks at relation-group boundaries and on auto-advance, and can't be
  meaningfully tested.
- **Narrowing the swap to the acted-on row** — focus still dies (the focused button is inside the removed
  row), and it undoes #148's auto-advance and synced counts, resurrecting the stale-display bug that redesign
  existed to fix.

## 1. Where focus lands

| Action | Focus target |
| --- | --- |
| Per-row Approve/Reject, successor exists | the next row's **same-decision** button, within the same relation group |
| Per-row, acted row was last in its relation group | the group anchor |
| Per-row that empties the group (auto-advance) | the group anchor (now showing the next group) |
| Per-relation-type bulk (Approve all / Reject all) | the group anchor |
| Global Approve/Reject all remaining, group remains | the group anchor |
| Global Approve/Reject all remaining, nothing left | the empty-state `<p>` |
| Sidebar group link (htmx `GET`) | the group anchor |
| Run expansion now / admin expand-now | **no `autofocus`** — the triggering button survives the swap, so a stable `id` on it lets htmx's built-in id-based restore keep focus exactly where the user left it |
| Full page load (non-htmx `GET`, history restore, and the no-JS redirect path) | **no `autofocus` anywhere** |

Same-decision targeting means Approve → next Approve and Reject → next Reject, so a run of identical decisions
needs no tabbing at all.

The **empty-state `<p>`** (`th:if="${current == null}"`) gains `tabindex="-1"` so it can receive focus at all.

**Group anchor** = the existing `<section id="current-group" tabindex="-1">`, already the skip-link target. It
gains:

- `aria-labelledby` pointing at its `<h2>` (which gains an id), so focusing it announces the group name;
- a themed focus ring in `app.css` — it currently receives the raw UA outline whenever the skip link is used,
  and this design makes that path much more common.

The `id="current-group"` and `href="#current-group"` values are asserted by `CandidatesPageRenderTest` and do
not change.

## 2. Server contract

One new pure class, `review/ActionOutcome.java`, factored the way `review/CandidateGroups.java` already is
(pure logic, unit-tested, no Spring):

```java
public record ActionOutcome(Focus focus, Long artistId, String decision, String message) {
    public enum Focus { NONE, ANCHOR, ROW }

    /** ROW targeting the successor of actedId in orderedGroupRows; ANCHOR when it has none. */
    static ActionOutcome afterRow(List<Artist> orderedGroupRows, long actedId, String decision, String message);
    static ActionOutcome anchor(String message);
    /** NONE -- the element that triggered the request survives the swap and htmx re-focuses it by id. */
    static ActionOutcome keepFocus(String message);

    boolean focusesRow(Long id, String decision);  // template predicate
    boolean focusesAnchor();                       // template predicate
}
```

`ReviewController#actionResult` takes the outcome as an additional parameter, so **every call site is forced by
the compiler to decide where focus goes** — no handler can silently forget and drop focus to `<body>`. The
`GET` handler passes `ActionOutcome.anchor(null)` for htmx requests and `null` for full-page renders.

### Successor lookup

Computed **before** the mutation, from `findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(owner,
PENDING_REVIEW, via, source)` — the same ordered method the render uses. One extra query per per-row action.

Doing it pre-mutation from the same DB-ordered list is what keeps this correct: computing "the next name after
this one" in Java post-mutation would compare with `String.compareTo` while the render orders by Postgres
collation, and the two disagree on case and punctuation — landing focus one row off the visible successor.

After the mutation, `actionResult` checks the chosen successor is still present in the `rowsByType` that
`populateCandidates` just built (another tab may have decided it) and downgrades `ROW` → `ANCHOR` if not. This
makes the **exactly-one-`autofocus`-per-response** invariant true by construction rather than by convention.

### Template

`th:autofocus="${outcome != null and outcome.focusesRow(a.id, 'approve')}"` on each row button; the analogous
predicate on the anchor and the empty-state `<p>` (the empty state is `focusesAnchor()` combined with the
template's existing `current == null` branch, so no fourth `Focus` value is needed). If `th:autofocus` misbehaves,
the equivalent `th:attr="autofocus=${cond} ? 'autofocus' : null"` is a drop-in fallback.

## 3. Deterministic row ordering

`ArtistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource` has no `ORDER BY` today, so "the next row" is
undefined and the visible order is whatever Postgres happens to return. Rename to
`...OrderByNameAsc`.

Required for this feature to mean anything, and it has a second benefit: near-duplicate spellings sort
adjacent (`"Paul Quinichette - John Coltrane Quintet"` next to `"Paul Quinichette-John Coltrane Quintet"`),
making the known dedup problem visible during review instead of scattered through a 157-row list.

## 4. Announcement

Remove `aria-live="polite"` from `.artist-group-header` (`candidates.html:64`). It is inside the swap target,
so `outerHTML` replaces the live-region node itself on every action — a brand-new live region's initial content
is generally not announced, which means the current markup is both wrong-content (group title + count rather
than what happened) and largely inert.

Replace it with a persistent region in `fragments/layout.html`, outside `<main>` and therefore outside every
swap target:

```html
<p id="sr-status" role="status" aria-live="polite" class="visually-hidden"></p>
```

Action responses carry a nested out-of-band update for it:

```html
<p id="sr-status" hx-swap-oob="innerHTML" th:if="${outcome != null and outcome.message != null}">…</p>
```

`innerHTML`, not the default `true`/`outerHTML`, so the live-region **node** persists and only its contents
change — the pattern screen readers reliably announce. Nested OOB works here: the vendored build defaults
`allowNestedOobSwaps` to `true` (checked), and htmx extracts OOB elements from the response before the primary
swap, so the element never lands inside `#candidates-app`.

Message content is composed from two halves so no count plumbing is needed:

- **what happened** (Java, in the handler): `"Approved Mike Campbell."` / `"Rejected 12 Members from Wilco."` /
  `"Expansion requested."`
- **where you are now** (template, from the already-resolved model): `" 29 left in Tom Petty and the
  Heartbreakers."`, or `" Nothing left to review."` when the queue is empty

The second half needs no auto-advance branch: after an auto-advance `current.via` *is* the group just moved
into, so the same sentence reads "Approved Mike Campbell. 30 left in Wilco." and the changed group name is
itself the announcement. Focus landing on that group's anchor announces it a second time.

`outcome` is null on full-page renders (and `outcome.message()` is null for the htmx `GET` used by sidebar
navigation, which moves focus but has nothing to report), so the OOB element is not emitted there and no
duplicate `id="sr-status"` can occur.

## 5. Testing

**Unit — `ActionOutcomeTest`** (fast, no Postgres, mirroring `CandidateGroupsTest`):

- successor picked for a middle row; ANCHOR when the acted row is last in its list; ANCHOR for a single-row
  list; ANCHOR when the acted id isn't in the list at all
- `focusesRow` / `focusesAnchor` predicates, including that `focusesRow` is false for the other decision

**Integration — extending `CandidateActionsTest` / `CandidatesPageRenderTest`** (existing MockMvc + Testcontainers
style, asserting on response markup):

- htmx approve of a middle row → `autofocus` on the **next** row's Approve button (matched by proximity to that
  artist's `aria-label`)
- htmx reject of a middle row → `autofocus` on the next row's **Reject** button
- approve the last remaining row of a group → `autofocus` on the group anchor, and the OOB status names the
  newly advanced-to group
- per-relation-type bulk → anchor
- approve-all-pending → `autofocus` on the empty-state element
- htmx `GET` with `via` (sidebar navigation) → anchor
- expand-now → response contains **no** `autofocus`, and the trigger button carries its stable id
- full-page `GET` → **zero** occurrences of `autofocus` (never steal focus on a normal load)
- invariant: every action response contains **exactly one** `autofocus`
- OOB: action responses contain `id="sr-status"` with `hx-swap-oob="innerHTML"` and the acted-on artist's name;
  the full page contains the empty persistent region and no `hx-swap-oob`
- regression: `.artist-group-header` no longer carries `aria-live`
- ordering: rows seeded out of order render A–Z
- owner scoping preserved on the new ordered query (per CLAUDE.md)

**Manual — keyboard + VoiceOver walkthrough**, recorded as acceptance in the PR.

Be explicit about what is and isn't covered: the browser spike proves htmx honors `autofocus` in an `outerHTML`
swap, and the MockMvc tests prove the app emits exactly one, on the right element, for every action. The seam
between those two — a real browser running the real page — is covered only by the manual pass. This repo has no
browser test harness and this work does not add one.

## Non-goals

- **#154 (stale nav badge)** stays a separate PR. This branch establishes the OOB pattern in `actionResult` that
  #154 needs, reducing it to giving the badge an id and emitting one more OOB span.
- **The no-JS path is unchanged.** A form POST is a full page navigation and the browser resets focus by
  definition; carrying a focus hint through the redirect is not worth the query-param surface.
- **`shows.html:45`** has the same anti-pattern (`aria-live` on `#shows-region`, which is itself the swap
  target). Out of scope here; worth its own issue once the shared `#sr-status` region exists.
- **Sidebar DOM churn.** Every swap re-creates ~294 sidebar links. `hx-preserve` would help, but it is a
  performance concern, not a focus one.
- **Key auto-repeat.** Holding Enter could chain a decision onto the newly focused button. This is inherent to
  any focus-the-next-control design and is not engineered around.

## Constraints notes

- **Modulith:** all changes are within `review` (`ReviewController`, the new `ActionOutcome`, templates) plus one
  renamed query method on `catalog.ArtistRepository`. No new cross-module dependency, so `ModularityTests` is
  unaffected.
- **No schema change**, so no Flyway migration and no `ddl-auto: validate` risk.
- **No ADR.** Nothing here contradicts or establishes a recorded architecture decision — the whole point of the
  chosen mechanism is that the app's shape (server-rendered Thymeleaf + vendored htmx, no custom JS) is
  preserved. Had the JS option been chosen, it would have warranted one.
- **Gate:** full `./gradlew --no-daemon clean build` plus `scripts/check_adrs.py` before the PR, per CLAUDE.md.
