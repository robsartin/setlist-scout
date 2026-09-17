# Films 1/3: a cinema is a venue, a screening is a show — design (#284)

## What is being decided

Whether Setlist Scout can show what is screening at cinemas the owner follows, and what the minimum
change to the model is to do that honestly — without inventing the film graph, and without polluting
the artist catalog with film titles.

This is the first of three sub-projects. It delivers on its own and it proves the listing pipeline
before any graph work is invested.

| # | Sub-project | Delivers | Depends on |
|---|---|---|---|
| **1** | **this spec** — cinema as a venue, screening as a show | what's screening near me | nothing |
| 2 | WORK entity + person→work edges from Wikidata | which films a person made | a Wikidata source |
| 3 | match screenings to the people followed | *"Goodfellas — because you follow Scorsese"* | 1 and 2 |

## The evidence

**Film is the first thing that breaks the event shape.** Adding `COMEDY` to `Show.Kind` needed no
model change, because a comedian *performs*: one hop from the followed thing to the thing on stage.
Film separates them — you follow Scorsese, *Goodfellas* screens. Person → work → screening.

**That break is #2 and #3's problem, not this one.** Here the film is the subject of the event exactly
as a band is, so `show_event`'s shape and its `(owner, artist_name, event_date_time, venue_name)`
natural key are unchanged and sufficient: two films at one cinema differ by title, one film twice
differs by showtime.

> An earlier reading of this claimed the natural key would collide. It would — but only if the
> *director* were stored in `artist_name`, which nothing here does. Recorded because the wrong version
> of that claim is the kind that survives into a design if left uncorrected.

**Segue already settled the vocabulary, and it is worth not re-deciding.** `NodeKind`'s own doc:

> *"'Musician', 'novelist', 'director' are ROLES, and roles are expressed as edges (PERFORMED,
> AUTHORED, DIRECTED), never as node types... If you ever feel the need to add MUSICIAN or FILM here,
> the model is being used wrong."*

Segue's `EdgeTypes` already ships `ACTED_IN`, `DIRECTED`, `WROTE_SCREENPLAY_FOR`, `COMPOSED_FOR`,
`BASED_ON`. **#2 should adopt that vocabulary rather than invent a parallel one.** Nothing in this
sub-project needs it, and nothing in this sub-project should pre-empt it.

**Measured state, 2026-09-16.** One followed venue (Cap City Comedy Club); `Venue` carries owner,
name, normalized name, calendar URL, created-at, and no kind; the add form is name + URL;
`TourPageLlmService` is LLM-based with a 200,000-char cap and already digests Cap City's 149,420-char
calendar.

## The design

### 1. `Venue.kind` — `LIVE` or `CINEMA`

A new non-null column defaulting to `LIVE`, so the one existing row keeps today's behaviour.
Chosen explicitly on the add form.

**One field, three consequences** — which extraction prompt runs (§2), whether catalog candidates are
created (§4), and how screenings become visible (§5). Stated plainly because it means the flag must be
right and cannot be a guess.

*Rejected: infer it from the page.* A wrong inference silently creates hundreds of film-titles-as-
artists, which is the failure §4 exists to prevent. An explicit choice at add time is one click and
cannot be wrong quietly.

### 2. `Show.Kind` gains `FILM`, and a film-mode prompt

A screening is the existing row shape:

```
artist_name     = "Goodfellas"     the film is the event's subject
event_date_time = the showtime
venue_name      = "AFS Cinema"
source          = "venue:<host>"
kind            = FILM
```

`TourPageLlmService` currently frames the page as "a tour/shows page belonging to *<name>*". A
`CINEMA` is framed as a screening calendar and asked for title, showtime and release year. This is a
prompt change on an existing LLM extractor, not a new data source.

### 3. Release year

One nullable column on `show_event`, one prompt field.

