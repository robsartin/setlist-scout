# Structured logging & correlation IDs — design

## Goal

Give Setlist Scout real observability. Today there is no logging config and a single
`log.error`, so when a user reports "no shows" or "expansion is empty" there's nothing to
read. Add: a standard JSON log format, a UUIDv7 correlation id on every HTTP request
(propagated to all downstream log lines), and a separate correlation id per background
scan/expansion — plus meaningful, leveled log statements throughout the pipeline.

The concrete payoff: the recent "why did David get 0 shows / why is his expansion empty"
investigation required poking the DB and the code by hand. With this in place, the logs
would have shown the answer directly.

## Prerequisite (separate PR, first)

Upgrade Spring Boot **3.3.4 → latest 3.4.x** (`gradle/libs.versions.toml`). 3.4 provides
native structured (JSON) logging, so we avoid adding `logstash-logback-encoder`. This ships
as its own PR, CI-verified, before the logging work:

- Verify the Flyway pin (`extra["flyway.version"]="11.20.3"`) still resolves and the migration
  Testcontainers tests pass (it may become removable if 3.4 manages a PG18-capable Flyway).
- Verify Spring Security 6.3→6.4 (OIDC allow-list) via the render test with `oidcLogin()`.
- Verify Hibernate 6.5→6.6 (`ddl-auto=validate`) via the boot test.
- Watch for deprecated config properties (config is small).

## 1. Log format & volume control

- `logging.structured.format.console: ecs` — JSON (Elastic Common Schema), native to 3.4,
  emitted as **JSONL: one compact JSON object per line, newline-delimited** (not pretty-printed
  multi-line). This is Spring Boot's structured console output and is what the Render log viewer
  and any later `jq`/aggregation step expect. MDC fields are included automatically, so `cid`
  (and `owner`/`job`/`parentCid`) appear on every line without per-statement effort.
- `logging.level.com.robsartin.setlistscout: ${LOG_LEVEL:INFO}` — **`LOG_LEVEL` env var is the
  volume knob.** Default `INFO`. Set `DEBUG` in Render for a deep dive; back to `INFO` to quiet.
  (Root stays at Spring's default; only our package is knob-controlled.)

### Runtime level changes without redeploy (in scope)

Flip the app's log level **live, no redeploy** — for when a user hits a problem and you want DEBUG
right now. Mechanism: Spring's `LoggingSystem.setLogLevel("com.robsartin.setlistscout", level)` (the
same call Actuator's loggers endpoint makes under the hood).

- Surface it as a tiny **authenticated UI toggle** (INFO ⇄ DEBUG) on an existing page, POSTing
  through our normal form + CSRF + htmx path to `POST /admin/log-level`. This is chosen over exposing
  Actuator's `/actuator/loggers` because that endpoint wants a JSON body + CSRF token + the session
  cookie, which is awkward to invoke by hand on a cookie-auth app; a UI toggle "just works."
- The change is **ephemeral** — it resets to the `LOG_LEVEL` env default on the next deploy/restart,
  which stays the persistent baseline.
- Alternative (if we'd rather not add UI): expose `management.endpoints.web.exposure.include:
  health,loggers` and POST to `/actuator/loggers/...`. Standard, but clunky to call here.

## 2. Correlation id (UUIDv7)

- **Source:** `com.fasterxml.uuid:java-uuid-generator`; `Generators.timeBasedEpochGenerator().generate()`
  yields a v7 UUID (time-ordered, so ids sort chronologically in the logs). Same FasterXML family
  as our existing Jackson. Wrapped in a `CorrelationIds` helper (one static method) so the generator
  choice is isolated.
- **Carrier:** SLF4J **MDC**. Keys: `cid` (always), `owner` and `job` (background work),
  `parentCid` (async job linked to the request that triggered it).
- **HTTP requests** — a `CorrelationIdFilter extends OncePerRequestFilter`, registered early:
  1. Use inbound `X-Request-Id` if present and a valid UUID; otherwise mint a UUIDv7.
  2. `MDC.put("cid", id)`; echo it on the response `X-Request-Id` header.
  3. `try { chain } finally { MDC.clear() }` — never leak context across pooled threads.
- **Background work — per operation, per owner:**
  - Scheduler (`ShowScanScheduler.scan`): for each owner, run `expandAll(owner)` under a fresh
    cid (`job=expansion`, `owner=…`) and `scanForShows(owner)` under another fresh cid
    (`job=scan`, `owner=…`).
  - Manual `/scan-now` runs async (`AsyncScanRunner`): capture the triggering request's `cid` at
    submit time; the background task mints its **own** `cid` and sets `parentCid` = the request's
    cid, so a click can be traced to its scan.
  - Manual `/expand-now` runs synchronously on the request thread, so it is already covered by the
    request's `cid` — no separate job id needed.
  - A tiny `Correlation.run(job, owner, parentCid, Runnable)` helper does set-MDC → run →
    clear, so no call site hand-manages MDC.

## 3. Instrumentation (leveled)

**INFO — visible by default (curated key events):**
- Request in/out: method, path, status, `durationMs` (in the filter, logged on completion).
- Scan: start (`owner`), finish (`artistsSearched`, `showsFound`, `showsSaved`, `durationMs`).
- Expansion: start (`owner`), finish (`artistsProcessed`, `candidatesFound`, `durationMs`).
- All WARN / ERROR.

**DEBUG — off by default (verbose):**
- Per-artist within a scan/expansion: artist name + per-source counts (TM=n, BIT=m, band-site=k;
  members=n, similar=m, tributes=k).
- Each external API call outcome across the nine services (Ticketmaster, Bandsintown, MusicBrainz,
  Discogs, Last.fm, SimilarArtistLlm, TributeLlm, TourPageLlm, Geocoding): source, artist, result
  count.

**WARN — high value, always visible:** every place a service currently **catches an exception and
returns empty** now logs a WARN with the cause (Ticketmaster/Bandsintown blank-key or HTTP error,
Discogs/Last.fm/LLM failures, geocode failure, band-site scrape failure). This is the core payoff —
silent empty results become visible. A WARN per failed source also makes a partial/aborted
expansion legible (the deeper `expandAll` resilience hardening is a sibling concern, to be filed
separately).

Each service and pipeline class gets its own SLF4J logger. Log statements carry structured
key/values (via the SLF4J fluent API or `kv`-style args) rather than string-concatenated messages,
so ECS fields stay queryable.

## 4. Testing

- `CorrelationIdFilterTest`: mints a v7 when no inbound header; honors a valid inbound
  `X-Request-Id`; sets `cid` in MDC during the chain and clears it after; echoes the response header.
- `CorrelationTest` (background helper): sets `cid`/`owner`/`job`/`parentCid`, runs the block, and
  clears MDC afterward even on exception.
- `CorrelationIdsTest`: generated id is a valid UUID and version 7; two successive ids are ordered.
- `LogLevelControllerTest`: `POST /admin/log-level` with a valid level calls `LoggingSystem.setLogLevel`
  (mocked) for our package; an invalid level is rejected; the endpoint requires authentication.
- Structured-output format itself is configuration — spot-checked in CI logs / prod, not unit-tested.

## Out of scope (YAGNI)

- No log shipping / aggregation service — Render dashboard only.
- No distributed tracing (single service, no downstream services to propagate to).
- No sampling or rate-limiting of logs.
- Outbound calls to third-party APIs do **not** carry our cid as a header (nothing on the other end
  consumes it); the cid lives in our logs only.

## Rollout

1. PR A — Spring Boot 3.4.x upgrade (prerequisite), CI green.
2. PR B — this logging feature on top of 3.4.
