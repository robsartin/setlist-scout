# 0020: Schema migrations via Flyway (replacing ddl-auto=update)

Date: 2026-08-11
Status: Accepted

## Context

Schema was managed by `spring.jpa.hibernate.ddl-auto=update`. That only does
additive, best-effort changes -- it cannot add a NOT NULL column to a populated
table, drop columns, or change constraints. The multi-tenancy change (ADR-0009)
added a non-null `owner` column; deploying it against the live, populated
database crashed the app on boot (`ALTER TABLE ... ADD COLUMN owner ... NOT
NULL` is rejected by Postgres when rows exist). ADR-0009 already anticipated
"migrations when it grows."

## Decision

Adopt **Flyway** versioned migrations and set `ddl-auto=validate` (Hibernate
only checks the schema matches the entities; Flyway owns schema evolution).

- `V1__baseline.sql` captures the current schema (generated from the
  Hibernate-created DDL so `validate` passes), using `CREATE TABLE IF NOT
  EXISTS`.
- `spring.flyway.baseline-on-migrate=true`, `baseline-version=0`. This adopts
  the **existing populated** production DB (Flyway baselines its schema and
  marks V1 applied -- the IF NOT EXISTS makes V1 a no-op there) **and** creates
  a **fresh** DB from scratch (V1 runs). Both paths were verified before merge.
- Future schema changes ship as new `V2__…`, `V3__…` migrations, run
  automatically on deploy -- no manual DB steps, no ddl-auto guesswork.

## Consequences

- The class of failure behind the boot crash (#29) is eliminated: migrations
  are explicit, deterministic, and tested.
- No production DB reset is needed to adopt Flyway (baseline-on-migrate handles
  the already-populated DB).
- Every schema change now requires a migration file -- a small, healthy
  discipline. Migrations should be tested against a populated DB (see #31).
- `validate` will fail fast at boot if the entities and schema drift, catching
  a missed migration in CI rather than in production.
