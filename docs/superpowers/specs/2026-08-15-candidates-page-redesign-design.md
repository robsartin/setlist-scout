# Candidates page redesign: focused single-group review (#148)

**Status:** approved 2026-08-15 (brainstormed interactively with Rob).
**Issue:** #148 (`ready`). **Supersedes:** the grouped-accordion design from `2026-08-13-ui-overhaul-design.md` (#96) at the scale it was actually built for — that design assumed ~1,169 rows across ~84 groups; the real number today is 2,417 rows across 295 groups and still growing.

## Goal

The Candidates page currently renders all ~295 distinct base-artist groups on every page load — group headers, relation-type sub-disclosures, and bulk-action forms for every one of them, even though only one group's rows are ever open at a time. That page weight, not the grouping concept itself, is the real source of "the page is clunky." Separately, group-level bulk actions ("Approve/Reject all in this group") only patch the global pending-count badge — the group's own header count and row list never update, so cleared candidates stay visibly on screen until a manual reload ("things i reject all still are displayed").

Replace render-everything with **focused single-group review**: show exactly one group's full candidate list at a time, in the same biggest-first order the page already sorts by, with a lightweight sidebar to see/jump to the rest. Clearing the current group auto-advances into the next one — which is also the direct fix for the stale-display bug, since the response now swaps in the next group's real content instead of patching a background counter.

Server-rendered **Thymeleaf + vendored htmx** throughout, matching every other page in this app. All existing status-change semantics (`ArtistActivationService.changeStatus`, owner scoping) are preserved unchanged.

## Explicitly out of scope

A separate, confirmed bug: near-duplicate spelling variants (e.g. `"Paul Quinichette - John Coltrane Quintet"` vs `"Paul Quinichette-John Coltrane Quintet"` — differs only in hyphen spacing) can slip past `ArtistNameMatcher`'s conservative matching and reappear as a new candidate after the original spelling was rejected. This is a name-matching/dedup bug upstream of this page, not a display problem — track and fix separately, do not fold into this work.

## 1. Data: what "the current group" means

`ArtistRepository.countByStatusGroupedByViaAndSource` (existing) still produces the full set of `(discoveredVia, source, count)` rows, still assembled into `CandidateGroups.BaseArtistGroup`/`RelationGroup` (existing, unchanged) — this is cheap (an aggregate `COUNT`, not row data) and remains the source for both the sidebar list and the biggest-first ordering.

**The current group** is resolved from an optional `via` query param:
- `GET /artists/candidates?via=X` → that group, if it still has pending candidates for this owner.
- `GET /artists/candidates` (no param), or a `via` that's no longer pending (cleared, or stale bookmark) → the first group in the existing biggest-first sort.
- No groups left at all → the existing empty state ("Nothing pending. Run expansion to find more."), sidebar empty too.

This makes the current view bookmarkable/shareable and gives "jump to a specific group" and "land on whatever's biggest" the same resolution path — no separate "next group" state to track server-side between requests.

## 2. Page layout

Two regions, both server-rendered on the same `GET /artists/candidates`:

