# Plan: remove an artist from the seed list (#117)

## Context

Today the only way to take an artist off the Artists (active) page is
`POST /artists/{id}/remove` (`ReviewController.remove`), which sets status
`REJECTED`. That's the right semantic for an APPROVED artist (a former
expansion candidate) -- it parks it in the reversible Rejected review queue.
It's wrong for a `SEED` artist: a hand-curated seed the owner no longer wants
isn't a "rejected candidate," and rejecting it clutters the Rejected queue
forever.

## Design decision (settled -- binds every task below)

Add a new terminal `ArtistStatus.REMOVED`, distinct from `REJECTED`.

- **Not hard-delete:** `artist_edge` (V10) has hard FK constraints
  (`from_artist_id`/`to_artist_id` -> `artist(id)`, no cascade). A removed
  seed may be the `from`/`to` of edges asserting relationships to OTHER
  still-active artists (the graph epic, #108/#109/#123) -- hard-delete would
  either violate the FK or require cascading edge deletes that destroy
  discovery provenance for unrelated artists.
- **Not demote to REJECTED:** REJECTED drives the Rejected review-queue page
  (Unreject, #118's reappearance-prevention guard). A curated seed the owner
  just doesn't want anymore is a different semantic and must not appear
  there.
- **REMOVED is correct:** the row persists (no FK issue, `artist_edge`
  untouched, preserves graph provenance exactly as REJECTED already does per
  #109's "any status" corroboration-preserving match). It's inactive (same
  bucket as REJECTED/PENDING_REVIEW), so `ArtistActivationService.isActive()`
  needs no logic change -- just the new enum value. The transition goes
  through `ArtistActivationService.changeStatus(id, owner, REMOVED)`, never a
  direct repo save, so `ArtistDeactivated` fires and cancels
  `scan_job`/`expand_job` via the existing listener -- reuse, don't
  hand-roll.
- **Already-found shows:** left alone. `show_event.artist_name` is a plain
  string column, no FK to `artist.id` (V1 baseline; confirmed again in #123's
  V13 migration javadoc).
- **UI split by status, not a page split:** the Artists page's active list
  already mixes SEED and APPROVED in one table (`ArtistController
  .populateActive`: `findByOwnerAndStatusIn(owner, [SEED, APPROVED])`). Keep
  the existing "Remove" (-> REJECTED) button for APPROVED rows unchanged --
  that's still the correct semantic for a former candidate. Add a new
  "Remove from seed list" (-> REMOVED) button that only renders for SEED
  rows. REMOVED artists must not appear on the Artists page, the Candidates
  page, or the Rejected page. No "Un-remove" is required by the issue; do
  not build one.

## Verified against the codebase (do not re-derive)

- `artist.status` is `varchar(255)` **with a CHECK constraint**
  (`V1__baseline.sql` line 20:
  `CHECK (status IN ('SEED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'))`).
  Adding `REMOVED` DOES need a migration to widen this constraint -- it is
  NOT free. Use the next Flyway version after the highest currently in
  `src/main/resources/db/migration/` -- check with `ls
  src/main/resources/db/migration/ | sort -V` right before writing the
  migration filename, since another branch may have claimed the next number
  by the time this task runs.
- Every other status-filtering query in the codebase (`ScanJobBackfill`,
  `ExpandJobBackfill`, `ArtistController.populateActive`, and everything in
  `ReviewController`) already lists `SEED, APPROVED` or a specific status
  explicitly rather than "everything except REJECTED" -- so `REMOVED` is
  automatically excluded everywhere else with zero additional code changes.
  Do not add defensive filtering elsewhere; if you find a query that isn't
  status-scoped this way, flag it rather than guessing at a fix.
- The existing Remove button lives in
  `src/main/resources/templates/artists.html` lines 36-40, inside the
  `th:each="a : ${active}"` row loop, gated on nothing (renders for every
  row regardless of status). It must become conditional.

## Global Constraints

- Owner-scoped everything; assert it in tests (mirror the existing pattern:
  a foreign owner's id is a no-op, not a 404 leak, per
  `ArtistActivationService.changeStatus`'s existing owner-scoped no-op
  behavvior).
- Status changes go through `catalog.ArtistActivationService`, never a
  direct repository save.
- TDD: failing test -> implement -> green -> commit.
- Full gate before considering ANY task's final commit done:
  `export JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem && ./gradlew
  --no-daemon clean build --console=plain` (~4min after #129's
  maxParallelForks change) plus `python3 scripts/check_adrs.py`. If the
  build backgrounds past a tool timeout, redirect to a log file and poll
  that log in a separate command -- never assume success from a stale log;
  confirm it corresponds to the current HEAD.
- Do not push or open a PR for this plan's branch. Stop after the final
  task's gate-green confirmation.

## Task 1: `ArtistStatus.REMOVED` + Flyway migration

Add `REMOVED` to `src/main/java/com/robsartin/setlistscout/catalog/ArtistStatus.java`
with a javadoc comment explaining it's distinct from REJECTED (a curated
seed the owner no longer wants, vs. a reviewed-and-rejected expansion
candidate) and that it is intentionally excluded from `isActive()` (already
true by construction -- `isActive` only returns true for SEED/APPROVED, no
code change needed there, just confirm it with a test).

Add a Flyway migration (`V<next>__add_removed_artist_status.sql`, exact
version number determined at implementation time per the "Verified against
the codebase" note above) that drops and recreates `artist_status_check` to
include `REMOVED`:

```sql
ALTER TABLE artist DROP CONSTRAINT artist_status_check;
ALTER TABLE artist ADD CONSTRAINT artist_status_check
    CHECK (status IN ('SEED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'REMOVED'));
```

**Tests:**
- A repository-level test that persists an `Artist` with status `REMOVED`
  and confirms it saves without a constraint violation (proves the migration
  applied and the entity mapping accepts the new value). Use the existing
  `AbstractPostgresIntegrationTest` base, following the pattern of
  `ArtistRepositoryTest#uniqueConstraintIsCaseSensitive` referenced in
  `ArtistNameNormalizer`'s javadoc for how that test file asserts
  constraint behavior.
- A unit test on `ArtistActivationService.isActive` (or equivalent) proving
  `REMOVED` is inactive, alongside the existing REJECTED/PENDING_REVIEW
  cases -- do not skip this just because "no code changed"; the point is to
  pin the behavior with a test now that a fourth inactive status exists.

## Task 2: wire the removal action through `ArtistController`

Add `POST /artists/{id}/remove-from-seed` to `ArtistController` (not
`ReviewController` -- this action only applies to the Artists/active page
that `ArtistController` owns, unlike `remove`/`reject` which live in
`ReviewController` alongside the rest of the review-queue actions; follow
`ArtistController`'s existing constructor-injection pattern, it already has
`ArtistActivationService`? No -- check: `ArtistController`'s constructor
currently takes `(ArtistRepository, ArtistEdgeRepository, CurrentUser,
ArtistSeedService)`, no `ArtistActivationService`. Add it as a new
constructor parameter.).

```java
@PostMapping("/{id}/remove-from-seed")
public String removeFromSeed(@PathVariable Long id,
                             @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                             Model model) {
    activationService.changeStatus(id, currentUser.email(), ArtistStatus.REMOVED);
    if (hxRequest != null) {
        populateActive(model, currentUser.email());
        return "artists :: activeSection";
    }
    return "redirect:/artists";
}
```

Owner-scoped for free via `changeStatus`'s existing `findByIdAndOwner`
no-op-if-absent behavior -- confirm this with a test (a foreign owner's id
does nothing, artist unchanged) rather than assuming it.

**Tests (unit, mocked repository/activationService, mirror
`ArtistControllerTest`'s existing style):**
- Calling `removeFromSeed` invokes
  `activationService.changeStatus(id, owner, ArtistStatus.REMOVED)`.
- htmx request returns the `activeSection` fragment; non-htmx redirects to
  `/artists`.

**Real-path flow test (Testcontainers, mirror `JobEnqueueFlowTest`'s style
of driving the real `ArtistActivationService` + listener, not a mock):**
- Persist a SEED artist with existing `scan_job`/`expand_job` rows (or let
  the real backfill create them). Call `removeFromSeed`. Assert: artist
  status is `REMOVED`; its `scan_job`/`expand_job` rows are gone (proves
  `ArtistDeactivated` actually fired and the existing listener's cleanup
  ran -- a `Scenario` test would be a false green per ADR-0024, this must
  drive the real publish path).

## Task 3: template -- conditional button + exclude REMOVED everywhere

In `src/main/resources/templates/artists.html`, inside the `th:each="a :
${active}"` row's Actions cell (lines 35-41):

- Keep the existing "Remove" button (`/artists/{id}/remove`), but gate it
  `th:if="${a.status.name() == 'APPROVED'}"`.
- Add a new "Remove from seed list" button
  (`th:action="@{'/artists/' + ${a.id} + '/remove-from-seed'}"`,
  `th:if="${a.status.name() == 'SEED'}"`), same `btn-bad` styling as the
  existing Remove button, distinct label and `aria-label` text ("Remove
  {name} from seed list") so it's distinguishable for a screen-reader user,
  per the repo's accessibility convention (`th:aria-label` already used on
  the existing button).
- Confirm (read the templates, don't assume) that `candidates.html` and
  `rejected.html` cannot render a REMOVED row in the first place -- they
  query `PENDING_REVIEW` and `REJECTED` respectively, so this should already
  be structurally impossible. If either template turns out to iterate over
  something broader than that, flag it as a finding rather than silently
  patching around it.

**Tests:** a Thymeleaf/MockMvc render test (mirror the pattern in whichever
existing test class covers `artists.html` rendering, e.g. a
`*PageRenderTest` class if one exists for this page -- check
`src/test/java/com/robsartin/setlistscout/web/` for the naming convention
already used by `ArtistPageRenderTest`/`CandidatesPageRenderTest`/etc.)
asserting: a SEED row shows "Remove from seed list" and not "Remove"; an
APPROVED row shows "Remove" and not "Remove from seed list".

## Task 4: final gate + ADR check

Run the full gate (`clean build` + `check_adrs.py`) on the final state of
the branch. Confirm: all new tests pass, no existing test regressed, ADR
numbering/index still contiguous (this plan adds no new ADR -- if the
implementer judges one is warranted, e.g. documenting the REMOVED-vs-REJECTED
distinction as a durable design decision, flag it to the human rather than
adding one unilaterally, since ADRs are typically added deliberately, not as
a matter of course for every status addition).
