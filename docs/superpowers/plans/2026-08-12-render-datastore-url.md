# Render Datastore URL — Secret-Free DB Credentials Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Spring app consume Render's linked internal Datastore URL so Postgres credential rotation becomes one-click and secret-free.

**Architecture:** A pure URL parser (`RenderDatabaseUrl`) converts Render's libpq-form connection URL into a JDBC URL plus separate username/password. A thin `EnvironmentPostProcessor` reads the `DATABASE_CONNECTION_URL` env var, and when present, publishes `spring.datasource.{url,username,password}` at highest precedence (so both JPA and Flyway use it). When the var is absent or unparseable, it is a no-op and the existing `application.yml` split-var fallback stands.

**Tech Stack:** Java 21, Spring Boot, Gradle (Kotlin DSL), JUnit 5, AssertJ.

## Global Constraints

- **Java toolchain:** 21. The system default JVM is 25, which Gradle 8.14 cannot launch on. **Every** `./gradlew` command MUST be prefixed with `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12-tem"`.
- **Package:** `com.robsartin.setlistscout` (new code goes in the `shared` subpackage).
- **Test style:** JUnit 5 (`org.junit.jupiter.api.Test`), package-private test classes, AssertJ `assertThat` (`import static org.assertj.core.api.Assertions.assertThat;`).
- **Env var name:** `DATABASE_CONNECTION_URL` (exact).
- **Do not touch** `src/main/resources/application.yml` — the split-var fallback must remain intact.
- **Testcontainers note:** `ApplicationContextSmokeTest` needs Docker and does not run locally without it; the two new tests below need no Docker. Filter with `--tests` to avoid triggering it.
- Commit messages end with the `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer.

---

## File Structure

- `src/main/java/com/robsartin/setlistscout/shared/RenderDatabaseUrl.java` — pure parser (Task 1).
- `src/test/java/com/robsartin/setlistscout/shared/RenderDatabaseUrlTest.java` — parser tests (Task 1).
- `src/main/java/com/robsartin/setlistscout/shared/DatabaseUrlEnvironmentPostProcessor.java` — Spring hook (Task 2).
- `src/main/resources/META-INF/spring.factories` — EPP registration (Task 2, new file).
- `src/test/java/com/robsartin/setlistscout/shared/DatabaseUrlEnvironmentPostProcessorTest.java` — hook tests (Task 2).

---

## Task 1: `RenderDatabaseUrl` pure parser

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/shared/RenderDatabaseUrl.java`
- Test: `src/test/java/com/robsartin/setlistscout/shared/RenderDatabaseUrlTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `RenderDatabaseUrl.parse(String) -> Optional<RenderDatabaseUrl.Parsed>` where `Parsed` is a record `Parsed(String jdbcUrl, String username, String password)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/shared/RenderDatabaseUrlTest.java`:

```java
package com.robsartin.setlistscout.shared;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RenderDatabaseUrlTest {

    @Test
    void parsesUrlWithExplicitPort() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgresql://scout:secret@dpg-abc-a.oregon-postgres.render.com:5432/scoutdata");

        assertThat(result).isPresent();
        assertThat(result.get().jdbcUrl())
                .isEqualTo("jdbc:postgresql://dpg-abc-a.oregon-postgres.render.com:5432/scoutdata");
        assertThat(result.get().username()).isEqualTo("scout");
        assertThat(result.get().password()).isEqualTo("secret");
    }

    @Test
    void parsesInternalUrlWithoutPort() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgresql://scout:secret@dpg-abc-a/scoutdata");

        assertThat(result).isPresent();
        assertThat(result.get().jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc-a/scoutdata");
        assertThat(result.get().username()).isEqualTo("scout");
        assertThat(result.get().password()).isEqualTo("secret");
    }

    @Test
    void acceptsPostgresScheme() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgres://user:pw@host:5432/db");

        assertThat(result).isPresent();
        assertThat(result.get().jdbcUrl()).isEqualTo("jdbc:postgresql://host:5432/db");
    }

    @Test
    void urlDecodesUsernameAndPassword() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgresql://scout:p%40ss%2Fword@host/db");

        assertThat(result).isPresent();
        assertThat(result.get().password()).isEqualTo("p@ss/word");
    }

    @Test
    void preservesQueryString() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgresql://u:pw@host:5432/db?sslmode=require");

        assertThat(result).isPresent();
        assertThat(result.get().jdbcUrl()).isEqualTo("jdbc:postgresql://host:5432/db?sslmode=require");
    }

    @Test
    void returnsEmptyForBlankNullAndInvalidInput() {
        assertThat(RenderDatabaseUrl.parse(null)).isEmpty();
        assertThat(RenderDatabaseUrl.parse("")).isEmpty();
        assertThat(RenderDatabaseUrl.parse("   ")).isEmpty();
        assertThat(RenderDatabaseUrl.parse("not-a-url")).isEmpty();
        assertThat(RenderDatabaseUrl.parse("mysql://u:pw@host/db")).isEmpty();
        assertThat(RenderDatabaseUrl.parse("postgresql://host/db")).isEmpty(); // no user:pass@
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12-tem" ./gradlew test --tests 'com.robsartin.setlistscout.shared.RenderDatabaseUrlTest'`
Expected: FAIL — compilation error, `RenderDatabaseUrl` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/robsartin/setlistscout/shared/RenderDatabaseUrl.java`:

```java
package com.robsartin.setlistscout.shared;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Render/libpq-style Postgres connection URL
 * ("postgres[ql]://user:password@host[:port]/db[?query]") into the pieces Spring's datasource
 * needs: a JDBC URL plus a separate username and password.
 *
 * <p>Render exposes a managed database's connection string as an env var in libpq form and keeps
 * it current across credential rotations. Spring's {@code spring.datasource.url} wants JDBC form
 * with the credentials supplied separately, so this bridges the two. Render-generated passwords
 * are alphanumeric; the username/password are still URL-decoded so a percent-encoded value would
 * round-trip correctly.
 */
public final class RenderDatabaseUrl {

    // user has no ':' '@' '/'; password is everything up to the LAST '@' (host group forbids '@');
    // host has no '@' ':' '/' '?'; optional :port; '/'db (no '?'); optional '?'query.
    private static final Pattern PATTERN = Pattern.compile(
            "^postgres(?:ql)?://([^:@/]+):(.*)@([^@:/?]+)(?::(\\d+))?/([^?]+)(?:\\?(.*))?$");

    private RenderDatabaseUrl() {
    }

    public record Parsed(String jdbcUrl, String username, String password) {
    }

    public static Optional<Parsed> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(url.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String username = decode(m.group(1));
        String password = decode(m.group(2));
        String host = m.group(3);
        String port = m.group(4);
        String database = m.group(5);
        String query = m.group(6);

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(host);
        if (port != null) {
            jdbc.append(':').append(port);
        }
        jdbc.append('/').append(database);
        if (query != null && !query.isBlank()) {
            jdbc.append('?').append(query);
        }
        return Optional.of(new Parsed(jdbc.toString(), username, password));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12-tem" ./gradlew test --tests 'com.robsartin.setlistscout.shared.RenderDatabaseUrlTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/shared/RenderDatabaseUrl.java \
        src/test/java/com/robsartin/setlistscout/shared/RenderDatabaseUrlTest.java
git commit -m "feat: parse Render Datastore URL into JDBC url + credentials (#81)"
```

---

## Task 2: `DatabaseUrlEnvironmentPostProcessor` + registration

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/shared/DatabaseUrlEnvironmentPostProcessor.java`
- Create: `src/main/resources/META-INF/spring.factories`
- Test: `src/test/java/com/robsartin/setlistscout/shared/DatabaseUrlEnvironmentPostProcessorTest.java`

**Interfaces:**
- Consumes: `RenderDatabaseUrl.parse(String) -> Optional<RenderDatabaseUrl.Parsed>` from Task 1.
- Produces: a registered `org.springframework.boot.env.EnvironmentPostProcessor` that, when `DATABASE_CONNECTION_URL` is set, injects `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password` at highest precedence via a property source named `renderDatastoreUrl`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/shared/DatabaseUrlEnvironmentPostProcessorTest.java`:

```java
package com.robsartin.setlistscout.shared;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUrlEnvironmentPostProcessorTest {

    private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

    @Test
    void setsDatasourcePropertiesWhenConnectionUrlPresent() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_CONNECTION_URL", "postgresql://scout:secret@dpg-abc-a/scoutdata");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-abc-a/scoutdata");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("scout");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("secret");
    }

    @Test
    void takesPrecedenceOverExistingDatasourceProperties() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/setlistscout");
        env.setProperty("spring.datasource.username", "setlistscout");
        env.setProperty("spring.datasource.password", "setlistscout");
        env.setProperty("DATABASE_CONNECTION_URL", "postgresql://scout:secret@dpg-abc-a:5432/scoutdata");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-abc-a:5432/scoutdata");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("scout");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("secret");
    }

    @Test
    void noOpWhenConnectionUrlAbsent() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/setlistscout");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:5432/setlistscout");
        assertThat(env.getPropertySources().contains("renderDatastoreUrl")).isFalse();
    }

    @Test
    void noOpWhenConnectionUrlUnparseable() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_CONNECTION_URL", "not-a-url");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getPropertySources().contains("renderDatastoreUrl")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12-tem" ./gradlew test --tests 'com.robsartin.setlistscout.shared.DatabaseUrlEnvironmentPostProcessorTest'`
Expected: FAIL — compilation error, `DatabaseUrlEnvironmentPostProcessor` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/robsartin/setlistscout/shared/DatabaseUrlEnvironmentPostProcessor.java`:

