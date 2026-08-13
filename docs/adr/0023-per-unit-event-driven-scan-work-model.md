# 0023: Per-unit event-driven scan/expand work model

Date: 2026-08-13

Status: Accepted

## Context

ADR 0006 recorded the original scan model: a single whole-fleet `@Scheduled`
batch job that re-ran expansion and show search for *every* artist on a fixed
interval (`setlistscout.scan-interval-ms`, default 3 days), regardless of
whether that artist actually needed a re-check. That model had no per-artist
cadence (a brand-new artist and one scanned yesterday waited the same fixed
interval), no backoff for a source that was failing or rate-limited, and a
manual "Scan now" / "Run expansion now" button that blocked on a synchronous
full-fleet scan — slow, and coupled the request thread to however long the
whole batch took.

Phase B's move to Spring Modulith application events (ADR 0022) made it
possible to drive work per unit — per `(owner, artist, source)` — instead of
per fleet, triggered by the domain events that actually change what needs
scanning (an artist approved, a source added, search settings changed) rather
than by a blind timer.

## Decision

Replace the whole-fleet scheduler with durable, per-`(owner, artist, source)`
work items:

- Two job tables, `scan_job` and `expand_job`, each row representing one unit
  of work for one owner/artist/source combination, with a `next_due_at`,
  status, attempt count, and a JPA `@Version` column for optimistic locking.
- Jobs are enqueued, cancelled, and re-dued by catalog and settings domain
  events (an artist becomes active, a source is added, an owner's search
  settings change) rather than by a timer sweeping the whole fleet.
- A paced claim-lease poller per job type (`ScanPoller` / `ExpandPoller`)
  ticks on a fixed interval, claims a batch of due jobs with
  `SELECT ... FOR UPDATE SKIP LOCKED` (safe under concurrent pollers), and
  drains them.
- Failed attempts back off exponentially per job, up to a configured cap
  (`poller-park-cap`), after which the job "parks" — pushed a full interval
  out instead of climbing the backoff ladder further — so a persistently
  failing source doesn't retry in a tight loop forever.
- Per-source cadence overrides (`source-intervals`) let a specific source
  (e.g. a rate-limited API) run on a different interval than the
  `scan-interval` / `expansion-interval` defaults.
- The `@Version` optimistic lock means a settings-driven re-due can't be
  silently clobbered by a poller that has an older copy of the same job
  in-flight — one of the writes loses with an `OptimisticLockingFailureException`
  and is retried, rather than one silently overwriting the other.
- A startup backfill reconciler enqueues jobs for artists that predate the
  job tables, idempotently (`insertIfAbsent`), with a jittered `next_due_at`
  spread (`backfill-spread`) so enabling the pollers doesn't fire every
  backfilled job on the very first tick.
- The manual "Scan now" / "Run expansion now" buttons no longer run a
  synchronous scan; they simply re-due the owner's existing jobs to "now"
  and let the poller pick them up on its next tick.

## Consequences

- External API load is spread evenly over time and scales with the size of
  the *active* artist/source set, not the whole fleet — an artist that needs
  no attention doesn't cost a scan just because a timer fired.
- The system is resilient to restarts: jobs are durable rows, not in-memory
  scheduler state, and a lease that isn't renewed (a crash mid-run) is
  recoverable by another poller tick once it expires.
- Manual "Scan now" is now asynchronous — the button queues jobs and shows a
  "queued" confirmation instead of returning results synchronously; the user
  has to reload after a short wait rather than seeing results immediately.
- Running more than one application instance means more than one poller can
  be ticking concurrently; the claim lease bounds double-run to at most the
  lease window (`job-lease-ms`) rather than preventing it outright. This is
  an accepted tradeoff, not a full distributed lock.
- Supersedes [0006](0006-scan-frequency.md).
