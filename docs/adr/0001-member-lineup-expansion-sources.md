# 0001: Member/lineup expansion sources

Date: 2026-08-06
Status: Accepted

## Context

The seed band list needs to expand to include individual members' other
projects and side bands (e.g., a Tom Petty fan should also see Mike Campbell
& the Dirty Knobs). This requires structured data about band lineups and
member relationships, not taste-based similarity.

Candidate sources: MusicBrainz (free, open, explicit "member of"/"collaborator"
relationships), Discogs (similar relationship data via artist "members"/"groups"/
"aliases" fields, sometimes covers acts MusicBrainz misses), Spotify's related-
artist data (relationship type is unclear/inconsistent, access has changed over
time), Wikipedia/DBpedia scraping (unstructured, brittle).

## Decision

Use MusicBrainz as the primary source and Discogs as a secondary source, querying
both and merging results. MusicBrainz has the cleanest structured relationship
data; Discogs fills gaps, particularly for older or regional acts.

## Consequences

- Two API integrations to maintain instead of one.
- MusicBrainz enforces a strict ~1 req/sec rate limit for unauthenticated use,
  so expansion runs sequentially and can be slow for a large active list.
- Discogs requires a personal access token (free, but one more secret to manage).
- Neither source will have complete data for very obscure acts (e.g. some 1980s
  regional bands) — those may need to stay manually curated in the seed list.
