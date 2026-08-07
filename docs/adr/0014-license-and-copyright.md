---
status: Accepted
date: "2026-08-07"
topic: license-and-copyright
tags: [universal, legal]
supersedes: []
related: []
---
# 14. Declare an explicit license and copyright

## Context

A repository with no license is "all rights reserved" by default — others (and future us)
have no clear terms for use, and intent is ambiguous. The choice of terms is a decision
worth recording, not leaving implicit.

## Decision

Every repository declares its terms explicitly:

- A **`LICENSE` file** at the repository root stating the chosen license.
- **Copyright** attributed to `setlistscout`'s owner.
- The specific license is chosen deliberately per repository based on its intended use
  (permissive for open libraries, proprietary/all-rights-reserved for private work) and
  recorded here when it is anything other than the repository's stated default.

## Alternatives considered

- **No `LICENSE` file, implicit all-rights-reserved** — leaves usage terms ambiguous, the
  exact problem this ADR exists to close.
- **One organization-wide default license applied uniformly to every repo** — doesn't fit
  the mix of open libraries and private, proprietary work we actually maintain.
- **Dual-licensing by default** — adds complexity most repositories don't need; reserved for
  the rare case that genuinely calls for it, not the baseline.

## Consequences

- Use, distribution, and contribution terms are unambiguous from day one.
- Changing the license later is a deliberate, superseding decision — not a silent edit.
- Third-party dependencies must be checked for license compatibility with the chosen terms.
