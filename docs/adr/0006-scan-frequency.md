# 0006: Scan frequency

Date: 2026-08-06
Status: Accepted

Superseded by [0023](0023-per-unit-event-driven-scan-work-model.md)

## Context

The scheduled job re-runs expansion and show search on an interval. Too
frequent wastes API quota (Ticketmaster's free tier is 5,000 calls/day,
MusicBrainz is rate-limited to ~1 req/sec) for little benefit, since tour
announcements don't happen every day. Too infrequent means stale results.

## Decision

Run every few days by default (`setlistscout.scan-interval-ms`, default
259200000ms / 3 days), configurable via environment variable without a code
change. Manual "Scan now" / "Run expansion now" buttons are also available on
the web page for on-demand runs.

## Consequences

- New show announcements can take up to a few days to appear unless manually
  triggered.
- Keeps well within free-tier API rate limits even as the artist list grows.
- The interval lives in config, not code, so it can be tuned later without a
  redeploy of application logic (just an env var change + restart).
