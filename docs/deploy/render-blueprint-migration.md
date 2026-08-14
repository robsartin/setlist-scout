# Migrating the live service to Blueprint management

Issue: [#85](https://github.com/robsartin/setlist-scout/issues/85). Decision
record: [ADR 0025](../adr/0025-blueprint-managed-hosting-for-credential-rotation.md).

## Why

The live `setlist-scout` web service and its Postgres database were both
created **by hand** in the Render dashboard. The DB connection currently
comes from a dashboard-linked **Datastore URL** env var
(`DATABASE_CONNECTION_URL`, consumed by `DatabaseUrlEnvironmentPostProcessor`
— see #81/PR #82 and
`docs/superpowers/specs/2026-08-12-render-datastore-url-design.md`).

The 2026-08-12 rotation showed that link's limit: it's a **snapshot copied
at link time**, not a live reference. It does not follow a later credential
rotation. Every rotation since has needed a manual four-step dance (new
credential → delete/re-add the Datastore URL link → deploy → verify → delete
the old credential).

A Render **Blueprint** (`render.yaml`, already in this repo) is different:
env vars set via `fromDatabase` are re-resolved by Render on every deploy,
so they always reflect the database's *current* credential. Once the live
service is Blueprint-managed, rotation becomes: rotate the credential in the
dashboard → Render redeploys automatically → done. No re-link, no manual
env-var edit.

## What changed in this repo

- `render.yaml` — the web service's env vars now use `fromDatabase` refs for
  `DB_HOST` / `DB_PORT` / `DB_NAME` / `DATABASE_USERNAME` /
  `DATABASE_PASSWORD`, matching exactly what `application.yml`'s existing
  split-variable fallback already reads
  (`spring.datasource.url: ${DATABASE_URL:jdbc:postgresql://${DB_HOST:...}...}`
  when `DATABASE_URL` itself isn't set). No application code changed — the
  app already supported this shape.
- Fixed a real bug in `render.yaml`: the single-user gate was wired as
  `ALLOWED_EMAIL` (singular), but the app reads `ALLOWED_EMAILS` (plural —
  `setlistscout.auth.allowed-emails` in `application.yml`). The old key
  would have silently no-op'd to the `rob.sartin@gmail.com` default instead
  of actually setting the configured allow-list. **Before applying the
  Blueprint, confirm the `ALLOWED_EMAILS` value in `render.yaml` matches
  (or update it to match) whatever's live today** — it's not a secret, so
  Blueprint sync will overwrite the live value with what's in the file.
- Added `healthCheckPath: /actuator/health` and
  `autoDeployTrigger: checksPass` (the Blueprint-spec equivalent of the
  dashboard's "Auto-Deploy: After CI Checks Pass" setting) to the web
  service, so both match the live service's current dashboard settings.
- `databases:` block's `databaseName`/`user` fields updated to describe the
  live database (`scoutdata` / the current rotated user) instead of the
  stale placeholder names (`setlistscout`/`setlistscout`) from the original
  fresh-setup version of the file. **These fields don't actually matter for
  adoption** — Render ignores them for an already-existing database (they're
  write-once-at-creation) — they're updated here so the file doesn't lie
  about what's running.

## Does this supersede the #82 `DATABASE_CONNECTION_URL` parser?

For the Blueprint-managed service, yes, functionally: Blueprint doesn't set
`DATABASE_CONNECTION_URL` at all, so `DatabaseUrlEnvironmentPostProcessor`
becomes a no-op once adopted, and the split-variable path
(`DB_HOST`/`DB_PORT`/`DB_NAME`/`DATABASE_USERNAME`/`DATABASE_PASSWORD`) does
the same job with the same rotation-safety guarantee.

The parser code (`RenderDatabaseUrl` /
`DatabaseUrlEnvironmentPostProcessor`) is **not removed** by this change. It
costs nothing when its env var is absent, and it's a cheap fallback if the
service is ever reverted to hand management. Removing it can be a separate
follow-up cleanup once the Blueprint has been the live configuration for a
while with no reversion.

## The manual Render steps (must be done in the dashboard — not from this repo)

This is the part nothing in the repo can do for you. **Read all of this
before starting** — the risk is Render creating a brand-new, empty database
next to the real one if a name doesn't match.

1. **Confirm the exact resource names in the Render dashboard.**
   - Open the live web service → note its exact name. It must read
     `setlist-scout`.
   - Open the live Postgres instance → note its exact name. It must read
     `setlist-scout-db`.
   - Render's Blueprint sync matches existing resources to `render.yaml`
     **by this `name` field only** — not by `databaseName`/`user`/anything
     else. If either name differs from what's in `render.yaml`, either
     rename the dashboard resource to match, or edit `render.yaml`'s
     `name:` field to match the dashboard — **before** proceeding. Getting
     this wrong for the database means Render provisions a brand-new, empty
     Postgres instance instead of adopting the one with your actual data.

2. **Reconcile `ALLOWED_EMAILS` (and the other non-secret env vars) first.**
   Open the live web service's Environment tab and compare every
   non-`sync: false` var in `render.yaml` against what's actually set today
   (`ALLOWED_EMAILS` in particular — see above). Update `render.yaml` in a
   PR if the file needs to change to match reality, rather than fixing it
   post-hoc in the dashboard where it'll just drift again on the next sync.

3. **Apply the Blueprint against the existing resources.** Render.com →
   **New → Blueprint** → connect this repo (branch: `main`, after this PR
   merges). Render reads `render.yaml`, matches `setlist-scout` and
   `setlist-scout-db` to the existing dashboard resources by name, and
   proposes adopting them (not creating new ones) — **verify the review
   screen says "adopt" / shows the existing resource IDs, not "create," for
   both, before confirming.** You'll be prompted once for the `sync: false`
   secrets (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
   `TICKETMASTER_API_KEY`, `BANDSINTOWN_APP_ID`, `DISCOGS_TOKEN`,
   `LASTFM_API_KEY`, `ANTHROPIC_API_KEY`) — use the live values, not new
   ones, so nothing else changes.

4. **Deploy and verify.** After the Blueprint sync deploys:
   - Check the service logs for a clean startup and DB connection (no
     `DATABASE_CONNECTION_URL`-related log lines; the split vars are now
     doing the work).
   - Visit the live URL, confirm you can still sign in and the artist list
     still loads (proves it's talking to the *existing* database, not an
     empty new one).
   - In the Render dashboard, confirm there's still exactly **one** Postgres
     instance (`setlist-scout-db`) — if a second one appeared, the name
     match in step 1 failed; stop, do not delete anything yet, and
     investigate before cleaning up.

5. **Clean up the now-redundant manual env vars** on the web service, if
   `DATABASE_CONNECTION_URL` (and/or the old static
   `DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD` from before #82)
   are still present — they're superseded by the Blueprint's `fromDatabase`
   vars. Safe to remove once step 4 confirms the app is healthy on the new
   config.

6. **Validate the actual rotation flow end-to-end** (the point of this
   whole issue): create a new default Postgres credential, confirm the
   service redeploys automatically and reconnects with **no manual step on
   your part**, then delete the old credential. If it doesn't auto-redeploy,
   check the web service's Auto-Deploy setting matches
   `autoDeployTrigger: checksPass` (step 3 confirms — CI checks running on
   this repo's `main` branch must pass for the deploy to trigger).

## Rollback

If adoption goes wrong before step 4's verification passes: do not delete
any database. Render Blueprint sync can be disconnected from a service
without deleting the underlying resource — disconnect the Blueprint, and the
web service and database keep running with whatever env vars were most
recently applied (Render does not revert them on disconnect). From there,
manually restore the pre-migration env vars
(`DATABASE_CONNECTION_URL` or the original static
`DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD`) on the web service
and redeploy.
