/**
 * Cross-module domain events, delivered via Spring Modulith's durable
 * {@code @ApplicationModuleListener} (async, {@code AFTER_COMMIT}) mechanism.
 * <p>
 * Every publisher and listener in this package must honor the event and
 * durable-write invariant recorded in
 * <a href="../../../../../../../../docs/adr/0024-event-and-durable-write-invariant.md">ADR 0024</a>:
 * <ol>
 *   <li><b>Publish only inside a committing transaction.</b> A {@code publishEvent} call with no
 *   active, committing transaction around it is silently dropped -- the {@code event_publication}
 *   row is never written and the listener never fires. Publishers must be {@code @Transactional},
 *   or must wrap the publish in a {@code TransactionTemplate} after querying any slow external
 *   source outside the transaction. A Modulith {@code Scenario} test is a false green here; every
 *   event flow needs a real-path test driving the actual production publisher.</li>
 *   <li><b>Idempotent durable writes inside a listener must be DB-level upserts, never
 *   existsBy-then-catch.</b> A listener's whole body runs in one transaction; on an IDENTITY-keyed
 *   table a real unique-constraint race poisons that Postgres transaction even if the exception is
 *   caught, which then fails Modulith's own AFTER_COMMIT completion write and leaves the event
 *   redelivered. Use {@code INSERT ... ON CONFLICT (...) DO NOTHING} for every idempotent durable
 *   write a listener makes.</li>
 * </ol>
 */
package com.robsartin.setlistscout.shared.events;
