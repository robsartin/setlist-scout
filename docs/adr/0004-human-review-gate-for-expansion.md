# 0004: Human review gate for artist expansion

Date: 2026-08-06
Status: Accepted

## Context

Automated expansion (ADRs 0001, 0002) will produce false positives — loosely
related artists, name collisions, or LLM suggestions that don't actually fit.
If expanded names feed directly into show search, the show list quality
degrades and irrelevant results accumulate silently.

## Decision

Newly discovered artists are stored with `PENDING_REVIEW` status and excluded
from show search entirely. They only move to `APPROVED` (and start being
searched) after explicit action on the `/artists` page. Rejected suggestions
are kept (as `REJECTED`) rather than deleted, so the same bad suggestion isn't
re-proposed on the next expansion run.

## Consequences

- Expansion and show search are decoupled — running expansion never changes
  what shows up in results until artists are reviewed.
- Adds a manual step after each expansion run; the list doesn't grow unattended.
- Requires persisting rejected names indefinitely to avoid re-suggesting them,
  which is a small, acceptable amount of permanent state.
