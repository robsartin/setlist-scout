---
status: Accepted
date: "2026-08-07"
topic: keep-documentation-current
tags: [universal, documentation, process]
supersedes: []
related: [record-architecture-decisions, pr-based-trunk-workflow]
---
# 13. Keep developer and user documentation current

## Context

Documentation that lags the code is worse than none — it misleads. When docs are treated
as a separate, later task, they rot. We want them to move with the change that affects them.

## Decision

Documentation is part of the definition of done. A change that affects behavior, setup,
interfaces, or usage updates the relevant docs **in the same pull request**:

- **Developer documentation** — how to build, test, run, and reason about the system
  (README, architecture notes, ADRs).
- **User documentation** — how someone uses the software, kept accurate to what ships.

A PR that changes behavior without touching the docs it affects is incomplete.

## Alternatives considered

- **Periodic documentation sprints** — docs still lag between sprints, rotting for however
  long the interval runs.
- **A follow-up "update docs" ticket filed alongside the code change** — competes with new
  feature work for priority and routinely never gets done.
- **Code as the only source of truth, no separate docs** — fine for implementation detail,
  but leaves setup and usage undiscoverable to newcomers.

## Consequences

- Docs stay trustworthy because they change alongside the code, under the same review.
- Each PR carries a little more work; the payoff is documentation people can rely on.
- Reviewers watch for the docs half of a change, not just the code half.
