# 0003: Show search sources

Date: 2026-08-06
Status: Accepted

## Context

Once the artist list is finalized, the service needs to find their upcoming
shows near Austin. Tour data is scattered across multiple platforms with no
single authoritative source — Ticketmaster, Bandsintown, Songkick, individual
band/venue websites, and local press (Austin Chronicle, Do512, KUTX) all cover
different, overlapping subsets of shows.

## Decision

Start with Ticketmaster's Discovery API (broad official coverage, clean JSON,
generous free tier) and Bandsintown's API (catches smaller club shows
Ticketmaster misses, artist-centric). Local Austin sources and direct venue
calendars are explicitly deferred — they lack clean APIs and would require
scraping, which is more brittle and a larger effort than the two API
integrations.

## Consequences

- Some shows — especially small club dates only announced via a venue's own
  site or local press — will be missed until local-source scraping is added.
- Two result sets must be deduplicated against each other (see `ShowAggregationService`).
- Bandsintown has no server-side radius filter, so its results are filtered
  client-side against the saved location, which is looser than Ticketmaster's
  native radius search.
- This is a known gap, tracked as a follow-up in the README rather than solved now.
