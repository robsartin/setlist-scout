# UI overhaul design — light/dark theming + split `/artists` + grouped candidates (#96)

**Status:** approved 2026-08-13 (design + palette mockup + routing + 2-PR staging all approved by Rob).
**Issue:** #96 (`ready`). **Mockup:** `https://claude.ai/code/artifact/0dd13435-beaf-4269-a38f-4335cde73a4c`.

## Goal

Give setlist-scout a cohesive, accessible visual identity and make the review workflow scale. Today all styling is inline `<style>` in `templates/artists.html` / `shows.html`, and `/artists` is one page holding three sections (active / pending / rejected) — the pending list is currently ~1,169 rows across ~84 base artists, loaded all at once. This work establishes a themeable CSS foundation, splits `/artists` into three pages behind a persistent top nav, and makes the candidates page grouped and lazy-loaded.

Server-rendered **Thymeleaf + vendored htmx** throughout (no SPA, no CDN). All existing behavior (seeding, approve/reject semantics, shows, settings, owner isolation) is preserved.

## 1. Design system (CSS custom properties, auto light/dark)

A single stylesheet at `src/main/resources/static/css/app.css`, linked from every page — replacing the inline `<style>` blocks.

- **Palette** (from the approved mockup): calm indigo primary; violet-biased neutrals (chosen, not default grey); relation chips Members = teal, Similar = blue, Tributes = violet; semantic green/red for approve/reject (separate from the accent). Tokens are named CSS custom properties. Exact values are captured in the mockup and become the token block verbatim.
- **Three-state theming** (per the artifact theming rule): the bare `:root` block defines the complete **light** palette; `@media (prefers-color-scheme: dark)` redefines only the tokens (so dark follows the browser); components read tokens only — never a literal color inside a media block. `body` sets an explicit token background + foreground. **No manual toggle** (the mockup's preview button is not shipped).
- **Type:** system-font stack only (`system-ui, -apple-system, "Segoe UI", Roboto, …`) — no webfonts (keeps the app dependency-light, honors "no CDN"). Hierarchy carried by weight/size/spacing; `font-variant-numeric: tabular-nums` for counts.
- **Contrast:** WCAG AA (≥4.5:1 body, ≥3:1 large/UI) verified in both themes, including every chip and the primary button. Visible `:focus-visible` outline on all interactive elements. `@media (prefers-reduced-motion: reduce)` disables transitions.

## 2. Shared layout fragment + top nav

A reusable Thymeleaf layout fragment (e.g. `templates/fragments/layout.html`) provides `<head>` (charset, viewport, `<title>`, the `app.css` link, the vendored htmx script) and the persistent top nav, so every page shares one head + nav and there is no duplication.

- **Nav:** `Shows | Artists | Candidates | Rejected | Settings`, each linking to its route; the current page marked `aria-current="page"` and visually highlighted. The Candidates item shows the pending count.
- Each page template composes the layout fragment and supplies its own content block. `shows.html`, the new artists/candidates/rejected pages, and the settings view all use it.

## 3. Routing / page split

`/artists` stops being one combined page; the three sections become three routes under the existing `/artists` prefix (the `catalog`/`review` modules already own it):

| Route | Page | Content |
|---|---|---|
| `/` | Shows | unchanged behavior, restyled + nav (scan module, `ShowController`) |
| `/artists` | **Artists** | active list (SEED + APPROVED) + add-seed + upload + official-site edit + Remove |
| `/artists/candidates` | **Candidates** | grouped, lazy-loaded pending review (§4) |
| `/artists/rejected` | **Rejected** | rejected list + Unreject |
| `/settings` | Settings | unchanged behavior, restyled + nav |

Existing POST endpoints keep working (`/artists/seed`, `/artists/upload`, `/artists/{id}/site-url`, `/artists/{id}/remove`, `/artists/{id}/unreject`, `/artists/review`, `/artists/approve-all-pending`, `/artists/reject-all-pending`, `/artists/expand-now`). htmx fragments returned by an action target the section on the page the action was invoked from. A bare GET `/artists` now renders only the active page (no behavior change to the data, only which rows show).

**Current state:** the combined page GET is served entirely by `catalog.ArtistController` (`@GetMapping`, returns the `artists` view, sets `active` + `pendingTributes`/`pendingOthers` + `rejected`); `review.ReviewController` owns the review/reject/unreject/approve-all/reject-all POSTs (and only populates the pending fragment for its htmx responses). **Module placement for the split (plan decision, recommended):** `/artists` (active) stays in `catalog.ArtistController`; the `/artists/candidates` and `/artists/rejected` GET pages move to `review.ReviewController`, co-locating the review-queue pages with the review actions that already live there. Keeping all three in `catalog.ArtistController` is an acceptable fallback if the move creates friction. Either way `ModularityTests` stays green (both modules already read `catalog.ArtistRepository`).

## 4. Candidates page: grouped + lazy-loaded

Every pending candidate carries `discoveredVia` (the base artist it was expanded from) and `source` (`MEMBER_EXPANSION` / `SIMILAR_EXPANSION` / `TRIBUTE_EXPANSION`).

- **Structure:** group by base artist (`discoveredVia`), then within each by relation type. Each base-artist card lists its relation groups (Members / Similar / Tributes) with per-group counts; each relation group is a native `<details>`/`<summary>` disclosure — keyboard-operable, degrades without JS.
- **Lazy load ("variable-sized pages"):** the page first renders only the group headers + counts (a cheap grouped `COUNT`), not the rows. A group's candidate rows load on first expand via htmx (`hx-get` a rows-fragment endpoint, `hx-trigger` on the `<details>` toggle, `once`). Within a very large single group, the rows fragment includes a "Show more" continuation. This keeps the initial page small regardless of the ~1,169 total.
- **Data (catalog, read by review):** add owner-scoped `ArtistRepository` queries — (a) grouped counts of `PENDING_REVIEW` by `(discoveredVia, source)`; (b) the `PENDING_REVIEW` rows for a given `(owner, discoveredVia, source)` (with a limit/offset for "show more"). Base-artist card ordering: by total pending desc (biggest queues first) — reasonable default; revisit if noisy.
- **Default state:** groups start collapsed (nothing loaded) so the page is light; counts always visible.

## 5. Candidate review actions

All map onto the existing `catalog.ArtistActivationService.changeStatus(id, owner, APPROVED|REJECTED)` (which publishes the activation events that enqueue/cancel jobs) and stay owner-scoped.

- **Per-item:** Approve / Reject buttons on each candidate row (htmx, swaps just that row out / updates the group + its count — no full-page reload).
- **Per-group:** "Approve all / Reject all in this group" — acts on that `(base artist, relation type)` set for the owner.
- **Global:** "Approve all remaining / Reject all remaining" (the existing `approve-all-pending` / `reject-all-pending`, restyled).
- Counts (nav badge, group counts, global bar) update after an action. Feedback via an `aria-live` region.

## 6. Accessibility (acceptance criterion, both themes)

Semantic HTML (`<nav>`, `<details>`/`<summary>`, real `<button>`s, `<table>` for tabular data); `aria-current="page"` nav; visible focus states everywhere; `aria-live` region for action results; the disclosure widgets keyboard-operable (native `<details>` gives this free). AA contrast verified light + dark. Verification is part of acceptance — a keyboard walkthrough + contrast check on each page.

## 7. Staging (two PRs)

- **PR1 — foundation (no behavior/route change):** create `app.css` (the token system + light/dark), the shared layout fragment (head + nav + css link), and the top nav; migrate `shows.html` and the existing combined `artists.html` to the fragment + tokens (restyle in place, still one artists page). Ships the new look live with zero routing/behavior change — low risk. Render tests updated to the fragment; a contrast/keyboard pass.
- **PR2 — structure:** split the combined artists page into `/artists` (active), `/artists/candidates` (grouped + lazy-loaded, §4), `/artists/rejected`; wire the nav's three items; add the grouped-count + rows-fragment endpoints and the per-item/per-group actions. Render tests for each new page + the rows fragment + a large-group case; owner-isolation preserved.

Each PR: branch off `main` referencing #96, its own implementation plan, subagent-driven execution, full `./gradlew build` + `check_adrs` gate, PR to `main`. PR2 closes #96.

## Testing

- **Render tests** (existing pattern: `@SpringBootTest` + Testcontainers MockMvc, `oidcLogin` principal, names NOT in `seed-bands.txt`): each page renders the real template through the layout fragment; the candidates page renders group headers + counts without rows; the rows-fragment endpoint returns a group's rows (incl. a >limit "show more" case); actions return the right fragment and change status owner-scoped.
- **Owner isolation** preserved across the new routes (a second owner sees none of the first's candidates).
- Behavior-preservation: seed/upload/site-url/remove/unreject/review/approve-all/reject-all/expand-now unchanged.

## Non-goals

No manual theme toggle; no webfonts / new asset pipeline; no SPA; no redesign of settings/shows beyond restyle + nav; no change to expansion/scan/job logic; no new external dependency.

## Modulith / constraints notes

Templates + `static/` are cross-cutting resources (not a module). Controllers stay in their modules: `ShowController` (scan), `ArtistController` (catalog), `ReviewController` (review), `SettingsController` (settings), `LogLevelController` (shared.observability). New `ArtistRepository` query methods are catalog API (already read by review). `ModularityTests` must stay green. Merge gate = `./gradlew --no-daemon build` + `python3 scripts/check_adrs.py` (JDK 21 via `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem`).
