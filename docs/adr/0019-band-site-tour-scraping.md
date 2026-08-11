# 0019: Band official-site tour scraping (hybrid JSON-LD + LLM)

Date: 2026-08-11
Status: Accepted

## Context

ADR-0003 deferred scraping band/venue sites (no clean APIs, brittle, larger
effort) in favor of Ticketmaster + Bandsintown. Many artists -- especially
smaller/regional acts -- announce tour dates only on their own site, so those
shows are missed. This ADR reopens that deferral for band official sites.

## Decision

Add a third show source that scrapes each artist's official site.

- **Discovery:** the official-site URL comes from MusicBrainz's "official
  homepage" url-rel (structured, free), cached on `Artist.officialSiteUrl`.
  It is auto-used and shown/editable on `/artists` -- no approval gate; a wrong
  URL just yields no shows until corrected.
- **Extraction (hybrid):** fetch the page with JSoup; parse schema.org
  `Event`/`MusicEvent` **JSON-LD** first (structured, reliable, common); fall
  back to an **LLM** read of the page text (`TourPageLlmService`) for free-form
  pages. Shows are tagged `source = "band-site:<domain>"`.
- **Filtering (v1):** scraped shows are filtered by a loose city-name match to
  the user's location. Precise per-show geocoding + distance is deferred (#28).
- **Robustness:** any fetch/parse/LLM failure yields no shows -- a scrape never
  breaks the wider scan.

## Consequences

- Catches shows only announced on a band's own site; complements the two APIs.
- Scraping is inherently brittle (arbitrary layouts, bot-blocking); the
  JSON-LD-first + graceful-degradation design limits the blast radius.
- Adds a JSoup dependency and per-artist page fetches (throttled by the scan's
  cadence; the site URL is discovered once and cached).
- City-match filtering is looser than the ZIP-radius used for the APIs until
  #28 adds per-show geocoding.
- Revisits ADR-0003 for band sites; local-press/venue-calendar scraping remains
  deferred.
