# Consume Render's linked Datastore URL for secret-free DB credential rotation

- **Issue:** [#81](https://github.com/robsartin/setlist-scout/issues/81)
- **Date:** 2026-08-12
- **Status:** Approved (design)

## Problem

The production web service on Render is **not** Blueprint-managed. Its database
connection is configured with three **static** environment variables that were
entered by hand:

- `DATABASE_URL` = `jdbc:postgresql://<internal-host>:5432/scoutdata` (no credentials embedded)
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`

Because these are static values (not `fromDatabase` links), rotating the Postgres
credential in Render does **not** propagate to the service — the new password has to
be re-entered by hand every time. That manual step is exactly what we want to remove:
it is error-prone and requires a human to handle the secret.

Render's dashboard can inject a **linked Datastore URL** env var (a reference to the
database's internal connection string) that Render keeps up to date automatically,
including across credential rotations. But that URL arrives in libpq form
(`postgresql://user:password@host/db`), whereas Spring's `spring.datasource.url`
needs JDBC form (`jdbc:postgresql://host/db`) with username and password supplied
separately. Something has to translate between the two.

## Goal

Let the app consume Render's linked Datastore URL so that credential rotation becomes
**one-click and secret-free**: rotate on Render → the linked URL updates → the service
redeploys and reconnects, with no human touching the password and no code change.

Local development must be **completely unaffected** — it keeps using the existing
split-variable fallback in `application.yml`.

## Approach

A Spring **`EnvironmentPostProcessor`** backed by a **pure URL parser**. Chosen over a
Docker entrypoint shell script (untestable bash URL-parsing, changes the entrypoint) and
over adding a library (extra dependency for a ~20-line need).

### Components

Both live in `com.robsartin.setlistscout.shared` (where cross-cutting infrastructure lives).

1. **`RenderDatabaseUrl` — pure parser (no Spring dependency).**

   ```
   RenderDatabaseUrl.parse(String url) -> Optional<Parsed>
   Parsed { String jdbcUrl; String username; String password; }
   ```

   Behavior:
   - Accepts `postgres://` and `postgresql://` schemes.
   - Extracts userinfo (`user:password@`) into `username` / `password`, **URL-decoding**
     percent-encoded characters (Render passwords can contain reserved characters).
   - Produces `jdbcUrl = "jdbc:postgresql://" + host [+ ":" + port] + "/" + database [+ "?" + query]`.
   - Port is optional; when absent it is omitted from the JDBC URL (JDBC defaults to 5432).
   - Any query string (e.g. `sslmode=require`) is preserved on the JDBC URL.
   - Blank, null, wrong-scheme, or unparseable input → `Optional.empty()` (no throw).

2. **`DatabaseUrlEnvironmentPostProcessor` — thin Spring hook.**
   - Implements `org.springframework.boot.env.EnvironmentPostProcessor`, registered in
     `src/main/resources/META-INF/spring.factories` (EPPs run before the application context,
     Flyway, and JPA — a `@Component`/`@Configuration` would run too late).
   - Reads env var **`DATABASE_CONNECTION_URL`** from the environment.
   - If present and non-blank and it parses: add a `MapPropertySource` at **highest precedence**
     setting `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`.
   - If absent, blank, or unparseable: **no-op** (log at DEBUG, do nothing) — the existing
     `application.yml` configuration stands.

### Data flow / precedence

- **Production:** `DATABASE_CONNECTION_URL` (linked to the DB) is set → the post-processor's
  high-precedence property source wins over the `application.yml` `${DATABASE_URL:…}` chain →
  both JPA **and** Flyway use the derived datasource (they read the same `spring.datasource.*`).
- **Local dev / tests:** `DATABASE_CONNECTION_URL` unset → post-processor is a no-op → the
  existing `url: ${DATABASE_URL:jdbc:...localhost...}` / `username: ${DATABASE_USERNAME:setlistscout}`
  / `password: ${DATABASE_PASSWORD:setlistscout}` fallback chain in `application.yml` is untouched.

### Error handling

- Unparseable `DATABASE_CONNECTION_URL` → no-op (fall through to `application.yml`), rather than
  failing startup on a malformed value. Rationale: a missing/garbled link should degrade to the
  existing config path, not hard-crash the app; a genuinely wrong datasource surfaces as a normal
  connection failure with a clear Flyway/Hibernate error.

## Testing

- **`RenderDatabaseUrl` unit tests** (pure, no Spring): with port, without port, percent-encoded
  password, query string preserved, `postgres://` vs `postgresql://`, and invalid inputs
  (blank, null, non-URL, wrong scheme) → empty.
- **`DatabaseUrlEnvironmentPostProcessor` test:** using a `MockEnvironment` (or
  `ApplicationContextRunner`), assert that when `DATABASE_CONNECTION_URL` is set the three
  `spring.datasource.*` properties resolve to the parsed values and take precedence; and that
  when it is unset the datasource properties are left to the existing configuration.
- **Existing `ApplicationContextSmokeTest`** must still boot (no regression).

## Rollout & rotation sequence (ops, after the PR merges and deploys)

1. Ship the code (this issue → branch → PR → merge → Render auto-deploy).
2. In Render, add a linked env var `DATABASE_CONNECTION_URL` → the `database` instance's
   internal Datastore URL. The app now connects via the parsed URL using the current `scout`
   credentials — verify healthy.
3. Remove the now-unused static `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` from
   the service (cleanup; the code keeps them only as local-dev defaults).
4. **Rotate:** create a new default credential and delete the old `scout` one. The linked URL
   auto-updates → the service redeploys → verify logs show a clean DB connection. The leaked
   credential is now invalid.

After this, every future rotation is only step 4 — one-click and secret-free.

## Out of scope

- Converting the service to a full `render.yaml` Blueprint (larger change; the blueprint's
  current `setlistscout`/`setlistscout` names don't match the live `scout`/`scoutdata` DB).
- Changing the DB's inbound IP rules / network exposure (a separate security decision).