```java
package com.robsartin.setlistscout.shared;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges Render's linked internal Datastore URL to Spring's datasource. When
 * {@code DATABASE_CONNECTION_URL} is present (Render keeps it current across credential
 * rotations), parse it and publish {@code spring.datasource.{url,username,password}} at highest
 * precedence so both JPA and Flyway use the rotated credentials with no manual env-var edits.
 *
 * <p>Absent or unparseable → no-op; the {@code application.yml} split-var configuration stands
 * (local dev, tests). Runs as an {@link EnvironmentPostProcessor} — before the application
 * context, Flyway, and JPA — registered in {@code META-INF/spring.factories}.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String ENV_VAR = "DATABASE_CONNECTION_URL";
    static final String SOURCE_NAME = "renderDatastoreUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(ENV_VAR);
        RenderDatabaseUrl.parse(raw).ifPresent(parsed -> {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("spring.datasource.url", parsed.jdbcUrl());
            props.put("spring.datasource.username", parsed.username());
            props.put("spring.datasource.password", parsed.password());
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
        });
    }
}
```

Create `src/main/resources/META-INF/spring.factories`:

```properties
org.springframework.boot.env.EnvironmentPostProcessor=\
com.robsartin.setlistscout.shared.DatabaseUrlEnvironmentPostProcessor
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12-tem" ./gradlew test --tests 'com.robsartin.setlistscout.shared.DatabaseUrlEnvironmentPostProcessorTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/shared/DatabaseUrlEnvironmentPostProcessor.java \
        src/main/resources/META-INF/spring.factories \
        src/test/java/com/robsartin/setlistscout/shared/DatabaseUrlEnvironmentPostProcessorTest.java
git commit -m "feat: apply Render Datastore URL to spring.datasource via EnvironmentPostProcessor (#81)"
```

