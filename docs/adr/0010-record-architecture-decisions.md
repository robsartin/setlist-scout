---
status: Accepted
date: "2026-08-07"
topic: record-architecture-decisions
tags: [universal, adr, process]
supersedes: []
related: [keep-documentation-current]
---
# 10. Record architecture decisions with ADRs

## Context

Architecturally significant decisions — choices that shape structure, dependencies,
interfaces, or the way the team works — need a durable record. Without one, the *why*
behind a decision is lost: newcomers can't tell intent from accident, and past reasoning
gets re-litigated or silently reversed.

## Decision

We record architecturally significant decisions as **Architecture Decision Records
(ADRs)**, following Michael Nygard's lightweight convention.

- ADRs live in `docs/adr/`, one Markdown file per decision, named
  `NNNN-kebab-case-title.md` and numbered sequentially.
- Each ADR has a **Date**, a **Status** (`Proposed`, `Accepted`, `Deprecated`, or
  `Superseded by [NNNN](...)`), and the sections **Context**, **Decision**, and
  **Consequences**.
- ADRs are **immutable once Accepted.** A decision that changes is not edited; a new ADR
  supersedes it, and the old one's status is updated to point at its successor. This
  preserves a truthful history of what was decided and when.
- Reserve ADRs for decisions that are costly to reverse or that a future reader would
  otherwise find surprising; trivial choices don't need one.

## Alternatives considered

- **Commit messages and PR descriptions as the record** — the reasoning exists somewhere,
  but it isn't browsable by topic and gets buried as history grows.
- **A wiki or external docs tool** — lives apart from the code, so it drifts and goes stale
  instead of shipping in the same PR as the decision it documents.
- **Editing a decision in place when it changes** — overwrites the original reasoning,
  losing the truthful timeline of what was decided and when.

## Consequences

- The reasoning behind significant choices is preserved and discoverable next to the code.
- Superseding rather than editing keeps a truthful timeline at the cost of some duplication.
- Contributors must judge when a decision is significant enough to warrant an ADR.
