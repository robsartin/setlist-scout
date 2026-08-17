# Permanent shared scans: a shared scan is a scan context, not a query (#163)

**Status:** design, awaiting review. Supersedes `2026-08-16-shared-show-scan-design.md`, which specified a transient, admin-only, run-and-discard page.
**Issue:** #163.

## What changed and why

The first design ran a shared scan synchronously and threw the results away. Rob asked for it to be permanent instead, and made three calls that drive everything below:

| Decision | Choice |
|---|---|
| Freshness | **Auto-refreshing**, on the same durable-job/poller model as personal scans |
| Location | **Fixed, editable settings** — configured once, not entered per run |
| Visibility | **Both participants** see it, not admin-only |
| Page | Its own page, not a mode on Shows |
| Scale | **Exactly one pairing** (Rob + David) for the foreseeable future |

Auto-refresh is the decision with the largest consequences: a background job has nobody to ask for a location, which is why the location must be stored; and it means shared shows must be persisted, which is what forces a real data model.

## The core idea

**A shared scan is a scan context that happens not to be a person.**

Every owner-scoped thing in this app — `ScanJob`, `Artist`, `SearchSettings`, `Show`, the claim-lease poller, `ScanUnitRunner` — keys on one opaque `owner` string. Nothing in that pipeline requires the string to be an email; `ScanUnitRunner#run(owner, artistId, sourceId)` looks up an artist by `(id, owner)`, settings by `owner`, and stamps shows with `owner`. That is the whole reuse story.

So: give the shared scan its own synthetic owner key, and the existing machinery scans it, retries it, backs it off, and stores its shows with **no changes to the job model, the poller, or the runner**.

```
shared_scan row ──> owner_key "shared:<uuid>"
                      ├─ search_settings(owner_key)   ← location/radius/window
                      ├─ artist(owner_key, name)      ← the materialized intersection
                      ├─ scan_job(owner_key, artist_id, source)  ← enqueued by the EXISTING listener
                      └─ show_event(owner_key, …)     ← written by the EXISTING runner
```

### Why this beats the alternatives

- **Parallel `shared_*` tables** would fork the claim-lease/`SKIP LOCKED`/backoff logic — the subtlest code in the app — into a second copy that must stay in step with the first.
- **A `shared_scan_id` discriminator column** on `scan_job`/`show_event` looks cheaper but sets a trap: `show_event`'s `UNIQUE(owner, artistName, eventDateTime, venueName)` would have to include it, and Postgres treats NULLs as **distinct** in a unique constraint by default — so ordinary shows would silently stop deduplicating. Under this design the constraint keeps working untouched, because the owner differs.

### The price, stated plainly

`owner` stops meaning "a real user." That is a genuine semantic widening and it is the main risk in this design. Sections 4 and 7 exist to contain it.

## 1. `shared_scan` — identity only

New table. Deliberately small: it holds *who*, not *where*.

| Column | Notes |
|---|---|
| `id` | PK |
| `owner_key` | UNIQUE. `"shared:" + UUID`. Opaque by construction. |
| `owner_a`, `owner_b` | Participant emails. Two columns, not a join table — the app needs exactly one pairing, and `SharedArtistFinder#findSharedArtistNames` is already a two-owner function. N-way membership would change the finder too, so it is not modelled speculatively. |
| `label` | Display name, e.g. "Rob & David". |
| `created_at` | |

**Location lives in `search_settings` under `owner_key`**, not here. `SettingsService#getOrCreateSettings(ownerKey)` and the existing settings-edit flow then work as-is — and, critically, the existing `SettingsChanged` → re-due-scan-jobs behaviour applies to shared scans for free. Duplicating location columns onto `shared_scan` would forfeit all of that.

The `owner_key` must never collide with a real email. Emails always contain `@`; the `shared:` prefix guarantees separation, and the UUID means the key is stable even if a participant is swapped later.

Migration: `V18__create_shared_scan.sql`.

## 2. Materializing the intersection

`SharedScanReconciler` keeps `artist(owner_key, …)` equal to the normalized intersection:

1. `shared = SharedArtistFinder.findSharedArtistNames(ownerA, ownerB)` — already built, already normalized (see §5).
2. Any name in `shared` with no active artist under `owner_key` → create it as `SEED`.
3. Any active artist under `owner_key` not in `shared` → set `REMOVED`.

Both transitions go through `catalog.ArtistActivationService`, per CLAUDE.md's standing rule that status changes never bypass it. That is not ceremony here — it is what makes `ArtistActivated`/`ArtistDeactivated` fire, which is what enqueues and cancels the scan jobs. **The job lifecycle is therefore inherited, not written.**

