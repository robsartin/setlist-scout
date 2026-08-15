# Rejected artists reappearing in the review queue -- investigation (issue #118)

## Summary

Two mechanisms, not one. The 3 confirmed live pairs are best explained by mechanism 1, which was
in place continuously since this feature existed. Mechanism 2 is a same-day regression (#109,
landed on the day this issue was filed) that removed the only guard the expansion path had against
near-duplicate spellings -- it doesn't explain the 3 historical pairs (they predate it, or are
independent of it), but left as-is it would make the reported problem worse, not better, from here
on. Both are fixed by the same change (see "Fix implemented" below).

## Mechanism 1 (confirmed, historical): case/punctuation variant slips past an imperfect guard

The `artist (owner, name)` unique constraint is case- and punctuation-**sensitive**
(`ArtistRepositoryTest#uniqueConstraintIsCaseSensitive` proves this against real Postgres). From
this app's very first commit (`7b90f48`, Aug 12) through `c3f8eaa` (Aug 13), the
now-retired `CandidatePersistenceListener` had an application-layer pre-check,
`artistRepository.existsByOwnerAndNameIgnoreCase(owner, name)`, before creating a new candidate.
That check:

- is case-insensitive, so it caught the case-only pairs in principle, and
- does **not** filter by status -- it returns `true` for a match in ANY status, including
  REJECTED, so under normal (non-racing) operation a rejected artist already blocked a same- or
  differently-cased re-suggestion.

Two things this check did **not** cover, which is where the 3 confirmed pairs come from:

1. **Punctuation, not just case.** `existsByOwnerAndNameIgnoreCase` only folds case. It does
   nothing for a hyphen vs. an en-dash. `Only Murders In The Building - Cast` vs. `Only Murders in
   the Building – Cast` differs by case AND by that dash -- the case difference would have been
   caught by the old guard, but the dash difference would not have been, in isolation. This alone
   explains that pair without invoking any race.
2. **A same-case, same-punctuation TOCTOU race is still possible in principle** for the other two
   pairs (pure case differences): `existsByOwnerAndNameIgnoreCase` is a plain SELECT with no
   locking, followed by a separate `save()` -- two concurrent discoveries of the same name in
   different case (plausible here: MusicBrainz and Discogs, or the band-site scraper, can each
   independently discover a related artist and publish separate `RelationDiscovered`/
   `CandidateDiscovered` events close together) could both pass the pre-check before either
   commits. This was already flagged as a known, accepted gap in the pre-#109 code (the
   `CandidatePersistenceListener` javadoc calls the DB-level `insertIfAbsent` "the real,
   DB-enforced guard" and the app-layer check "a best-effort pre-filter" only).

**What's ruled out:** these are not explained by anything resetting status back to
`PENDING_REVIEW` -- see "Status-reset audit" below.

**What would confirm this precisely:** the 3 rows' `created_at` timestamps, cross-referenced
against `c3f8eaa`'s deploy time (Aug 13 23:38 commit; deploy time unknown without Render deploy
history) -- I don't have prod DB or Render deploy-log access from this environment. If the
punctuation-variant row for "Only Murders..." predates `c3f8eaa`, that's consistent with
mechanism 1 alone explaining it without a race. Recommend pulling `created_at` for all 3 rejected
originals and their reappeared variants as a follow-up confirmation step.

## Mechanism 2 (confirmed, current-code regression): #109 removed the guard entirely for expansion

