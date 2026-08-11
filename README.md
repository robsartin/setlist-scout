# Setlist Scout

Personal service: expands a seed band list (members, side projects, similar
artists), searches for their upcoming shows near Austin, and shows them on a
single sortable, Google-login-protected web page.

See [docs/adr/](docs/adr/README.md) for the reasoning behind each major design decision.

## How it works

1. **Seed list** — `src/main/resources/data/seed-bands.txt`, one band per line.
   Imported into the database on first startup. Add more later from the `/artists` page.
2. **Expansion** — every scan, `ExpansionService` looks up each active (seed/approved)
   artist's band members and side projects (MusicBrainz + Discogs) and taste-similar
   artists (Last.fm + an LLM cross-check). Results land as **pending review** —
   nothing gets searched for shows until you approve it on `/artists`.
3. **Show search** — `ShowAggregationService` checks Ticketmaster + Bandsintown for
   every seed/approved artist, filtered to your saved location/radius/window
   (editable live on the `/` page — no redeploy needed).
4. **Schedule** — runs automatically every 3 days (`setlistscout.scan-interval-ms`),
   or trigger manually with the "Scan now" / "Run expansion now" buttons.

**Not yet wired up:** Austin-local sources (venue calendars, Austin Chronicle,
Do512, KUTX) don't have clean JSON APIs, so they need scraping rather than a
simple client. Left as a follow-up — Ticketmaster + Bandsintown cover most of it
in the meantime.

## One-time setup

### 1. Google OAuth (login restricted to rob.sartin@gmail.com)
- console.cloud.google.com → new project → "APIs & Services" → "Credentials"
- Create an OAuth Client ID (type: Web application)
- Authorized redirect URI: `https://<your-render-url>/login/oauth2/code/google`
- Save the Client ID and Client Secret for step 3

### 2. API keys (all free tiers)
- Ticketmaster: developer.ticketmaster.com → register an app → API key
- Bandsintown: app.bandsintown.com/api/authentication → request an `app_id`
- Discogs: discogs.com/settings/developers → generate a personal access token
- Last.fm: last.fm/api/account/create → API key
- Anthropic (for the LLM similar-artist cross-check): console.anthropic.com → API key
- MusicBrainz needs no key, just a descriptive User-Agent (already set in `application.yml`)

### 3. Deploy to Render

Render has no native Java/JVM runtime, so the root `Dockerfile` (multi-stage Gradle
build → JRE runtime) is what Render builds and runs — Environment is **Docker**.

**Option A — Blueprint (recommended, least manual setup).** `render.yaml` in the repo
root provisions the web service *and* the Postgres database and wires the database
connection automatically, so there's no JDBC URL to hand-format.
- render.com → **New → Blueprint** → connect this repo.
- Render creates both services and prompts you for the `sync: false` secrets
  (`GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` from step 1, and the API keys from step 2).
- Set the Google OAuth **Authorized redirect URI** to
  `https://<your-render-url>/login/oauth2/code/google` (step 1).

**Option B — Manual dashboard.**
- render.com → New → Web Service → connect the repo. Environment: **Docker**. Leave
  Build/Start Command blank; the Dockerfile's `ENTRYPOINT` starts the app.
- Health check path: `/actuator/health`.
- Add a **Render Postgres** (free tier). Open its **Connections** page for the values below.
- Set these on the **web service** (not the database):
  ```
  DATABASE_URL=jdbc:postgresql://<INTERNAL_HOSTNAME>:5432/<DATABASE>
  DATABASE_USERNAME=<username>
  DATABASE_PASSWORD=<password>
  GOOGLE_CLIENT_ID=<from step 1>
  GOOGLE_CLIENT_SECRET=<from step 1>
  ALLOWED_EMAILS=rob.sartin@gmail.com,davidbuley01@gmail.com
  SEED_OWNER=rob.sartin@gmail.com
  TICKETMASTER_API_KEY=<from step 2>
  BANDSINTOWN_APP_ID=<from step 2>
  DISCOGS_TOKEN=<from step 2>
  LASTFM_API_KEY=<from step 2>
  ANTHROPIC_API_KEY=<from step 2>
  ```
  > **`DATABASE_URL` must be a `jdbc:postgresql://` URL** built from the internal
  > hostname — **not** Render's raw `postgresql://user:pass@host/db` Internal Database
  > URL pasted directly. Getting this wrong makes the app fall back to `localhost` and
  > fail to start. (Alternatively, set `DB_HOST`/`DB_PORT`/`DB_NAME` instead of
  > `DATABASE_URL` and the app composes the JDBC URL for you — this is what the Blueprint
  > does.) The web service and database must be in the same region.

- Deploy. Visit the Render URL, sign in with Google — only rob.sartin@gmail.com gets past the login.

## Changing the search location later
Go to `/`, edit the "Near ___, within ___ miles, next ___ months" fields, hit
Save. Takes effect on the next scan — no redeploy, no code change.

## Local development
Requires a local Postgres (or point `DATABASE_URL` at one). Run:
```
./gradlew bootRun
```
