# 0024: Event and durable-write invariant

Date: 2026-08-14

Status: Accepted

## Context

ADR 0022 adopted Spring Modulith application events with a durable JPA
event-publication registry; ADR 0023 built the per-unit `scan_job`/`expand_job`
work model on top of it. Phase B turned that groundwork into real flows:
`@ApplicationModuleListener` handlers that are `@Async` and
`@TransactionalEventListener(AFTER_COMMIT)`, wired to durable per-unit jobs
that are enqueued idempotently as those listeners run.

Building those flows produced two real production bugs, each traced during
the #95 design review (`docs/reviews/2026-08-13-phase-b-design-review.md`,
"The event & durable-write invariant" section):

- **PR3a:** an event was published from a call site with no active,
  *committing* transaction around it. Modulith never wrote the
  `event_publication` row, so the listener silently never fired — no
  exception, no log, just a domain event that quietly did nothing.
- **PR3b:** a listener's idempotent durable write used the
  `existsBy...` → `save` → `catch(DataIntegrityViolationException)` pattern.
  On an IDENTITY-keyed table the `INSERT` executes eagerly, so a real unique-
  constraint race threw mid-transaction. Catching the exception in Java did
  not un-poison the Postgres connection: any later statement in that same
  transaction — including Modulith's own AFTER_COMMIT write that marks the
  event handled — then failed too, leaving the event incomplete and
  redelivered.

Both bugs came from violating a rule that isn't obvious from the Spring
Modulith or Spring `@Transactional` documentation on its own: it only shows
up once you combine AFTER_COMMIT async listeners with durable per-row
idempotency under Postgres. Both are cheap to reintroduce by accident in a
new publisher or listener unless the rule is written down where the next
change gets made.

## Decision

Adopt two invariants for every event publisher and every
`@ApplicationModuleListener` in `shared.events` and its consumers:

**1. Publish only inside a committing transaction.** A `publishEvent` call
with no active, committing transaction around it is silently dropped —
Modulith never writes the `event_publication` row and the listener never
fires. Every publisher must be `@Transactional`, or must wrap the publish in
a `TransactionTemplate` when it also needs to do slow work (an external API
call, an LLM call) that must not run inside the transaction. In that case,
query the slow external source *outside* any transaction first, then publish
each result in its own short transaction — see `ExpandUnitRunner` for the
pattern.

A Modulith `Scenario` test wraps the publish call in its own transaction and
will pass even when the production publish site does not commit — it is a
false green for this specific bug. Every event flow needs a real-path test
that drives the actual production publisher (a full Spring context test
against the real service/listener/DB path), not only a `Scenario` test.

**2. Idempotent durable writes inside a listener must be DB-level upserts,
never existsBy-then-catch.** A listener runs its whole body in one
transaction. On an IDENTITY-keyed table, checking existence first and
catching the constraint violation on save does not prevent the underlying
`INSERT` from executing eagerly — a real race still throws
`DataIntegrityViolationException` and poisons the Postgres transaction
("current transaction is aborted"). Catching that exception in Java does not
un-poison the connection: any later statement in the same transaction,
including Modulith's own AFTER_COMMIT completion write, then fails, and the
event is left incomplete and redelivered. Use
`INSERT ... ON CONFLICT (...) DO NOTHING`, matching the constraint's exact
columns/collation, for every durable write a listener makes — not only
multi-row loops.

## Consequences

- Every event publisher in `shared.events` and its module implementations is
  either `@Transactional` or explicitly uses `TransactionTemplate` around the
  publish call; slow external calls are moved outside the transaction that
  does the publishing.
- Every idempotent durable write inside an `@ApplicationModuleListener` is a
  DB-level `ON CONFLICT DO NOTHING` upsert, not an existsBy/save/catch
  sequence.
- Every event flow has at least one real-path test driving the actual
  production publisher end-to-end (not only a `Scenario` test), so a broken
  publish site fails a test instead of shipping as a silent no-op.
- A new event or listener that doesn't follow this invariant risks
  reintroducing the PR3a (silently dropped event) or PR3b (poisoned
  transaction, self-healing but noisy redelivery) class of bug.
- Recorded in code at `shared/events/package-info.java`, pointing back here.
- Builds on [0022](0022-event-driven-inter-module-communication.md)
  (event-driven inter-module communication) and
  [0023](0023-per-unit-event-driven-scan-work-model.md) (the per-unit job
  model these listeners drive).
