# 0017: Tribute/cover band expansion sources

Date: 2026-08-10
Status: Accepted

## Context

The seed list expands along member/lineup relations (ADR-0001) and taste-similar
artists (ADR-0002). A third dimension from the original vision — cover/tribute
bands (e.g. "The Iron Maidens" for Iron Maiden) — was never built. Tribute acts
are a distinct discovery problem: they do not appear in MusicBrainz lineup
relations and are not returned by Last.fm "similar artist" queries, so neither
existing source finds them.

## Decision

Discover tribute/cover bands with an LLM only, via a dedicated `TributeLlmService`
(a near-clone of the similar-artist LLM service). Run this expansion for `SEED`
artists only — not the full `SEED + APPROVED` set the other dimensions use.

Rejected alternative: name-pattern search against the show APIs (Ticketmaster /
Bandsintown for "<band> tribute", "tribute to <band>", etc.). It finds only
tributes that happen to be touring, misses creatively-named acts ("The Iron
Maidens" contains neither word), and blurs discovery into show search.

## Consequences

- Tribute acts flow through the pending-review gate (ADR-0004) like every other
  discovered artist; LLM false positives are absorbed there rather than polluting
  show results.
- Seed-only scope bounds LLM cost to the seed count and avoids proposing tributes
  for soloists or third-hop discoveries that realistically have none. It also
  sidesteps "tribute band of a tribute band" recursion once tributes are approved.
- To cover a non-seed band's tributes, promote it to a seed — a manual step, out
  of scope here.
