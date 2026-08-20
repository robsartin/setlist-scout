# Follow a venue: calendars as a show source

**Status:** design, awaiting review.
**Issue:** #206.
**Depends on:** the #208 branch (`208-venue-calendar-scan`), which makes `BandSiteScraperService`
return a **performer and a kind per show** and makes the LLM extraction path work at all (#211).
Without those, a venue calendar yields nothing usable.

## The problem, measured

Some Austin presenters simply are not on Ticketmaster, so no amount of better matching reaches them:

- **Austin Classical Guitar** — 0 Ticketmaster events nationwide.
- **Austin Symphony Orchestra** — two attraction records, both 0 upcoming events; its season sells
  through its own box office. The one ASO date Ticketmaster *does* carry was recovered separately by
  #207.
- **Cap City Comedy Club** — a venue, so Ticketmaster would never carry it as an attraction at all.

Meanwhile Cap City's own calendar is rich and readable: `capcitycomedy.com/events` is server-rendered,
and a live extraction run produced **181 shows spanning 2026-08-20 to 2027-11-10**, every one with a
named performer and a COMEDY classification.

## The core decision: a venue is not an artist

Modelling a venue as an `artist` row works for scanning but is wrong in a way that costs something
concrete: artist activation fans out to **all five expansion sources**, which would go ask Discogs,
MusicBrainz, Last.fm, and two LLM calls *who are the members of Cap City Comedy Club* and *what
tribute bands perform its music*. There is no per-artist expansion opt-out — the only suppression
that exists is owner-level, for shared-scan keys.

So `venue` is its own entity. **A venue never expands.** It is a followed calendar, nothing more.

## What a venue scan produces

The volume forces a decision. 181 shows from one venue against **48 shows total** today would make
the shows page 80% comedy listings by acts the owner does not follow.

**Venue shows are mixed into the shows page, but only displayed when the performer is an artist the
owner actively follows.** Unmatched performers are not discarded — they become candidates.

### Flow

1. A claim-lease poller (ADR-0023, `FOR UPDATE SKIP LOCKED`) claims a due `venue_scan_job`.
2. `BandSiteScraperService` fetches the venue's `calendar_url` — JSON-LD first, LLM fallback —
   returning `(date, venue, city, performer, kind)` per show.
3. **Every** extracted show is persisted to `show_event`, with `artist_name` = the extracted
   performer and `source` = `venue:<host>`.
4. Each performer is matched against the owner's catalog with `catalog.ArtistNameMatcher` — the
   app's single definition of name equality (CLAUDE.md). No hand-rolled normalization.
5. Unmatched performers are published as an event; `catalog` creates a `PENDING_REVIEW` artist with
   a new `ArtistSource.VENUE_EXPANSION`, landing it on the existing Candidates page.

Approving a candidate therefore makes their already-stored shows appear **immediately**, with no
rescan and no waiting for the ~14-day cadence.

### The filter that would otherwise be a bug

"Display only shows whose performer is an active artist" must **not** be a blanket rule.

Ticketmaster shows store the *event title* in `artist_name` (`TicketmasterService`: `label` is the
event name, falling back to the artist name only when blank). The show recovered by #207 is stored as
`A Very Merry Symphony ft. Austin Symphony Orchestra` — which is not, and will never be, a name in
the catalog. A blanket active-artist filter would hide **every Ticketmaster show the owner has**.

The active-artist check applies **only to rows whose `source` starts with `venue:`**. Artist-sourced
shows continue to display exactly as they do today. `ShowController#populateShows` already builds a
`Set<String>` of names from `artistRepository` for tribute highlighting, so this follows an
established pattern in the same method rather than inventing one.

## Data model

**`venue`** — `id`, `owner`, `name`, `normalized_name`, `calendar_url`, `created_at`.
Unique on `(owner, normalized_name)`, matching the `artist` precedent from #179/V21.

**`venue_scan_job`** — mirrors `scan_job`'s claim-lease shape (`status`, `attempts`, `last_error`,
`claimed_at`, `next_due_at`, `last_run_at`) keyed on `venue_id`.

