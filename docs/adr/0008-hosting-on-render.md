# 0008: Hosting on Render

Date: 2026-08-06
Status: Accepted

## Context

The app needs to run continuously (for the scheduler and the always-available
web page) without relying on the developer's own machine, since development
and operation are currently phone-only. Candidates considered: Render,
Railway, Fly.io.

## Decision

Deploy to Render. Free/hobby tier, deploys directly from the GitHub repo
(no local build step required), includes a managed Postgres add-on, and
supports environment-variable-based secrets — all configurable from Render's
web dashboard, which works fine from a mobile browser.

## Consequences

- Free tier services on Render can spin down after inactivity and take a
  moment to wake on the next request — acceptable for a personal tool checked
  occasionally, worth revisiting if it becomes annoying.
- Secrets (Google OAuth credentials, API keys) are managed in Render's
  dashboard, not in the repo — keeps the public GitHub repo free of credentials.
- Coupled to Render's Maven/Java auto-detection for the build; a Dockerfile
  could be added later for more control if needed.