- **Sidebar** (`th:fragment="groupSidebar"`): the other groups, name + total count only — a plain list, no forms, no per-group buttons, no relation-type breakdown. Each entry links to `?via=X` (plain `<a>`, works with or without JS; htmx-enhanced to swap only the main panel without a full reload when JS is available). The current group is visually marked (e.g. `aria-current`) and excluded from being a link (you're already there).
- **Main panel** (`th:fragment="candidateGroup"`): the current group's full content — header (name, total), then its relation-type sub-sections (Members/Similar/Tributes, existing `RelationGroup` structure, unchanged chip styling), each listing **all** its pending rows directly (no more 25-row lazy pagination — one group's rows, even at 157, is far less markup than today's 295-group page) with per-row Approve/Reject, and a per-relation-type "Approve all/Reject all in this group" bulk action.

No behavior change to relation-type sub-grouping itself — same three buckets, same order (Members, Similar, Tributes), same chip classes.

## 3. Auto-advance

A candidate-clearing action (a per-row approve/reject that empties the group's last relation-type section, or a per-relation-type bulk approve/reject that empties the group outright) checks whether the *group* — not just that one relation-type section — has any pending rows left for this owner. If not, the response is the **next group's** full `candidateGroup` fragment (resolved the same way as §1, using the now-updated group list) plus a refreshed `groupSidebar` (the cleared group's entry gone). If the group still has other relation-type sections with pending rows, the response is just that one section's updated content (existing per-row/per-group swap behavior, scoped tighter than today's global-bar-only patch).

Both the main panel and sidebar update in one response — htmx targets a wrapping container around both regions rather than either fragment alone, so there's never a moment where one region reflects the new state and the other doesn't. Non-JS fallback: plain redirect to `/artists/candidates` (resolves to the next group per §1) or `?via=<next>` — same end state, just a full page load instead of a partial swap.

## 4. Endpoints

Builds on the existing `ReviewController` (owns candidate review already) rather than introducing a new controller:

| Route | Change |
|---|---|
| `GET /artists/candidates` | Existing route, new behavior: resolves the current group (§1), renders sidebar + one group's full content instead of all-groups-collapsed. Accepts optional `via` param. |
| `POST /artists/{id}/approve`, `POST /artists/{id}/reject` | Existing routes. Response changes: after the status change, check whether the row's group is now empty for this owner; return either the updated relation-type section or (if empty) the auto-advance response from §3. |
| `POST /artists/candidates/group` | Existing per-relation-type bulk route. Same response-shape change as above. |
| `GET /artists/candidates/rows` | **Removed.** No longer needed — a group's rows render directly as part of `GET /artists/candidates`, not lazy-loaded separately. |

`approve-all-pending` / `reject-all-pending` (global, existing) are unchanged in behavior (still act on every pending row for the owner) but now need to also return a real "nothing left" empty state for both regions rather than just refreshing a counter, since there's always a current group on screen to reflect.

## 5. Testing

Extends the existing `CandidatesPageRenderTest` (Testcontainers, `oidcLogin`, owner isolation) pattern:

- Landing on `/artists/candidates` with no `via` renders the biggest group's full rows + a sidebar listing the rest, correctly ordered.
- `?via=X` for a real pending group renders that group specifically, regardless of size ranking.
- `?via=X` for a group with no pending rows (already cleared, or a bogus value) falls back to the biggest-first resolution rather than erroring or rendering empty.
- Clearing a group's last relation-type section (via per-row and via per-group bulk) auto-advances: response reflects the next group's content, and the cleared group is absent from the sidebar.
- Clearing one relation-type section while the group still has others pending does NOT advance — only that section's content changes, the group and sidebar stay put.
- Clearing the last remaining group of all shows the real empty state, not a broken "next group" fragment.
- Owner isolation preserved: a second owner's groups/rows never appear regardless of `via` value supplied.
- `CandidateGroupsTest` (pure unit, existing) needs no change — the assembly logic (`CandidateGroups.from`) is unaffected; only the controller's use of it and the template change.

## Non-goals

No search/filter across groups (the sidebar is a plain list, not searchable — revisit only if 295 groups turns out to be too many to scan visually, not assumed now). No change to expansion/scan job logic, or to how candidates are generated. No change to the Rejected page. No new external dependency.

## Modulith / constraints notes

All changes stay within `review` (`ReviewController`, templates) reading `catalog.ArtistRepository` (already an established cross-module read per the #96 design). No new repository methods needed: `ArtistRepository` already has both an unpaginated `findByOwnerAndStatusAndDiscoveredViaAndSource(owner, status, via, source)` overload (currently used for per-group bulk actions) — this is what the current group's full row list uses directly — and the paginated one (currently backing the lazy "show more" rows fragment, which §4 removes; that overload becomes unused and can be deleted). `ModularityTests` must stay green. Merge gate: `./gradlew --no-daemon build` + `python3 scripts/check_adrs.py` (JDK 21 via `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem`).
