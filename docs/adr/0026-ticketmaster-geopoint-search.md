# 0026: Ticketmaster geoPoint search, not postalCode

Date: 2026-08-15

Status: Accepted

## Context

[0018](0018-zip-code-search-location.md) settled on querying Ticketmaster's Discovery API by
`postalCode` + `radius` directly, reasoning that Ticketmaster's native postal-code support meant
no geocoding step was needed for that source.

That assumption was wrong. Investigating #152 (one of the app's two production users had gotten
zero Ticketmaster results for his entire time on the app) found that Ticketmaster's postal-code
index only covers ZIPs where it has market presence -- it does not error, and it does not fall
back to a nearby indexed ZIP, for the ones it doesn't cover; it just matches nothing. The response
is a well-formed HTTP 200 with `totalElements: 0`, indistinguishable from a legitimate "no shows
near you." The affected owner's ZIP (a small town in NH) is one such unindexed ZIP; Rob's own ZIP
(downtown Austin) happens to be indexed, which is why this went unnoticed until a second real
user's data exposed it.

Verified against the live API with the affected owner's exact search parameters -- the only
variable changed between the two calls is `postalCode` vs `geoPoint`:

- near his ZIP, 200mi radius, `postalCode`: 0 events
- near his ZIP, 200mi radius, `geoPoint`: 2547 events

## Decision

Send Ticketmaster a `geoPoint` -- a base32 geohash -- instead of `postalCode`, built from the same
lat/long [0018](0018-zip-code-search-location.md) already geocodes from the owner's ZIP via
Zippopotam.us. A new `scan/Geohash` utility (same shape as `scan/GeoDistance`: small, pure,
static, no I/O) encodes at precision 9 (about 5m of resolution), the precision verified against
the live API above.

`TicketmasterService.searchShows` falls back to the old `postalCode` + `radius` query only when
`latitude`/`longitude` are null (geocoding failed for that owner). That's degraded -- back to the
original ZIP-coverage gap -- but no worse than before this fix, and the fallback never goes
further and drops the location filter entirely, which would flood the owner with shows from
anywhere in the world.

## Alternatives considered

- **Look up a nearby ZIP Ticketmaster does index and substitute it.** Rejected -- no reliable way
  to know which ZIPs are indexed short of trial and error against the live API per owner, and it
  would search from the wrong point.
- **Fall back to no location filter when a `postalCode` search returns zero results.** Rejected --
  a legitimate zero-result search is indistinguishable from an unindexed ZIP, so this would
  occasionally flood an owner with worldwide results for an artist that genuinely has no shows
  nearby.

## Consequences

- Refines [0018](0018-zip-code-search-location.md)'s Ticketmaster bullet: the ZIP geocode is now
  load-bearing for Ticketmaster too, not just Bandsintown's distance filtering.
- Fixes a silent full-source outage for any owner whose ZIP falls outside Ticketmaster's
  postal-code index, not just the one who surfaced it -- a correctness improvement for every
  future user, not a one-off patch.
- Adds `scan/Geohash`, a second small geo utility alongside `scan/GeoDistance`.
- The degraded `postalCode` fallback preserves today's (already-imperfect) behavior for the rare
  case geocoding itself fails, rather than introducing a new failure mode on top of it.