It **deliberately does not extend `AbstractJob`**: that mapped superclass requires a non-null
`artist_id`, and a venue job has no artist. This is the same reasoning that kept `ArtistImport` off
`AbstractJob` in #177 — mirror the shape, do not inherit a column it cannot satisfy.

Both tables need Flyway migrations **and** matching entity mappings, since `ddl-auto: validate` is on.
Note migrations live in **two** directories (SQL in `src/main/resources/db/migration/`, Java in
`src/main/java/db/migration/`); check both when picking the next version, and use `sort -V`.

## Module placement

`venue` lives in the **`scan`** module: it is a show source, and `scan` already owns `ShowController`
and the scraper. Candidate creation crosses into `catalog` **by event**, never by direct write —
`ModularityTests` enforces this, and per ADR-0024 the publish must happen inside a committing
transaction or the listener silently never fires.

## Managing venues

A `/venues` page mirroring `/artists`: name + calendar URL, the list, and remove. Small — one
controller, one template, one repository. Put the add form **above** the list from the start (#175
had to move the artist one retroactively), keep real `<label>` elements, and follow the app's
**no custom JavaScript** rule.

The page should show each venue's `last_run_at` and the number of shows it has contributed. Without
that, a venue whose calendar quietly stops parsing looks identical to a venue with no shows — which
is exactly how #211 hid for as long as it did.

## Decisions taken

- **No radius filter on venue shows.** The owner chose the venue deliberately; filtering it by
  distance would second-guess that. This differs from `BandSiteShowSource`, which geocode-filters
  because a band tours everywhere — a venue does not move.
- **Venues get no expansion jobs at all.**
- **Approved venue candidates are ordinary artists thereafter**, including expansion.
- **Venue scans use the same cadence as artist scans.**
- **The show's `venue_name` comes from the extraction, not the venue record.** Cap City's own
  calendar distinguishes "Cap City Comedy Club" from "The Red Room at Cap City", and that room
  detail is worth keeping.

## Non-goals

No venue discovery or search — the owner supplies a URL. No per-venue scheduling. No headless
browser rendering. No venue-level browse page beyond the management list; a venue's calendar is not
a destination, it is a source.

## Known limitation: Austin Symphony is still blocked

This design makes ASO **addressable but not solvable**. Probed 2026-08-19:

| URL | result |
|---|---|
| `austinsymphony.org/concerts/` | 200, 2,926 chars, **0 dates** — a navigation page |
| `austinsymphony.org/events/` | redirects to `my.austinsymphony.org` — 2,286 chars, **0 dates** |

That is a client-rendered ticketing app. Jsoup does not execute JavaScript, so neither the JSON-LD
path nor the LLM path has anything to read. Solving ASO needs headless rendering — a large
dependency and a real operational cost — and belongs in its own issue. **Cap City is not blocked by
this** and should ship first.

## Testing

- A venue scan persists every extracted show, and shows whose performer matches an active artist are
  the only venue-sourced rows displayed.
- **A Ticketmaster show whose `artist_name` is an event title still displays.** This is the
  regression guard for the filter described above; without it the filter silently empties the page.
- An unmatched performer becomes exactly one `PENDING_REVIEW` artist with source `VENUE_EXPANSION`,
  and a second scan does not create a duplicate.
- Approving that candidate makes its already-stored shows display, with no rescan.
- Performer matching is owner-scoped, and uses `ArtistNameMatcher` semantics (case, punctuation, and
  unicode variants match; distinct names do not).
- A venue with an unreachable or unparseable calendar records the failure and does not break the
  wider scan.
- Rejecting a venue candidate keeps their shows hidden.
- Integration coverage via Testcontainers, per the app's existing pattern; **no live network calls**
  — `BandSiteScraperService` and the LLM base URL are both injectable seams (#184).
