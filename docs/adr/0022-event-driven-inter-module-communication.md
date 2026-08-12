# 0022: Event-driven inter-module communication

Date: 2026-08-12
Status: Accepted

## Context

Some cross-module interactions are writes that require durable guarantees — for example,
the expansion module persisting candidate artists into the catalog. Direct service calls
between modules offer no durability if a downstream operation fails or is interrupted by
a restart. If the expansion process crashes after publishing candidates but before
receiving confirmation, those candidates may be lost, and no automatic retry will recover
them. Event-based communication, with a durable registry, decouples the modules and
provides durability across restarts.

## Decision

Adopt **Spring Modulith application events** with a **durable JPA event-publication
registry** (`spring-modulith-starter-jpa`). The `event_publication` table is
created by Flyway migration `V4__event_publication.sql`, consistent with ADR-0020's
principle that Flyway owns schema evolution and respects `ddl-auto=validate`.

In Phase A, the registry and table are in place but inert. Phase B will convert
cross-module writes to domain events:

- Modules publish domain events to represent significant state changes.
- Other modules subscribe via `@ApplicationModuleListener`, which is transactional and
  durable — if a listener fails or the application crashes mid-handling, the event is
  re-delivered from the registry on restart.
- Direct public-API calls remain appropriate for read operations and simple queries;
  events are reserved for writes that benefit from decoupling and durability.

## Consequences

- Incomplete event publications survive a crash or restart and are automatically re-delivered.
- Modules depend on events, not each other's write-side APIs, reducing coupling and making
  cross-module interactions explicit and reversible.
- A new table (`event_publication`) is added to the schema and must be created during
  deployment (Flyway handles this transparently).
- No functional behavior changes in Phase A — this is groundwork. Real event publishing
  and listening begin in Phase B.
