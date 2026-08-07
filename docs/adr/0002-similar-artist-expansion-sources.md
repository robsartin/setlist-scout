# 0002: Similar-artist expansion sources

Date: 2026-08-06
Status: Accepted

## Context

Beyond lineup relationships, the list should expand with taste-based "similar
artists" — bands that sound alike but share no members (e.g. Dawes → The Head
and the Heart). This needs a genre/taste-similarity signal, which structured
lineup databases (MusicBrainz, Discogs) don't provide.

Candidate approaches: Last.fm's `artist.getSimilar` (free, based on real
listener/tag data, well-established), Spotify's related-artist endpoint (access
has become inconsistent for third-party apps), and LLM-generated suggestions
(flexible and good on niche/regional acts thin databases don't cover, but not
verifiable against real listening data and can hallucinate).

## Decision

Use both Last.fm and an LLM-generated list, and cross-check them: a name
returned by both sources is flagged higher-confidence; a name from only one
source is still included but marked as a single-source match. Confidence is
surfaced to the human reviewer rather than used to silently filter results.

## Consequences

- Requires both a Last.fm API key and an Anthropic API key.
- Higher API cost/latency per artist (two calls instead of one) during expansion.
- Confidence flagging adds a small amount of complexity to `ExpansionService`,
  but gives the human reviewer useful signal instead of an undifferentiated list.
- LLM suggestions can still be wrong or stale; the review gate (ADR 0004) is the
  safety net for that, not the cross-check itself.
