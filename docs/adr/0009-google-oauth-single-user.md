# 0009: Google OAuth with a per-user allow-list

Date: 2026-08-06
Status: Accepted (amended 2026-08-11 -- single-user allow-list -> small multi-user allow-list)

## Context

The repo and deployed app are public. The web page shouldn't be usable by
anyone other than the intended users, but building a full user-management /
self-signup system for a small personal tool is unwarranted effort.

## Decision

Use Spring Security's OAuth2 login against Google, with a custom
`OAuth2UserService` that rejects any authenticated email not on a small,
hand-maintained allow-list (`setlistscout.auth.allowed-emails`, env
`ALLOWED_EMAILS`) during the user-info exchange -- before a session is ever
established. No password to manage; Google handles the actual authentication.

Each allowed user gets their own isolated data: every user-owned entity
(`Artist`, `Show`, `SearchSettings`) carries an `owner` column (the user's
email), and all queries are owner-scoped. A new user starts with an empty seed
list; the configured `seed-owner` is the one account that gets the
`seed-bands.txt` bootstrap.

## Consequences

- Only allow-listed Google accounts can authenticate; the list is a config
  value (a closed list, not open signup). Adding a user is an env-var change.
- Data is per-user (multi-tenant): users never see each other's artists,
  shows, or settings.
- Requires registering an OAuth client in Google Cloud Console and keeping the
  client secret out of the public repo (in Render's environment variables).
- Since the repo is public, the rejection logic itself is visible to anyone --
  this is fine, since the security boundary is "only these Google accounts can
  authenticate," not "the code is secret."
- The OAuth consent screen must be published to Production (non-sensitive
  scopes: openid/email/profile need no verification) so allow-listed users can
  sign in without being added as test users.