`RelationDiscoveredListener` (added in `a8ae611`, `#109`, landed **the same day this issue was
filed**) replaced `CandidatePersistenceListener` and, per its own javadoc, "deliberately does NOT
short-circuit when the to-artist already exists for this owner." The `existsByOwnerAndNameIgnoreCase`
pre-check was dropped entirely -- not narrowed, removed. The only remaining guard for the
expansion path (member/similar/tribute/band-site-scrape -- everything that publishes
`RelationDiscovered`) was `ArtistRepository#insertIfAbsent`'s `ON CONFLICT (owner, name) DO
NOTHING`, which is an **exact-string** match against a case-sensitive DB constraint.

This is documented in the code itself:
`ArtistRepositoryTest` (lines 21-33) explicitly notes that, unlike its predecessor,
`RelationDiscoveredListener` "no longer has an app-layer `existsByOwnerAndNameIgnoreCase`
pre-check in front of \[`insertIfAbsent`\]... every to-artist upsert... reaches this method, so
the DB-level guard tested here is now the ONLY thing absorbing a duplicate (owner, name) insert."

Net effect: as of `#109`, *any* case or punctuation variant of an existing artist -- rejected or
not -- sails through as a brand-new PENDING_REVIEW row, with no race required. This wasn't the
change's intent (the javadoc's stated goal was to stop the edge write from being silently
short-circuited for corroboration purposes -- see `RelationDiscoveredFlowTest
#corroborationFromTwoSourcesDedupesNodeButPreservesBothEdges`), but node-level dedup was an
unintended casualty: the change assumed `insertIfAbsent`'s DB-level guard was an equivalent
safety net, which is false for anything but an exact-string repeat.

**Does this explain the 3 confirmed pairs?** Probably not on its own -- `#109` landed the same day
the issue was profiled, so there wasn't much of a window for it to have produced these specific
rows before the profiling ran. But it is a live, current-code gap independent of the 3 historical
examples, and it means the reported "keeps reappearing" perception would only get worse over time
if left unfixed, regardless of how the original 3 arose.

## Status-reset audit (ruled out)

Every write path to `Artist.status` was checked:

- **`ArtistActivationService.changeStatus(id, owner, newStatus)`** -- id- and owner-scoped;
  changes exactly one existing row's status, never creates a row. Grepped every call site
  (`ReviewController`): `approve`/`reject`/`reviewGroup`/`approveAllPending`/`rejectAllPending`
  only ever pass `APPROVED` or `REJECTED`. Nothing calls it with `PENDING_REVIEW`.
- **`unreject`** (`ReviewController#unreject` -> `setStatus(id, PENDING_REVIEW)`) is the only path
  that sets `PENDING_REVIEW` via `changeStatus`, and it's a single explicit user action on one
  id -- exactly the "reversible via Unreject" behavior the Rejected page advertises, not a bug.
- **`ArtistRepository#insertIfAbsent`**'s SQL is `INSERT ... ON CONFLICT (owner, name) DO
  NOTHING` -- literally `DO NOTHING`, not `DO UPDATE`. It cannot touch an existing row's status
  under any circumstance, confirmed by reading the query text and by
  `ArtistRepositoryTest#insertIfAbsentIsIdempotentAgainstAPreExistingConflictingRow` (asserts the
  original row's data survives a conflicting insert unchanged).
- **Seed/upload path** (`ArtistSeedService.addSeedIfNew`) only ever constructs a brand-new `Artist`
  with `ArtistStatus.SEED`; it never calls `changeStatus` or otherwise touches an existing row.
- **Backfills** (`ScanJobBackfill`, `ExpandJobBackfill`) only `findByStatusIn` (read) to decide
  which artists to (re)queue for scanning/expansion; neither writes `Artist.status`.

Conclusion: nothing resets an existing REJECTED row back to PENDING_REVIEW. The reappearance is
entirely a **new row under a different name string**, not the original row's status flipping.

## Is the pre-check leaky? (yes, on two axes)

Confirmed leaky in two independent ways:

1. **Case-insensitive check, case-sensitive DB constraint** -- acknowledged, pre-existing, and
   explicitly deferred in #95. This was mechanism 1's TOCTOU race exposure.
2. **Punctuation was never covered at all**, even by the pre-#109 guard -- `ignoreCase` folds
   case only. This explains the "Only Murders..." pair without needing a race.
