# 0009: Single-user auth via Google OAuth

Date: 2026-08-06
Status: Accepted

## Context

The repo and deployed app are public. The web page shouldn't be usable by
anyone other than the owner, but building a full user-management system for a
single-user personal tool is unwarranted effort.

## Decision

Use Spring Security's OAuth2 login against Google, with a custom
`OAuth2UserService` that rejects any authenticated email other than
`rob.sartin@gmail.com` during the user-info exchange — before a session is
ever established. No password to manage; Google handles the actual
authentication.

## Consequences

- Only one account can ever use the app without a code change (the allow-listed
  email is a config value, `setlistscout.auth.allowed-email`, so it could be
  extended to a list later if needed).
- Requires registering an OAuth client in Google Cloud Console and keeping the
  client secret out of the public repo (in Render's environment variables).
- Since the repo is public, the rejection logic itself is visible to anyone —
  this is fine, since the security boundary is "only this Google account can
  authenticate," not "the code is secret."
