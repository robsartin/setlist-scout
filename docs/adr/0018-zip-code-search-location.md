# 0018: ZIP-code search location with Zippopotam.us geocoding

Date: 2026-08-10
Status: Accepted

## Context

The search location was city + state + radius (ADR-0007). Ticketmaster filtered by
city/state/radius; Bandsintown, which has no server-side geo filter, kept only shows
whose venue city string loosely matched — in practice a state-only cut, so it returned
shows from anywhere in the state. A ZIP code is a more precise, familiar way to say
"near here," and enables a real radius for both sources.

## Decision

Store the search location as a **ZIP code** plus a geocoded latitude/longitude on the
singleton `SearchSettings` row.

- **Geocoding:** a `GeocodingService` resolves the ZIP to lat/long (+ city/state for
  display) via **Zippopotam.us** (`api.zippopotam.us`) — free, no API key. It runs when
  settings are saved and as a startup backfill; failures degrade to empty so a scan is
  never broken.
- **Ticketmaster:** query by `postalCode` + `radius` (native support; no geocoding needed).
- **Bandsintown:** filter each event by great-circle (Haversine) distance from the ZIP's
  lat/long to the venue's coordinates, within the saved radius. With no coordinates
  (geocode failed), fall back to keeping all in-window shows.

Rejected: bundling a static US ZIP→lat/long dataset. It avoids an external call but adds
a ~2 MB data file to maintain; a free no-key API call (cached on the settings row) is
simpler and consistent with the app's other external-service integrations.

## Consequences

- Bandsintown gains a real radius filter (catches nearby suburbs), replacing the loose
  state-only match.
- Adds a dependency on Zippopotam.us, mitigated by graceful degradation and caching the
  result on `SearchSettings`.
- `city`/`state` become geocode-derived display fields rather than user input.
- Refines ADR-0007 (geographic scope, runtime-configurable) — the scope stays live-editable,
  now via ZIP.
