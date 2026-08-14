# 0025: Blueprint-managed hosting for auto-propagating DB credential rotation

Date: 2026-08-14

Status: Accepted

## Context

[0008](0008-hosting-on-render.md) chose Render for hosting; the live web
service and Postgres database were both created **by hand** in the Render
dashboard, with the database connection entered as static environment
variables. #81/PR #82 improved on this by consuming Render's linked
**Datastore URL** (`DATABASE_CONNECTION_URL`, parsed by
`DatabaseUrlEnvironmentPostProcessor`/`RenderDatabaseUrl`) instead of a
hand-typed JDBC URL — see
`docs/superpowers/specs/2026-08-12-render-datastore-url-design.md`.

The 2026-08-12 credential rotation exposed that design's limit: a dashboard
"Datastore URL" link is a **snapshot copied at link time**, not a live
reference — it does not follow a subsequent Postgres credential rotation.
Every rotation required a manual four-step dance (create new credential,
delete-and-re-add the Datastore URL link on the web service, deploy, verify,
then delete the old credential).

Render's Blueprint (`render.yaml`) mechanism is different: an env var set
via `fromDatabase` is re-resolved by Render itself on every deploy, so it
always reflects the database's *current* default credential — no dashboard
re-link step, no human touching a secret. `render.yaml` already existed in
the repo (written for a fresh Blueprint setup) but had never been applied to
the live service, and its `databases:` block described a database that
didn't match the live one.

## Decision

Adopt Blueprint management for the live web service and database, using
`fromDatabase` references (`DB_HOST`/`DB_PORT`/`DB_NAME`/
`DATABASE_USERNAME`/`DATABASE_PASSWORD`) as the DB connection mechanism
instead of a linked Datastore URL. This is a config/ops change, not a code
change: `application.yml`'s existing split-variable fallback
(`spring.datasource.url` composed from `DB_HOST`/`DB_PORT`/`DB_NAME`, with
`DATABASE_USERNAME`/`DATABASE_PASSWORD` supplied separately) already
consumes exactly these variables — no application code was written for this
issue.

Render matches Blueprint resources to existing dashboard resources by the
`name` field (confirmed against Render's Blueprint spec docs), not by
`databaseName`/`user` (write-once-at-creation, ignored for an
already-existing database). Adoption therefore hinges on the web service and
database in the dashboard being named exactly `setlist-scout` and
`setlist-scout-db` — see `docs/deploy/render-blueprint-migration.md` for the
adoption walkthrough and its data-loss guardrails.

This makes the #82 `DATABASE_CONNECTION_URL` parser **redundant** for the
Blueprint-managed service (Blueprint doesn't set that env var), but the code
is kept rather than removed: it's a no-op when the var is absent, costs
nothing at runtime, and is a cheap fallback if the service were ever
reverted to hand management.

## Alternatives considered

- **Keep the Datastore-URL-link approach (#82), just re-link on every
  rotation.** Rejected — that's the exact manual toil this issue exists to
  remove; a snapshot link fundamentally cannot auto-follow rotation.
- **Re-provision a brand-new Blueprint-managed service and database from
  scratch, migrate data over.** Rejected as the default path — real
  downtime and a real data-migration step for a personal single-tenant
  service where adopting the existing resources in place is available and
  safer.
- **Remove the #82 `DatabaseUrlEnvironmentPostProcessor`/`RenderDatabaseUrl`
  code now that it's unused in production.** Rejected for this issue — it's
  harmless dead weight while Blueprint adoption is still a pending manual
  step the user has to perform in the dashboard; removing it is a fine
  follow-up once the Blueprint has been live for a while.

## Consequences

- Future Postgres credential rotations on Render are genuinely one-click:
  rotate the credential, Render redeploys the web service with the new
  `fromDatabase`-resolved values automatically, no dashboard re-link step.
- `render.yaml` is now the source of truth for the web service's
  configuration shape (health check, auto-deploy trigger, env var list) —
  changes to non-secret env vars should go through the file and a normal PR,
  not a one-off dashboard edit, or they'll be silently overwritten on the
  next Blueprint sync (or drift silently if sync isn't re-run).
- The one-time adoption itself is a manual Render-dashboard operation (see
  `docs/deploy/render-blueprint-migration.md`) that this ADR and the repo
  changes cannot perform — care is required to avoid Render creating a new,
  empty database instead of adopting the live one.
- The `DATABASE_CONNECTION_URL` parser from #82 remains in the codebase as
  an inert fallback; a future cleanup issue can remove it once the Blueprint
  has been the live configuration for a while with no reversion.
- Builds on [0008](0008-hosting-on-render.md) (hosting on Render) and
  [16](0016-hosting-on-render-requires-a-dockerfile-not-native-java-support.md)
  (Docker-based deploy).