**Trigger:** reconcile when a participant's own list changes (`ArtistActivated` / `ArtistDeactivated` for an email that is `owner_a` or `owner_b` of some shared scan), plus once at creation. Event-driven matches the app's idiom and keeps the job set honest; a reconcile is two queries and a set diff, so the cost is trivial even against Rob's 1,269 artists.

## 3. Two guards — the part most likely to be got wrong

A shared-scan owner must receive a **reduced** job set. Both guards key off the same predicate, which lives once in `shared` (an OPEN module, reachable from both `scan` and `expansion`):

```java
SharedScanOwner.isSharedScanKey(owner)   // owner != null && owner.startsWith("shared:")
```

**Guard 1 — no expansion.** `ArtistActivated` currently enqueues `ExpandJob`s as well as `ScanJob`s. Left alone, a shared scan would start discovering member/similar/tribute candidates for an owner who is not a person, filling a Candidates queue nobody can see and burning LLM calls. The expansion enqueue path must skip shared-scan owners.

**Guard 2 — cheap sources only.** With scanning now asynchronous, the original spec's reason for excluding `BandSiteShowSource` (too slow for a synchronous request) no longer applies — but a better reason does: it falls back to `TourPageLlmService`, which is **billed per artist**. A shared scan should enqueue jobs only for `ticketmaster` and `bandsintown`. Same guard site, same predicate.

Both guards need their own tests. A silently-expanding shared scan would not be visible from any page — it would only show up as cost.

## 4. Containing the semantic widening

A synthetic owner must never surface as if it were a user. Before implementation, audit every query that enumerates or crosses owners, and pin the outcome with tests:

- `ArtistRepository#findByStatusIn` takes no owner — identify its callers and confirm none of them render or act per-owner in a way a shared scan would pollute.
- Anything that lists distinct owners.
- `NavModelAdvice#otherOwnerEmails()` derives from the `allowedEmails` **config**, so it cannot pick up a synthetic owner — verify, don't assume.
- Login: `SecurityConfig` authorises against `allowedEmails`, so `shared:<uuid>` can never authenticate. Assert it.

The Artists, Candidates, Rejected, and Shows pages are all scoped to `currentUser.email()`, so shared rows are invisible there **by construction** — that is a property worth a test, not a comment.

## 5. Name normalization is still load-bearing

Unchanged from the superseded spec, and still the single most likely way to build this and be silently wrong:

> Rob has 1,269 active artists, David has 5, and they genuinely share **4**. An exact-name join finds **1** — David's entries are lowercased on the second word ("Tom petty" vs "Tom Petty"). The intersection **must** go through `catalog.ArtistNameNormalizer`.

The guard test — "an exact-match join over this same data finds only 1 of 4" — carries over verbatim. `SharedArtistFinder` is already implemented against it and needs no change.

## 6. Access and the page

**Access:** a user may see a shared scan if their email is its `owner_a` or `owner_b`. Not admin-only — that was the previous design's rule and Rob explicitly replaced it. `AdminGuard` still gates *creating* a shared scan; viewing is participant-based.

**Page** (`/shared`): the shared scan's shows, its location settings form, and a manual "Scan now". Shows are ordinary `show_event` rows under `owner_key`, so the existing Shows table markup is reusable directly.

**Accessibility** — acceptance criteria, not nicety: labelled controls; results in a semantic `<table>` with `scope="col"` headers; wide tables inside the existing `.table-scroll` container; visible focus (note `select` is missing from `app.css`'s `:focus-visible` rule and must be added); announcements through the shared `#sr-status` region with `hx-swap-oob="innerHTML"`, which is load-bearing; and no custom JavaScript.

## 7. Failure and empty states

Each renders a distinct message — collapsing them was called out as the main UX failure mode and still is:

- **No shared scan configured** — nothing exists yet; offer creation (admin only).
- **Location not set / not geocodable** — the scan cannot run; say so rather than showing an empty list.
- **No artists in common** — the two lists don't overlap.
- **Shared artists, no shows yet** — distinguish *"scanning, nothing found yet"* from *"scanned, genuinely nothing there."* With a background poller there is now a real "not yet scanned" state the synchronous design never had.

## Non-goals

No N-way sharing. No multiple simultaneous locations. No hiding/unhiding shared shows (#166 applies to personal shows only for now). No cross-source dedup (#79 owns that). No band-site/LLM source. No notifications.

## Open question for review

**Should a shared scan's shows be excluded from a participant's personal Shows page?** They already are, by construction — different owner. Confirming that is the intent: you'd see a Chicago show on `/shared` but never mixed into your own upcoming-shows list.

## Rough shape of the work

Larger than the superseded design — roughly seven tasks: migration + entity; reconciler; the two guards; access + repository; the page; settings form; leak-audit tests. `AdminGuard` (done) and `SharedArtistFinder` carry over from the earlier plan unchanged.