3. **Post-#109, the guard doesn't exist for the expansion path at all** -- mechanism 2, above.

**Which paths create an `Artist` row, and did each apply a guard?**

- `RelationDiscoveredListener.on` (all of member/similar/tribute/band-site-scrape expansion) --
  pre-`#109`: case-insensitive-but-not-punctuation-aware pre-check. Post-`#109`: no pre-check at
  all, only the exact-match DB constraint. **This is the only creator of PENDING_REVIEW
  candidates.**
- `ArtistSeedService.addSeedIfNew` (the "Add a band" box, file upload, and startup
  `seed-bands.txt` import) -- still has its `existsByOwnerAndNameIgnoreCase` pre-check, unchanged
  by `#109`. Same punctuation gap as mechanism 1's item 1, but this is a deliberate, low-frequency
  **user** action (typing/uploading a band name), not an automated re-suggestion loop -- out of
  scope for this fix; noted as a possible follow-up below.

No other path constructs a new `Artist` row (`ScanJobBackfill`/`ExpandJobBackfill` are read-only
against `Artist`).

## Fix implemented

**Application-level normalized-name guard**, scoped to the expansion path
(`RelationDiscoveredListener`) where the actual bug lives:

- `ArtistNameNormalizer` (`src/main/java/.../catalog/ArtistNameNormalizer.java`): the single
  source of truth for "do these two spellings refer to the same artist." Trims, collapses internal
  whitespace, folds unicode dashes (en-dash, em-dash, horizontal bar, minus sign) to `-`, folds
  curly/typographic quotes to straight quotes, lowercases with `Locale.ROOT`. Deliberately
  conservative -- it does not strip all punctuation or do fuzzy/edit-distance matching, so
  genuinely different names ("AC/DC" vs "ACDC") stay distinct. Explicitly does NOT strip
  non-ASCII text: the issue's own first live-profiling pass did that and inflated a true count of
  3 pairs to a false 13 by collapsing all-Hebrew and all-Japanese names to an empty string --
  covered by `ArtistNameNormalizerTest#nonAsciiNamesPreserved`.
- `ArtistNameMatcher` (`src/main/java/.../catalog/ArtistNameMatcher.java`): owner-scoped scan over
  a lightweight `ArtistNameStatusView` projection (`ArtistRepository#findByOwner`, new), comparing
  normalized forms in Java (one implementation, not a Java copy + a SQL copy that can drift --
  exactly the kind of drift that caused the issue's own 13-vs-3 inflation). Matches across **every
  status**, including REJECTED, by design.
- `RelationDiscoveredListener.on` now resolves the to-artist id via
  `ArtistNameMatcher#findExistingMatch` **before** attempting `insertIfAbsent`. If a normalized
  match already exists (any status), no new row is inserted -- the edge is written against the
  existing row's id instead. If the match is REJECTED, this is logged
  (`"suppressed re-suggestion of a previously rejected artist under a new spelling"`) but the edge
  is still written, preserving the #109 corroboration fix (a second source's assertion of an
  already-known relationship still gets its own `artist_edge` row, it just doesn't get a second
  `artist` node).

This keeps the #109 corroboration fix intact while closing both leak axes (case and punctuation)
for the path that actually produces the reported symptom.

**Known residual limitation, same shape as the guard it replaces:** this is still a best-effort
pre-check, not a DB-enforced invariant -- a genuine concurrent race between two differently-spelled
discoveries of the same brand-new name could still both pass the Java-side scan before either
commits, in principle producing two variant rows. The DB's exact-match constraint remains the
backstop for a same-spelling race; a near-duplicate-spelling race is not DB-enforced. This mirrors
the exact tradeoff the pre-#109 `existsByOwnerAndNameIgnoreCase` pre-check already accepted (its
own javadoc called it "a best-effort pre-filter only").

## DB-level enforcement: deliberately NOT done in this pass

