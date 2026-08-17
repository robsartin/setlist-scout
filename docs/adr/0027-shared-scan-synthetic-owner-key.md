# 0027: Shared scans as a synthetic owner key, not parallel infrastructure

Date: 2026-08-16

Status: Accepted

## Context

Issue #163 asks for a **shared scan**: the artists two users both follow, at a location neither
of them has saved, refreshed automatically in the background and visible to both participants.

Every durable, owner-scoped primitive in the app -- `Artist`, `SearchSettings`, `Show`, `ScanJob`,
the claim-lease poller ([0023](0023-per-unit-event-driven-scan-work-model.md)), `ScanUnitRunner`
-- keys on one opaque `owner` string, and nothing in that pipeline requires the string to be a
real user's email: `ScanUnitRunner#run(owner, artistId, sourceId)` looks up an artist by
`(id, owner)`, settings by `owner`, and stamps shows with `owner`.

A shared scan is not a person, but it needs everything a person's scan has: a saved location, a
materialized artist list, per-source jobs, and somewhere to persist found shows. The question this
ADR settles is how something that is not a person gets that machinery.

Full design: `docs/superpowers/specs/2026-08-16-shared-scan-permanent-design.md`.

## Decision

Give a shared scan its own synthetic `owner` key, `"shared:" + UUID` (`shared.SharedScanOwner`),
and let the existing owner-scoped machinery scan it, retry it, back it off, and store its shows
with no changes to the job model, the poller, or the runner.

A new identity-only table, `shared_scan` (`owner_key` UNIQUE, `owner_a`, `owner_b`, `label`,
`created_at` -- migration `V18__create_shared_scan.sql`), records who is sharing. Location is
deliberately **not** duplicated there: it lives in `search_settings` under the same `owner_key`,
so `SettingsService`, the settings-edit flow, and the existing `SettingsChanged` ->
re-due-every-scan-job behavior apply to a shared scan unchanged.

`SharedScanReconciler` keeps `artist(owner_key, ...)` equal to the normalized intersection of the
two participants' active artists (`catalog.SharedArtistFinder`, matched through
`catalog.ArtistNameNormalizer`), and both the create and remove transitions go through
`catalog.ArtistActivationService` -- the same rule CLAUDE.md already states for every status
change. That firing is what enqueues and cancels `scan_job` rows: the job lifecycle is inherited,
not written.

## Alternatives considered

- **Parallel `shared_*` tables** -- a shared scan's own job table, own poller. Rejected: it would
  fork the claim-lease / `SKIP LOCKED` / backoff logic
  ([0023](0023-per-unit-event-driven-scan-work-model.md)) -- already the subtlest code in the app
  -- into a second copy that has to be kept in step with the first by hand, indefinitely.
- **A `shared_scan_id` discriminator column** on `scan_job` / `show_event`. Looks cheaper than a
  new owner key, but `show_event`'s `UNIQUE(owner, artistName, eventDateTime, venueName)`
  constraint would have to include it, and Postgres treats NULLs as **distinct** in a unique
  constraint by default -- every ordinary show has a NULL discriminator, so the constraint would
  silently stop deduplicating them. Under the chosen design that constraint keeps working
  untouched, because the owner itself differs.

## Consequences

- `owner` widens from "a real user" to "a scan scope" -- every query, action, and page in the app
  is owner-scoped (CLAUDE.md), so this is a genuine semantic change, not a local one.
- Two guards contain the widening, enforced at all four places that enqueue jobs from an owner
  string: `ScanJobListener` and `ScanJobBackfill` enqueue scan jobs for cheap sources only
  (`ticketmaster`, `bandsintown` -- excluding the LLM-billed band-site fallback); `ExpandJobListener`
  and `ExpandJobBackfill` skip expansion entirely for a shared-scan owner, since expansion has no
  reviewer to show its candidates to and bills an LLM call per artist.
- The containment is pinned by tests, not comments: `SharedOwnerIsolationTest` and
  `SharedScanGuardsTest`.
- Login stays closed to synthetic keys with no new logic: `SecurityConfig` authorizes against
  `allowedEmails`, which a generated `shared:<uuid>` can never match.
- `SharedScanOwner` lives in `shared` (OPEN --
  [0021](0021-adopt-spring-modulith-with-enforced-boundaries.md)) because both `scan` and
  `expansion` need the same predicate and neither should depend on the other.
