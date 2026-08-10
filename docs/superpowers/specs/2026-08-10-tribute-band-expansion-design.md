# Cover/Tribute Band Expansion — Design

- **Date:** 2026-08-10
- **Issue:** [#10](https://github.com/robsartin/setlist-scout/issues/10)
- **Status:** Approved (brainstorming)

## Problem

Setlist Scout expands a seed band list along two dimensions today:

- **Member/lineup relations** (`MEMBER_EXPANSION`) — MusicBrainz + Discogs.
- **Taste-similar artists** (`SIMILAR_EXPANSION`) — Last.fm + LLM cross-check.

A third dimension from the original vision is missing: **cover/tribute bands**
(e.g. "The Iron Maidens" for Iron Maiden). These acts are worth catching —
seeing a tribute play nearby is exactly the kind of show this tool exists to
surface — but they are a genuinely different discovery problem. Tribute acts do
not appear in MusicBrainz lineup relations and are not returned by Last.fm
"similar artist" queries, so neither existing dimension finds them.

## Decision

Add a third expansion dimension, `TRIBUTE_EXPANSION`, discovered **LLM-only** and
scoped to **seed bands only**, feeding the existing pending-review gate.

### Discovery: LLM-only

A new `TributeLlmService` asks Claude to name known tribute/cover acts for a
given band. It is a near-clone of `SimilarArtistLlmService`: same `WebClient`
setup, same package-private test-seam constructor, same `claude-sonnet-5` model,
same line-parsing regex, same graceful `onErrorReturn(Map.of())` degradation.

Only the public method and prompt differ:

```java
List<String> findTributeBands(String artistName, int count)
```

Prompt (intent, not verbatim):

> List up to N well-known tribute or cover bands that perform the music of
> "<artist>". Include only real, currently- or recently-active tribute acts.
> One name per line, no numbering, no commentary. **If you don't know any,
> return nothing.**

The explicit "return nothing" clause matters: most bands have zero notable
tributes, and without it the model tends to pad the list with invented names.

**Rejected alternative — name-pattern API search** (querying Ticketmaster /
Bandsintown for `"<band> tribute"`, `"tribute to <band>"`, `"<band> experience"`):
it only finds tributes that happen to be touring, misses creatively-named acts
("The Iron Maidens" contains neither word), and blurs discovery into show
search. The LLM path fits the existing candidate → review → show-search flow.

### Scope: seed bands only

The two existing dimensions expand over every `SEED` + `APPROVED` artist.
Tribute expansion runs over **`SEED` artists only**. Rationale:

- Tribute acts track famous originals — soloists and third-hop similar-artist
  discoveries rarely have tributes.
- Bounds LLM cost to the seed count.
- Sidesteps "tribute band of a tribute band" recursion once tribute acts are
  themselves approved.

A discovered band can be promoted to a seed manually if the user wants it
covered — out of scope for this change.

### Review gate unchanged

Each discovered name is stored via the existing `saveIfNew(...)` helper as
`PENDING_REVIEW` with source `TRIBUTE_EXPANSION` and a note like
`"tribute/cover act for <seed>"`. The existing `existsByNameIgnoreCase` dedup and
the `/artists` review page need no change — `artists.html` already renders source
+ note generically. Nothing reaches show search until the user approves it, so
LLM false positives are absorbed exactly as similar-artist false positives are
today (ADR-0004).

## Components

| Component | Change |
|---|---|
| `domain/ArtistSource` | Add `TRIBUTE_EXPANSION` enum constant + comment. |
| `service/TributeLlmService` | **New.** Clone of `SimilarArtistLlmService`; `findTributeBands(name, count)`. |
| `service/ExpansionService` | Inject `TributeLlmService`; add private `expandTributeBands(base)`; call it only when `base.getStatus() == SEED`. |
| `docs/adr/0017-tribute-band-expansion-sources.md` | **New.** Records LLM-only + seed-only decision. |

`ExpansionService.expandAll()` becomes:

```java
for (Artist base : baseArtists) {          // baseArtists = SEED + APPROVED
    expandMemberRelations(base);           // SEED + APPROVED
    expandSimilarArtists(base);            // SEED + APPROVED
    if (base.getStatus() == ArtistStatus.SEED) {
        expandTributeBands(base);          // SEED only
    }
}
```

## Data flow

```
seed band ──> TributeLlmService.findTributeBands()
          ──> ExpansionService.saveIfNew(name, TRIBUTE_EXPANSION, seed, note)
          ──> Artist(PENDING_REVIEW)  [existsByNameIgnoreCase dedup]
          ──> /artists review page ──(user approves)──> APPROVED
          ──> ShowAggregationService (Ticketmaster + Bandsintown) picks it up
```

## Error handling

Inherited from the `SimilarArtistLlmService` pattern: any API/network/parse
failure returns an empty list (`onErrorReturn(Map.of())` + null guards), so a
bad LLM call skips tribute discovery for that seed without failing the wider
expansion run.

## Testing (TDD — red first)

1. **`TributeLlmServiceTest`** — clone of `SimilarArtistLlmServiceTest`
   (MockWebServer): strips numbering/bullets, keeps plain lines, skips blanks,
   returns empty on missing content, returns empty on HTTP 500, **and** returns
   empty on an explicit "no known tributes" style response.
2. **`ExpansionServiceTest`** — extend to assert tribute expansion **runs for a
   `SEED` base** and is **skipped for an `APPROVED` base** (the seed-only rule),
   and that discovered names are saved with source `TRIBUTE_EXPANSION`.

All existing CI gates (build, format/lint equivalents, full test suite) stay
green before the PR goes up.

## Out of scope

- Name-pattern API search (rejected alternative above).
- Tribute-of-a-tribute expansion.
- Auto-promoting discovered bands to seeds.
- Any `/artists` template change.

## Rollout

Single branch `10-tribute-band-expansion` → PR to `main` → squash. No data
migration: `TRIBUTE_EXPANSION` is a new enum value; existing rows are unaffected.