Two reasons, and the second is the load-bearing one. It disambiguates the page — repertory cinemas
screen both *Dune (1984)* and *Dune (2021)*. And it is the identity key #2 needs: a film's identity is
title+year, which `UNIQUE (owner, normalized_name)` structurally cannot express. Capturing it now is
nearly free; backfilling it later means re-scraping calendars whose screenings have already passed.

Nullable because a listing may not state it, and a missing year must not drop a screening.

> **Addendum, 2026-09-17 (#286).** "A film's identity is title+year" holds for the screening side,
> which is all this sub-project touches, but not for the Wikidata side #2 reads from: `P577`
> (publication date) is multi-valued — one per country release — and 38 of Martin Scorsese's 112
> films come back with more than one distinct year, 4 with none. #286 therefore makes a WORK's
> identity its QID and demotes title+year to the *match key* between a screening and a work. The
> reason for capturing the year here is unchanged: it is still what the screening side must match
> on, and still unbackfillable once a showtime has passed.

### 4. A cinema creates no catalog candidates

`VenueScanRunner` suppresses the `VenuePerformerSeen` publish when the venue is a `CINEMA`.

Today that event turns every performer at a followed venue into a `PENDING_REVIEW` artist. Pointed at
a cinema it would put hundreds of film titles a year into the artist review queue beside the
musicians — the same pollution class as #253 (LLM prose stored as artists) and #255 (comma-split name
fragments). Films become catalog entities in #2, as WORKs, or not at all.

### 5. Visibility

`ShowController#visibleToOwner` keeps a `venue:` show only when its performer is an ACTIVE artist.
With no catalog row for films (§4), that rule hides **every** screening. It becomes:

> a `venue:` show is visible if `kind = FILM`, **or** the performer is an active artist

The rule stays scoped to `venue:` sources exactly as today — it is the `venue:` branch that changes,
not the filter as a whole. A Ticketmaster or band-site row is unaffected whatever its kind, which
matters because nothing prevents a future non-venue source emitting `FILM`.

**This is deliberately the opposite of the cross-filter #206 gave live venues**, and the owner's
explicit decision. The reason it is defensible here and was not there: the cross-filter matches
nothing for a film until #3 exists, so the alternative is showing nothing at all. #3 narrows it back
to "because you follow X". Volume is controlled by which cinemas get followed — an arthouse
repertory calendar is a different proposition from a first-run multiplex.

## Alternatives rejected

- **Store all screenings, display none until #3.** Keeps the Shows page clean, but #1 then delivers
  nothing visible and stops being a standalone increment that can prove the pipeline.
- **Only screenings the listing marks "special"** (repertory, 35mm, Q&A). Closest to what is worth
  knowing, but it invents a judgement cinemas do not publish consistently, and a missed flag is a
  silently dropped screening.
- **A per-cinema follow-everything flag.** Most flexible, but adds a setting and a decision for every
  cinema; revisit if the volume actually bites.
- **Films as `artist` rows with a kind discriminator.** Would make `show_event.artist_id`,
  `artist_edge` and the review gate work unchanged — but `UNIQUE (owner, normalized_name)` would merge
  *Dune (1984)* and *Dune (2021)*. This is the #266–#268 normalizer work in reverse: those merged
  things that should merge; a WORK needs two things that must not. Deferred to #2, which owns it.

## Verification

- **A `CINEMA` scrape creates zero artist rows.** The assertion that keeps films out of the catalog.
- Screenings persist with `kind = FILM` and their year; a screening with no stated year still persists.
- Screenings reach the Shows page for a followed cinema.
- **A `LIVE` venue behaves exactly as today** — Cap City Comedy Club must not shift by a single row.
  This is the regression guard on a shared code path, and it is the test most likely to catch a
  mistake in §1's flag.
- The `kind` column defaults existing venues to `LIVE`, asserted against a real migration rather than
  an entity default.

Everything above is exercised against real Postgres via Testcontainers, and the page assertions drive
the real Thymeleaf template — a Thymeleaf expression error compiles cleanly and 500s at runtime.