---

## Task 3: Full-suite gate (verification only)

**Files:** none.

- [ ] **Step 1: Compile + run the non-Docker test suite**

Run: `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12-tem" ./gradlew build -x test --warning-mode all`
Then the unit tests (excluding the Testcontainers smoke test which needs Docker):
Run: `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12-tem" ./gradlew test --tests 'com.robsartin.setlistscout.shared.*'`
Expected: BUILD SUCCESSFUL; 10 tests pass.

- [ ] **Step 2: Note skipped coverage**

If Docker is unavailable, `ApplicationContextSmokeTest` (Testcontainers) does not run locally — record that it was skipped and rely on CI to run the full context boot. Do not report the gate as fully passed if the smoke test was skipped.

---

## Self-Review

- **Spec coverage:** `RenderDatabaseUrl` parser (Task 1) ✓; `DatabaseUrlEnvironmentPostProcessor` + `spring.factories` (Task 2) ✓; precedence over `application.yml` (Task 2 test) ✓; no-op fallback when absent/unparseable (Task 2 tests) ✓; parser edge cases — port/no-port/schemes/encoding/query/invalid (Task 1 tests) ✓; existing smoke test still boots (Task 3) ✓; `application.yml` untouched (Global Constraints) ✓.
- **Placeholders:** none — all steps carry real code and exact commands.
- **Type consistency:** `RenderDatabaseUrl.parse` returns `Optional<Parsed>` with accessors `jdbcUrl()/username()/password()`, used consistently in Task 2. Property source name `renderDatastoreUrl` and env var `DATABASE_CONNECTION_URL` match across implementation and tests.

## Ops rollout (after merge & deploy — not code)

Tracked in the spec; performed in the Render dashboard once the PR merges and the service redeploys:
1. Add linked env var `DATABASE_CONNECTION_URL` → `database` internal Datastore URL; verify healthy.
2. Remove the now-unused static `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD`.
3. Rotate: new default credential + delete old `scout`; linked URL auto-updates → redeploy → verify logs. Leaked credential invalidated.
