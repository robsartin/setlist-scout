---
status: Accepted
date: "2026-08-07"
topic: pr-based-trunk-workflow
tags: [universal, process, git]
supersedes: []
related: [mikado-method-for-changes, ci-is-the-merge-gate, keep-documentation-current]
---
# 11. Integrate via a PR-based trunk workflow

## Context

We want `main` to stay releasable at all times, changes to be reviewable in coherent
units, and history to be legible. Committing directly to `main` or piling unrelated work
onto a long-lived branch works against all three.

## Decision

All work flows through short-lived branches and pull requests:

- Start from an **issue** describing the work.
- Create a **branch** off `main` named for the issue (e.g. `123-short-description`).
- Make focused **commits** on the branch.
- Open a **pull request** into `main`; **squash-merge** it so each change lands as one
  coherent commit.
- **Never commit directly to `main`.**
- **No new development on a PR that is already open and marked ready for review.** If more
  work is needed, branch off it or return the PR to draft first — reviewers should not be
  chasing a moving target.

## Alternatives considered

- **Long-lived feature branches / GitFlow** — merges become large, infrequent, risky events
  instead of the small continuous ones this ADR is built to produce.
- **Direct commits to `main` with post-hoc review** — `main` is no longer guaranteed
  releasable, and history isn't organized around reviewable, coherent units.
- **Trunk-based development without PRs, via full-time pairing** — drops the asynchronous
  review step entirely, which doesn't hold up without constant pairing.

## Consequences

- `main` is always a series of reviewed, squashed, releasable commits.
- Review happens on stable diffs, not shifting ones.
- The one accepted exception is the initial bootstrap commit of an empty repository, which
  has no base to branch from.
- Every change waits on review before it can merge, and keeping branches short-lived takes
  ongoing discipline.
