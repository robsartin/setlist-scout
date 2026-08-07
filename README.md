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
- Push this repo to GitHub (via github.dev in your mobile browser — no clone needed)
- render.com → New → Web Service → connect the repo
- Environment: **Docker** is not required — Render auto-detects Maven/Java.
  Build command: `./mvnw clean package -DskipTests`
  Start command: `java -jar target/setlist-scout.jar`
- Add a **Render Postgres** database (free tier) and copy its Internal Database URL
- Set these environment variables in Render's dashboard:
  ```
  DATABASE_URL=<from Render Postgres>
  DATABASE_USERNAME=<from Render Postgres>
  DATABASE_PASSWORD=<from Render Postgres>
  GOOGLE_CLIENT_ID=<from step 1>
  GOOGLE_CLIENT_SECRET=<from step 1>
  ALLOWED_EMAIL=rob.sartin@gmail.com
  TICKETMASTER_API_KEY=<from step 2>
  BANDSINTOWN_APP_ID=<from step 2>
  DISCOGS_TOKEN=<from step 2>
  LASTFM_API_KEY=<from step 2>
  ANTHROPIC_API_KEY=<from step 2>
  ```
- Deploy. Visit the Render URL, sign in with Google — only rob.sartin@gmail.com gets past the login.

## Changing the search location later
Go to `/`, edit the "Near ___, within ___ miles, next ___ months" fields, hit
Save. Takes effect on the next scan — no redeploy, no code change.

## Local development
Requires a local Postgres (or point `DATABASE_URL` at one). Run:
```
./mvnw spring-boot:run
```