The issue's "likely fixes" list included a DB-level option: a functional unique index on a
normalized/canonical name column, enforced at the constraint level rather than the application
level. **I concluded this is too risky to do safely in this pass and did not add a migration.**
No V13 migration was added.

Reasoning:

- De-duplicating the ~12 case-variant and ~45 punctuation-variant groups already live in
  production is not a mechanical rename -- it requires deciding a winner per group (which status
  wins when variants disagree, e.g. one REJECTED and one APPROVED?), then repointing every foreign
  key that references the losing row's `artist.id` (`artist_edge.from_artist_id` /
  `to_artist_id`, and potentially `scan_job`/`expand_job` rows keyed to that artist), then deleting
  the loser -- all against live production data, with no ability from this environment to query
  prod and verify the plan against the actual 12+45 groups first.
- The application-level guard above already stops the reported symptom (new duplicate rows) at the
  source going forward. It does not retroactively clean up the ~57 variant groups already in prod,
  but those are pre-existing clutter, not actively causing new rejected-artist reappearances once
  this fix ships.
- Getting the merge-and-repoint migration wrong on live data (wrong winner, orphaned edges, lost
  rejections) is a strictly worse outcome than shipping the safe half of the fix now and treating
  the data cleanup as its own, carefully-planned follow-up with real prod data in hand.

This is a legitimate, deliberate scope boundary, not an oversight: the acceptance-critical part of
the bug report ("rejected artists keep reappearing") is fully addressed by the application-level
guard, since new near-duplicate rows are what caused the reappearance in the first place.

**Recommended follow-up** (separate issue, not built here): a script/migration that reads the live
12+45 variant groups, proposes a winner per group (oldest row? most-advanced status? highest id?),
and repoints `artist_edge` before deleting losers -- run once against prod with a dry-run mode
first, informed by actual data this environment can't see.

## Scope boundary: `ArtistSeedService` untouched

`ArtistSeedService.addSeedIfNew` (the seed/upload path) keeps its existing
`existsByOwnerAndNameIgnoreCase` pre-check unchanged. It has the same punctuation gap as mechanism
1, but:

- it's not implicated in the bug report (nothing about "Add a band" or file upload was described
  as the reappearance path),
- it's a deliberate, infrequent user action rather than an automated re-suggestion loop, and
- widening it to use `ArtistNameMatcher` would require reworking several existing unit tests
  (`ArtistSeedServiceTest`, `ArtistControllerTest`, `PollerFlowTest`) that stub
  `existsByOwnerAndNameIgnoreCase` directly, for a path that isn't the source of the reported
  symptom.

If tightening this path is wanted, it's a small, low-risk, separate follow-up: swap
`existsByOwnerAndNameIgnoreCase` for `artistNameMatcher.findExistingMatch(...).isPresent()` in
`ArtistSeedService`.

## Tests added

- `ArtistNameNormalizerTest` -- pure unit tests for the normalization rules, including the 3
  confirmed live pairs and the non-ASCII-preservation regression guard.
- `ArtistNameMatcherTest` -- unit tests (mocked repository) for case/punctuation matching,
  status-independence, and distinct-name non-matching.
- `RelationDiscoveredListenerTest` -- new cases for "normalized match found -> node insert
  skipped, edge still written" and "matched row is REJECTED -> suppressed with a log line, edge
  still written"; existing corroboration test retitled to reflect that the short-circuit now
  avoided is against the edge write, not against `insertIfAbsent` unconditionally.
- `RelationDiscoveredFlowTest` (real-path, Testcontainers) -- three new tests: a rejected artist
  does not reappear under a case/punctuation variant (and its status stays REJECTED, not reset);
  two genuinely different names still both become separate PENDING_REVIEW candidates (guards
  against over-merging); owner isolation (one owner's rejected artist doesn't suppress another
  owner's candidate of a matching name).
