# 0005: Output as a live web page, not file/email

Date: 2026-08-06
Status: Accepted

## Context

The service needs to present found shows in a usable form. Options considered:
a generated markdown/file artifact, an email digest, or a persistent web page.

## Decision

Serve a single web page backed by the database, with columns for band,
date/time, venue, price, and discovery date. Sortable by any column via query
parameter, default sort by event date.

## Consequences

- Requires the app to stay running continuously (see ADR 0008) rather than
  running as a one-off script — the data has to be queryable on demand.
- No push notification when new shows appear — the person checks the page
  rather than being alerted; can be revisited if that becomes tedious.
- Simpler than email (no deliverability/formatting concerns) and always
  reflects current state, unlike a point-in-time file export.
