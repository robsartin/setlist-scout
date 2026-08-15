# Setlist Scout

Personal service: expands a seed band list (members, side projects, similar
artists), searches for their upcoming shows near Austin, and shows them on a
single sortable, Google-login-protected web page.

See [docs/architecture-introduction.md](docs/architecture-introduction.md) for a guided tour
of how the modules, jobs, and events fit together, and [docs/adr/](docs/adr/README.md) for
the reasoning behind each major design decision.

## How it works

1. **Seed list** — `src/main/resources/data/seed-bands.txt`, one band per line.
   Imported into the database on first startup. Add more later from the `/artists` page.
2. **Expansion** — each active (seed/approved) artist gets its own durable `expand_job`
   per source, looking up band members and side projects (MusicBrainz + Discogs) and
   taste-similar artists (Last.fm + an LLM cross-check). Results land as **pending
   review** — nothing gets searched for shows until you approve it on `/artists`.
3. **Show search** — each active artist/source pair gets its own durable `scan_job`
   checking Ticketmaster + Bandsintown, filtered to your saved location/radius/window
   (editable live on the `/` page — no redeploy needed).
4. **Scheduling** — there's no whole-fleet batch job. Two paced pollers
   (`setlistscout.scan-poller-enabled` / `expand-poller-enabled`, on by default) tick on
   an interval and drain whatever jobs are currently due, each on its own per-source
   cadence (`scan-interval` / `expansion-interval`, with optional per-source overrides).
   Newly approved artists and settings changes enqueue or re-due jobs automatically; a
   source newly added for an already-active artist is picked up by an idempotent startup
   backfill sweep on the next deploy/restart. The "Scan now" / "Run expansion now" buttons
   just re-due your jobs to
   "now" and queue them for the next poller tick — they don't run a scan synchronously,
   so the page shows a brief "queued" confirmation rather than results.

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

Migrating an already-running, hand-managed service (Option B below) to
Blueprint management afterward, without losing its database, is a separate
manual dashboard process — see
[`docs/deploy/render-blueprint-migration.md`](docs/deploy/render-blueprint-migration.md).

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

## Disabling a source

Each of the 8 external-service sources (3 show-search sources: Ticketmaster, Bandsintown,
band-site scraping; 5 relation sources: MusicBrainz, Discogs, Last.fm, and two LLM-backed ones)
can be switched off independently with a single Render environment variable on the web service --
no redeploy of anything else, no effect on the other 7. Setting one to `false` means Spring never
creates that source's bean, so it just drops out of scanning/expansion the same way an already-
unrecognized source ID does today (a WARN log line, existing jobs for it simply stop being picked
up) -- never a crash. These are **opt-out, not opt-in**: leaving a variable unset (or setting
anything other than `false`) keeps that source on, so nothing needs to change to keep today's
behavior.

**The immediate case this exists for:** to turn off Bandsintown (e.g. because its API is down or
misbehaving in production), set on the web service:
```
SETLISTSCOUT_SOURCES_BANDSINTOWN=false
```

The full list:

| Source | Env var to disable |
|---|---|
| Ticketmaster | `SETLISTSCOUT_SOURCES_TICKETMASTER=false` |
| Bandsintown | `SETLISTSCOUT_SOURCES_BANDSINTOWN=false` |
| Band's official site (scraped) | `SETLISTSCOUT_SOURCES_BANDSITE=false` |
| MusicBrainz | `SETLISTSCOUT_SOURCES_MUSICBRAINZ=false` |
| Discogs | `SETLISTSCOUT_SOURCES_DISCOGS=false` |
| Last.fm | `SETLISTSCOUT_SOURCES_LASTFM=false` |
| Similar-artist LLM cross-check | `SETLISTSCOUT_SOURCES_SIMILARLLM=false` |
| Tribute-band LLM search | `SETLISTSCOUT_SOURCES_TRIBUTELLM=false` |

> **Get the spelling exactly right for the three hyphenated names above.** Spring Boot's
> environment-variable binding *removes* a hyphen inside a property segment rather than turning
> it into an underscore, so `band-site` becomes `BANDSITE`, not `BAND_SITE` (same rule as
> `similar-llm` -> `SIMILARLLM` and `tribute-llm` -> `TRIBUTELLM`) -- verified directly against
> Spring's binder rather than guessed (see `SourceEnvVarBindingTest`).

## Changing the search location later
Go to `/`, edit the "Near ___, within ___ miles, next ___ months" fields, hit
Save. Takes effect on the next scan — no redeploy, no code change.

## Local development
Requires a local Postgres (or point `DATABASE_URL` at one). Run:
```
./gradlew bootRun
```
