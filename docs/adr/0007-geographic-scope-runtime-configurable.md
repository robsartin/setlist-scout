# 0007: Geographic scope, runtime-configurable

Date: 2026-08-06
Status: Accepted (supersedes initial "anywhere in Texas" scope)

## Context

Initial scope was "anywhere in Texas," later narrowed to "near Austin only —
even San Antonio is too far," with an explicit requirement that the scope be
easy to change without redeploying.

## Decision

Store city/state/radius/months-ahead in a single-row `SearchSettings` database
table, not `application.yml`. It's editable directly from the web page's
settings form and takes effect on the next scan — no redeploy, no environment
variable change. `application.yml` only supplies the *initial* defaults
(Austin, TX, 50mi, 6 months) used the first time the app starts.

## Consequences

- Requires a settings table and a small form/controller, rather than a static
  config value — slightly more code than a hardcoded constant.
- Location changes are immediate and self-service, which was an explicit
  requirement ("make it easy to change").
- If the app is ever redeployed with a fresh database, it falls back to the
  `application.yml` defaults rather than remembering the last-configured value —
  worth keeping in mind if the database is ever reset.
