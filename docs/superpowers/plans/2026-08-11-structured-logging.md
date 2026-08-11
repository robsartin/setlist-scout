# Structured Logging & Correlation IDs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Setlist Scout JSONL structured logs with a UUIDv7 correlation id on every HTTP request and a separate id per background scan/expansion, plus leveled, meaningful log statements throughout the pipeline.

**Architecture:** A UUIDv7 helper feeds a SLF4J-MDC-based correlation context. A servlet filter stamps each HTTP request; a small `Correlation.run(...)` helper stamps background work (per operation, per owner). Spring Boot 3.4's native ECS structured logging turns MDC + key-values into one JSON object per line. Service catch-blocks that currently swallow errors gain WARN logs; the scan/expansion pipelines gain INFO start/finish with counts. A tiny authenticated endpoint flips the log level at runtime via Spring's `LoggingSystem`.

**Tech Stack:** Spring Boot 3.4.13, Java 21, SLF4J 2 (fluent `atInfo().addKeyValue()`), Logback (Spring's ECS structured encoder), `com.fasterxml.uuid:java-uuid-generator`, JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Spring Boot **3.4.13** (already on main); use its **native** structured logging — do NOT add `logstash-logback-encoder`.
- Run Gradle with `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem` (Gradle 8.14 can't launch on JDK 25).
- Docker is unavailable locally → Testcontainers tests (`ApplicationContextSmokeTest`, `ArtistPageRenderTest`, `*MigrationTest`) run in **CI only**. Locally verify with `compileJava`/`compileTestJava` + the named non-container test classes.
- Every commit message ends with: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- MDC keys are fixed constants on `Correlation`: `cid`, `owner`, `job`, `parentCid`. Base package for level control: `com.robsartin.setlistscout`.
- All new production classes live in package `com.robsartin.setlistscout.observability` unless noted.

---

### Task 1: UUIDv7 generator (`CorrelationIds`)

**Files:**
- Modify: `build.gradle.kts` (add dependency)
- Create: `src/main/java/com/robsartin/setlistscout/observability/CorrelationIds.java`
- Test: `src/test/java/com/robsartin/setlistscout/observability/CorrelationIdsTest.java`

**Interfaces:**
- Produces: `CorrelationIds.newId(): String` (a UUIDv7 string), `CorrelationIds.isValid(String): boolean`.

- [ ] **Step 1: Add the dependency**

In `build.gradle.kts`, in the `dependencies { }` block near the other `implementation(...)` lines, add:

```kotlin
    // UUIDv7 (time-ordered) correlation ids; Java's built-in UUID is v4 only.
    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/observability/CorrelationIdsTest.java`:

```java
package com.robsartin.setlistscout.observability;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdsTest {

    @Test
    void newIdIsAValidVersion7Uuid() {
        String id = CorrelationIds.newId();
        assertThat(CorrelationIds.isValid(id)).isTrue();
        assertThat(UUID.fromString(id).version()).isEqualTo(7);
    }

    @Test
    void successiveIdsAreTimeOrdered() {
        String first = CorrelationIds.newId();
        String second = CorrelationIds.newId();
        // v7 is time-ordered and lexicographically sortable; the generator is monotonic.
        assertThat(first.compareTo(second)).isLessThan(0);
    }

    @Test
    void isValidRejectsJunk() {
        assertThat(CorrelationIds.isValid(null)).isFalse();
        assertThat(CorrelationIds.isValid("")).isFalse();
        assertThat(CorrelationIds.isValid("not-a-uuid")).isFalse();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.observability.CorrelationIdsTest"`
Expected: FAIL — `CorrelationIds` does not exist (compile error).

- [ ] **Step 4: Write the implementation**

Create `src/main/java/com/robsartin/setlistscout/observability/CorrelationIds.java`:

```java
package com.robsartin.setlistscout.observability;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import java.util.UUID;

/** Time-ordered (UUIDv7) correlation ids. v7 sorts chronologically, which keeps logs orderable. */
public final class CorrelationIds {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private CorrelationIds() {
    }

    public static String newId() {
        return GENERATOR.generate().toString();
    }

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.observability.CorrelationIdsTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/java/com/robsartin/setlistscout/observability/CorrelationIds.java src/test/java/com/robsartin/setlistscout/observability/CorrelationIdsTest.java
git commit -m "Add UUIDv7 correlation-id generator (#69)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: MDC context helper (`Correlation`)

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/observability/Correlation.java`
- Test: `src/test/java/com/robsartin/setlistscout/observability/CorrelationTest.java`

**Interfaces:**
- Consumes: `CorrelationIds.newId()`.
- Produces: constants `Correlation.CID/OWNER/JOB/PARENT_CID` (String), and `Correlation.run(String job, String owner, String parentCid, Runnable body)` which stamps a fresh `cid` + the given fields into MDC, runs `body`, and clears them in a `finally`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/observability/CorrelationTest.java`:

```java
package com.robsartin.setlistscout.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationTest {

    @Test
    void runStampsFieldsDuringBodyAndClearsAfter() {
        AtomicReference<String> cidDuring = new AtomicReference<>();
        AtomicReference<String> jobDuring = new AtomicReference<>();
        AtomicReference<String> ownerDuring = new AtomicReference<>();
        AtomicReference<String> parentDuring = new AtomicReference<>();

        Correlation.run("scan", "rob@example.com", "parent-123", () -> {
            cidDuring.set(MDC.get(Correlation.CID));
            jobDuring.set(MDC.get(Correlation.JOB));
            ownerDuring.set(MDC.get(Correlation.OWNER));
            parentDuring.set(MDC.get(Correlation.PARENT_CID));
        });

        assertThat(CorrelationIds.isValid(cidDuring.get())).isTrue();
        assertThat(jobDuring.get()).isEqualTo("scan");
        assertThat(ownerDuring.get()).isEqualTo("rob@example.com");
        assertThat(parentDuring.get()).isEqualTo("parent-123");

        assertThat(MDC.get(Correlation.CID)).isNull();
        assertThat(MDC.get(Correlation.JOB)).isNull();
        assertThat(MDC.get(Correlation.OWNER)).isNull();
        assertThat(MDC.get(Correlation.PARENT_CID)).isNull();
    }

    @Test
    void runClearsMdcEvenWhenBodyThrows() {
        assertThatThrownBy(() -> Correlation.run("scan", "rob@example.com", null,
                () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(Correlation.CID)).isNull();
        assertThat(MDC.get(Correlation.JOB)).isNull();
    }

    @Test
    void nullOwnerAndParentAreOmitted() {
        AtomicReference<String> ownerDuring = new AtomicReference<>("sentinel");
        Correlation.run("expansion", null, null,
                () -> ownerDuring.set(MDC.get(Correlation.OWNER)));
        assertThat(ownerDuring.get()).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.observability.CorrelationTest"`
Expected: FAIL — `Correlation` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/robsartin/setlistscout/observability/Correlation.java`:

```java
package com.robsartin.setlistscout.observability;

import org.slf4j.MDC;

/**
 * Sets correlation fields into SLF4J's MDC so every log line inside a unit of work carries them.
 * {@link #run} is used for background work (scheduled and manual async jobs); the HTTP path is
 * handled by {@link CorrelationIdFilter}.
 */
public final class Correlation {

    public static final String CID = "cid";
    public static final String OWNER = "owner";
    public static final String JOB = "job";
    public static final String PARENT_CID = "parentCid";

    private Correlation() {
    }

    /**
     * Run {@code body} with a fresh {@code cid} plus {@code job} (and {@code owner}/{@code parentCid}
     * when non-null) in MDC, clearing all four afterward even if {@code body} throws.
     */
    public static void run(String job, String owner, String parentCid, Runnable body) {
        MDC.put(CID, CorrelationIds.newId());
        MDC.put(JOB, job);
        if (owner != null) {
            MDC.put(OWNER, owner);
        }
        if (parentCid != null) {
            MDC.put(PARENT_CID, parentCid);
        }
        try {
            body.run();
        } finally {
            MDC.remove(CID);
            MDC.remove(JOB);
            MDC.remove(OWNER);
            MDC.remove(PARENT_CID);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.observability.CorrelationTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/observability/Correlation.java src/test/java/com/robsartin/setlistscout/observability/CorrelationTest.java
git commit -m "Add MDC correlation context helper (#69)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: HTTP correlation filter (`CorrelationIdFilter`)

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/observability/CorrelationIdFilter.java`
- Test: `src/test/java/com/robsartin/setlistscout/observability/CorrelationIdFilterTest.java`

**Interfaces:**
- Consumes: `CorrelationIds`, `Correlation.CID`.
- Produces: a `@Component` `OncePerRequestFilter` that, per request, sets MDC `cid` (honoring a valid inbound `X-Request-Id` else minting one), echoes it on the response `X-Request-Id` header, logs one INFO line on completion (method, path, status, durationMs), and clears MDC.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/observability/CorrelationIdFilterTest.java`:

```java
package com.robsartin.setlistscout.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void mintsAValidCidWhenNoInboundHeaderAndEchoesItAndClearsAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/artists");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> cidDuring = new AtomicReference<>();
        FilterChain chain = (req, res) -> cidDuring.set(MDC.get(Correlation.CID));

        filter.doFilter(request, response, chain);

        assertThat(CorrelationIds.isValid(cidDuring.get())).isTrue();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(cidDuring.get());
        assertThat(MDC.get(Correlation.CID)).isNull(); // cleared after
    }

    @Test
    void honorsAValidInboundRequestId() throws Exception {
        String inbound = CorrelationIds.newId();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", inbound);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> cidDuring = new AtomicReference<>();
        FilterChain chain = (req, res) -> cidDuring.set(MDC.get(Correlation.CID));

        filter.doFilter(request, response, chain);

        assertThat(cidDuring.get()).isEqualTo(inbound);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(inbound);
    }

    @Test
    void ignoresAJunkInboundRequestIdAndMintsInstead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> cidDuring = new AtomicReference<>();
        FilterChain chain = (req, res) -> cidDuring.set(MDC.get(Correlation.CID));

        filter.doFilter(request, response, chain);

        assertThat(cidDuring.get()).isNotEqualTo("not-a-uuid");
        assertThat(CorrelationIds.isValid(cidDuring.get())).isTrue();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.observability.CorrelationIdFilterTest"`
Expected: FAIL — `CorrelationIdFilter` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/robsartin/setlistscout/observability/CorrelationIdFilter.java`:

```java
package com.robsartin.setlistscout.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Stamps every HTTP request with a correlation id (MDC {@code cid}) and logs one line per request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String cid = CorrelationIds.isValid(inbound) ? inbound : CorrelationIds.newId();
        MDC.put(Correlation.CID, cid);
        response.setHeader(HEADER, cid);
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", durationMs)
                    .log("http request");
            MDC.clear();
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.observability.CorrelationIdFilterTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/observability/CorrelationIdFilter.java src/test/java/com/robsartin/setlistscout/observability/CorrelationIdFilterTest.java
git commit -m "Add HTTP correlation-id filter (#69)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: JSONL log format + LOG_LEVEL knob (config)

**Files:**
- Modify: `src/main/resources/application.yml`

**Interfaces:** none (configuration). Enables ECS/JSONL console output and the `LOG_LEVEL` env knob.

- [ ] **Step 1: Add the logging config**

In `src/main/resources/application.yml`, under the top-level `logging:` key (create it if absent, as a sibling of `spring:` / `server:` / `management:`), add:

```yaml
logging:
  structured:
    format:
      console: ecs # one JSON object per line (JSONL), Elastic Common Schema; native to Spring Boot 3.4
  level:
    com.robsartin.setlistscout: ${LOG_LEVEL:INFO} # LOG_LEVEL env var is the volume knob (INFO default; set DEBUG for a deep dive)
```

- [ ] **Step 2: Verify the app still boots and logs are JSON**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon compileJava`
Expected: BUILD SUCCESSFUL. (Full boot + JSON-shape is confirmed by the CI `ApplicationContextSmokeTest` and by eyeballing Render logs after deploy; ECS output is Spring config, not unit-tested.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "Enable ECS/JSONL structured console logging + LOG_LEVEL knob (#69)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Correlation ids for background work (scheduler + async scan)

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/scheduler/ShowScanScheduler.java`
- Modify: `src/main/java/com/robsartin/setlistscout/service/AsyncScanRunner.java`
- Test: `src/test/java/com/robsartin/setlistscout/service/AsyncScanRunnerTest.java` (extend existing)

**Interfaces:**
- Consumes: `Correlation.run(...)`, `Correlation.CID`, MDC.
- Produces: scheduled `scanForShows`/`expandAll` each run under their own `cid` (`job=scan|expansion`, `owner`); the manual async scan runs under its own `cid` with `parentCid` = the triggering request's cid.

- [ ] **Step 1: Wrap the scheduler loop**

In `ShowScanScheduler.java`, replace the body of the `for` loop in `scan()` so each operation gets its own correlation context:

```java
    @Scheduled(fixedRateString = "${setlistscout.scan-interval-ms:259200000}")
    public void scan() {
        for (SearchSettings settings : settingsRepository.findAll()) {
            String owner = settings.getOwner();
            Correlation.run("expansion", owner, null, () -> expansionService.expandAll(owner));
            Correlation.run("scan", owner, null, () -> showAggregationService.scanForShows(owner));
        }
    }
```

Add the import: `import com.robsartin.setlistscout.observability.Correlation;`

- [ ] **Step 2: Capture the parent cid and wrap the async scan**

In `AsyncScanRunner.java`, add imports:

```java
import com.robsartin.setlistscout.observability.Correlation;
import org.slf4j.MDC;
```

Replace the `startScan` method body so the background task carries its own cid + the request's cid as parent:

```java
    public void startScan(String owner) {
        if (!scanState.tryStart(owner)) {
            return;
        }
        String parentCid = MDC.get(Correlation.CID); // the triggering request's cid, captured on the request thread
        executor.execute(() -> Correlation.run("scan", owner, parentCid, () -> {
            try {
                aggregation.scanForShows(owner);
            } catch (RuntimeException e) {
                log.error("Show scan failed for {}", owner, e);
            } finally {
                scanState.finish(owner);
            }
        }));
    }
```

- [ ] **Step 3: Extend the existing AsyncScanRunner test**

The existing `AsyncScanRunnerTest` injects a same-thread executor. Add a test that asserts the background task ran under a scan cid and cleared it afterward. Append this test method (match the existing test's field/constructor style — it constructs `new AsyncScanRunner(aggregation, scanState, sameThreadExecutor)` with mocks):

```java
    @Test
    void scanRunsUnderAScanCorrelationContextAndClearsItAfter() {
        when(scanState.tryStart(OWNER)).thenReturn(true);
        java.util.concurrent.atomic.AtomicReference<String> cidDuring = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> jobDuring = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            cidDuring.set(org.slf4j.MDC.get(com.robsartin.setlistscout.observability.Correlation.CID));
            jobDuring.set(org.slf4j.MDC.get(com.robsartin.setlistscout.observability.Correlation.JOB));
            return null;
        }).when(aggregation).scanForShows(OWNER);

        runner.startScan(OWNER);

        assertThat(com.robsartin.setlistscout.observability.CorrelationIds.isValid(cidDuring.get())).isTrue();
        assertThat(jobDuring.get()).isEqualTo("scan");
        assertThat(org.slf4j.MDC.get(com.robsartin.setlistscout.observability.Correlation.CID)).isNull();
        verify(scanState).finish(OWNER);
    }
```

If the existing test file lacks names used here (`runner`, `aggregation`, `scanState`, `OWNER`, `doAnswer`, `verify`, `when`, `assertThat`), read the file first and adapt to its actual field names and static imports rather than assuming.

- [ ] **Step 4: Run the tests**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.service.AsyncScanRunnerTest"`
Expected: PASS (existing tests + the new one).

- [ ] **Step 5: Verify compile of the scheduler change**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scheduler/ShowScanScheduler.java src/main/java/com/robsartin/setlistscout/service/AsyncScanRunner.java src/test/java/com/robsartin/setlistscout/service/AsyncScanRunnerTest.java
git commit -m "Correlation ids for scheduled + async background work (#69)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Runtime log-level toggle (`LogLevelController` + UI)

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/web/LogLevelController.java`
- Modify: `src/main/resources/templates/artists.html` (add the toggle near the nav)
- Test: `src/test/java/com/robsartin/setlistscout/web/LogLevelControllerTest.java`

**Interfaces:**
- Consumes: Spring's `org.springframework.boot.logging.LoggingSystem` bean and `LogLevel` enum.
- Produces: `POST /artists/log-level` (kept under the already-authenticated `/artists` tree; body param `level`) → sets the level for `com.robsartin.setlistscout` via `LoggingSystem.setLogLevel`, ignoring an invalid level; redirects to `/artists`.

Note: mount under `/artists/log-level` (not `/admin/...`) so it inherits the existing authenticated area and the working Thymeleaf CSRF form handling with no SecurityConfig change.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/web/LogLevelControllerTest.java`:

```java
package com.robsartin.setlistscout.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LogLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LogLevelControllerTest {

    private LoggingSystem loggingSystem;
    private LogLevelController controller;

    @BeforeEach
    void setUp() {
        loggingSystem = mock(LoggingSystem.class);
        controller = new LogLevelController(loggingSystem);
    }

    @Test
    void setsTheLevelForOurPackageAndRedirects() {
        String view = controller.setLevel("DEBUG");

        assertThat(view).isEqualTo("redirect:/artists");
        verify(loggingSystem).setLogLevel("com.robsartin.setlistscout", LogLevel.DEBUG);
    }

    @Test
    void lowercaseLevelIsAccepted() {
        controller.setLevel("info");
        verify(loggingSystem).setLogLevel("com.robsartin.setlistscout", LogLevel.INFO);
    }

    @Test
    void invalidLevelIsIgnoredWithoutThrowing() {
        String view = controller.setLevel("bogus");

        assertThat(view).isEqualTo("redirect:/artists");
        verify(loggingSystem, never()).setLogLevel(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.LogLevelControllerTest"`
Expected: FAIL — `LogLevelController` does not exist.

- [ ] **Step 3: Write the controller**

Create `src/main/java/com/robsartin/setlistscout/web/LogLevelController.java`:

```java
package com.robsartin.setlistscout.web;

import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;

/**
 * Flips the app's log level at runtime (no redeploy) via Spring's LoggingSystem -- the same call
 * Actuator's loggers endpoint makes. Ephemeral: resets to the LOG_LEVEL env default on restart.
 * Under /artists so it inherits the authenticated area and existing CSRF form handling.
 */
@Controller
public class LogLevelController {

    private static final String PACKAGE = "com.robsartin.setlistscout";

    private final LoggingSystem loggingSystem;

    public LogLevelController(LoggingSystem loggingSystem) {
        this.loggingSystem = loggingSystem;
    }

    @PostMapping("/artists/log-level")
    public String setLevel(@RequestParam String level) {
        try {
            loggingSystem.setLogLevel(PACKAGE, LogLevel.valueOf(level.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            // Unknown level (e.g. a typo) -- leave the current level unchanged.
        }
        return "redirect:/artists";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.LogLevelControllerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Add the UI toggle**

In `src/main/resources/templates/artists.html`, immediately after the `<nav> ... </nav>` block, add:

```html
    <form class="inline" th:action="@{/artists/log-level}" method="post">
        <span class="note">Log level:</span>
        <button type="submit" name="level" value="INFO">INFO</button>
        <button type="submit" name="level" value="DEBUG">DEBUG</button>
    </form>
```

- [ ] **Step 6: Verify compile**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon compileJava compileTestJava`
Expected: BUILD SUCCESSFUL. (Template render + auth are covered by the CI `ArtistPageRenderTest`; if that test asserts on exact page structure it will still pass since we only added a form.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/web/LogLevelController.java src/main/resources/templates/artists.html src/test/java/com/robsartin/setlistscout/web/LogLevelControllerTest.java
git commit -m "Add runtime log-level toggle (#69)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Pipeline instrumentation — scan & expansion (INFO start/finish + counts)

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/service/ShowAggregationService.java`
- Modify: `src/main/java/com/robsartin/setlistscout/service/ExpansionService.java`
- Test: existing `ShowAggregationServiceTest` and `ExpansionServiceTest` still pass (behavior unchanged; logging is not asserted). If `ExpansionServiceTest` does not exist, no new test is required for this task — the change is log-only and covered by compile + the existing scan test.

**Interfaces:**
- Consumes: SLF4J `atInfo()/atDebug()` fluent API; MDC already carries `cid`/`owner`/`job` from Task 5.
- Produces: `persistNew` returns an `int` count of shows saved (used for the finish log).

- [ ] **Step 1: Instrument `ShowAggregationService`**

Add a logger field and imports at the top of the class:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```
```java
    private static final Logger log = LoggerFactory.getLogger(ShowAggregationService.class);
```

Change `persistNew` to return the number saved:

```java
    private int persistNew(String owner, List<Show> shows) {
        int saved = 0;
        for (Show show : shows) {
            if (show.getEventDateTime() == null) continue;
            boolean exists = showRepository.existsByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                    owner, show.getArtistName(), show.getEventDateTime(), show.getVenueName());
            if (!exists) {
                show.setOwner(owner);
                showRepository.save(show);
                saved++;
            }
        }
        return saved;
    }
```

Rewrite the `scanForShows` loop to count and log (keep the existing blank-name guard and the three source calls; only add the counters and log lines):

```java
    public void scanForShows(String owner) {
        SearchSettings settings = settingsRepository.findByOwner(owner)
                .orElseThrow(() -> new IllegalStateException(
                        "SearchSettings row missing for " + owner + " -- provisioned on first login"));

        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());

        List<Artist> activeArtists = artistRepository.findByOwnerAndStatusIn(
                owner, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));

        log.atInfo().addKeyValue("activeArtists", activeArtists.size()).log("scan started");
        long startNanos = System.nanoTime();
        int searched = 0;
        int found = 0;
        int saved = 0;
        for (Artist artist : activeArtists) {
            if (artist.getName() == null || artist.getName().isBlank()) continue;
            searched++;

            List<Show> tmShows = ticketmaster.searchShows(
                    artist.getName(), settings.getPostalCode(), settings.getRadiusMiles(), start, end);
            List<Show> bitShows = bandsintown.searchShows(
                    artist.getName(), settings.getLatitude(), settings.getLongitude(),
                    settings.getRadiusMiles(), start, end);
            List<Show> siteShows = scrapeBandSite(artist, settings, start, end);

            found += tmShows.size() + bitShows.size() + siteShows.size();
            saved += persistNew(owner, tmShows);
            saved += persistNew(owner, bitShows);
            saved += persistNew(owner, siteShows);

            log.atDebug()
                    .addKeyValue("artist", artist.getName())
                    .addKeyValue("ticketmaster", tmShows.size())
                    .addKeyValue("bandsintown", bitShows.size())
                    .addKeyValue("bandSite", siteShows.size())
                    .log("artist scanned");
        }

        log.atInfo()
                .addKeyValue("artistsSearched", searched)
                .addKeyValue("showsFound", found)
                .addKeyValue("showsSaved", saved)
                .addKeyValue("durationMs", (System.nanoTime() - startNanos) / 1_000_000)
                .log("scan finished");
    }
```

- [ ] **Step 2: Instrument `ExpansionService`**

Add the logger + imports:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```
```java
    private static final Logger log = LoggerFactory.getLogger(ExpansionService.class);
```

Have `saveIfNew` return a boolean already? It currently returns void. Change it to return `boolean` (true when saved) so `expandAll` can count, and wrap the loop with counters + a finish log:

```java
    public void expandAll(String owner) {
        List<Artist> baseArtists = artistRepository.findByOwnerAndStatusIn(
                owner, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));

        log.atInfo().addKeyValue("baseArtists", baseArtists.size()).log("expansion started");
        long startNanos = System.nanoTime();
        int processed = 0;
        int candidates = 0;
        for (Artist base : baseArtists) {
            processed++;
            candidates += expandMemberRelations(owner, base);
            candidates += expandSimilarArtists(owner, base);
            if (base.getStatus() == ArtistStatus.SEED) {
                candidates += expandTributeBands(owner, base);
            }
        }
        log.atInfo()
                .addKeyValue("artistsProcessed", processed)
                .addKeyValue("candidatesFound", candidates)
                .addKeyValue("durationMs", (System.nanoTime() - startNanos) / 1_000_000)
                .log("expansion finished");
    }
```

Change the three `expand*` helpers to return the count they added (they currently return void), and `saveIfNew` to return boolean. For each helper, sum the `saveIfNew` results. Example for `expandMemberRelations` (apply the same shape to `expandSimilarArtists` and `expandTributeBands`):

```java
    private int expandMemberRelations(String owner, Artist base) {
        Set<String> found = new HashSet<>();
        found.addAll(musicBrainz.findRelatedArtists(base.getName()));
        found.addAll(discogs.findRelatedArtists(base.getName()));

        int added = 0;
        for (String name : found) {
            if (saveIfNew(owner, name, ArtistSource.MEMBER_EXPANSION, base.getName(),
                    "member/lineup relation of " + base.getName())) {
                added++;
            }
        }
        log.atDebug().addKeyValue("artist", base.getName()).addKeyValue("members", added).log("member expansion");
        return added;
    }
```

And `saveIfNew`:

```java
    private boolean saveIfNew(String owner, String name, ArtistSource source, String discoveredVia, String note) {
        if (name == null || name.isBlank()) return false;
        if (artistRepository.existsByOwnerAndNameIgnoreCase(owner, name)) return false;
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, note);
        artist.setOwner(owner);
        artistRepository.save(artist);
        return true;
    }
```

(Apply the analogous `int` return + `log.atDebug()...log("similar expansion")` to `expandSimilarArtists` — key `similar`; and `...log("tribute expansion")` to `expandTributeBands` — key `tributes`.)

- [ ] **Step 3: Run the affected unit tests**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon test --tests "com.robsartin.setlistscout.service.ShowAggregationServiceTest"`
Expected: PASS (unchanged behavior — the blank-name guard test still holds).

- [ ] **Step 4: Verify full compile**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/service/ShowAggregationService.java src/main/java/com/robsartin/setlistscout/service/ExpansionService.java
git commit -m "Instrument scan + expansion with INFO start/finish counts (#69)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: WARN-on-swallowed-errors + DEBUG outcomes across the API services

This is the core payoff: every place a service **catches an exception and returns empty** currently hides the reason. Add a logger and a WARN in each catch block so silent empties become visible, plus a DEBUG line with the result count on success.

**Files (each Modify — add a logger field + WARN in existing catch blocks + a DEBUG success line):**
- `service/TicketmasterService.java`
- `service/BandsintownService.java`
- `service/MusicBrainzService.java`
- `service/DiscogsService.java`
- `service/LastFmService.java`
- `service/SimilarArtistLlmService.java`
- `service/TributeLlmService.java`
- `service/TourPageLlmService.java`
- `service/GeocodingService.java`

**Interfaces:** none new. Log-only change; all method signatures and return values are unchanged.

**Pattern to apply in every file:**

- [ ] **Step 1: Add a logger to each service**

At the top of each class add (imports + field), using that class's own name in `getLogger`:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```
```java
    private static final Logger log = LoggerFactory.getLogger(TicketmasterService.class); // <-- this class's name
```

- [ ] **Step 2: Add a WARN in each existing catch block**

For every existing `catch (Exception e) { ... return empty ... }` (and `catch (NumberFormatException | ...)` blocks that swallow), add a WARN before the fallback that names the source, the relevant input, and the cause. Example in `TicketmasterService.searchShows` catch:

```java
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "ticketmaster")
                    .addKeyValue("artist", artistName)
                    .log("show search failed");
            response = Map.of();
        }
```

Apply the same shape to each service's catch, using the source name and the input in scope:
- Bandsintown → `source=bandsintown`, `artist=artistName`.
- MusicBrainz → `source=musicbrainz`, `artist=artistName` (both catches in `findRelatedArtists` and `findOfficialHomepage`).
- Discogs → `source=discogs`, `artist=<the name param>`.
- Last.fm → `source=lastfm`, `artist=<the name param>`.
- SimilarArtistLlm → `source=similar-llm`, `artist=<the name param>`.
- TributeLlm → `source=tribute-llm`, `artist=<the name param>`.
- TourPageLlm → `source=tour-llm`, `artist=<the name param>` (or `url` if that's what's in scope).
- Geocoding → `source=geocoding`, `zip=<the zip param>`.
- BandSiteScraperService (`service/BandSiteScraperService.java`) → in its `catch (Exception e) { return List.of(); }`, add `source=band-site`, `url=siteUrl`.

- [ ] **Step 3: Add a DEBUG success line at each service's main return**

Before the successful `return` of each public search/lookup method, add a DEBUG with the result size, e.g. in `TicketmasterService.searchShows`:

```java
        log.atDebug().addKeyValue("source", "ticketmaster").addKeyValue("artist", artistName)
                .addKeyValue("count", shows.size()).log("show search");
        return shows;
```

Apply analogously to each service's primary method (use `count = <the returned collection>.size()`; for `MusicBrainz.findOfficialHomepage` and `Geocoding.geocode` which return `Optional`, log `.addKeyValue("found", result.isPresent())`).

- [ ] **Step 4: Verify compile + existing service tests pass**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon compileJava compileTestJava test --tests "com.robsartin.setlistscout.service.*"`
Expected: BUILD SUCCESSFUL — behavior unchanged (log-only additions); the MockWebServer-based service tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/service/
git commit -m "Log WARN on swallowed external-call errors + DEBUG outcomes (#69)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: Final verification + PR

**Files:** none (verification only).

- [ ] **Step 1: Full local gate (minus Docker)**

Run: `JAVA_HOME=~/.sdkman/candidates/java/21.0.12-tem ./gradlew --no-daemon compileJava compileTestJava test --tests "com.robsartin.setlistscout.observability.*" --tests "com.robsartin.setlistscout.service.*" --tests "com.robsartin.setlistscout.web.ShowControllerTest" --tests "com.robsartin.setlistscout.web.ArtistControllerTest" --tests "com.robsartin.setlistscout.web.LogLevelControllerTest"`
Then: `python3 scripts/check_adrs.py`
Expected: all PASS.

- [ ] **Step 2: Push and open the PR (spec + implementation together, per #69)**

```bash
git push -u origin structured-logging-spec
gh pr create --repo robsartin/setlist-scout --base main --head structured-logging-spec \
  --title "Structured logging & correlation IDs (#69)" \
  --body "Closes #69. Implements docs/superpowers/specs/2026-08-11-structured-logging-design.md: ECS/JSONL logs, UUIDv7 correlation ids (per request + per-operation-per-owner), leveled instrumentation with LOG_LEVEL knob + runtime toggle, and WARN-on-swallowed-errors. CI runs the Testcontainers boot/migration/render tests.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

- [ ] **Step 3: Confirm CI green** (Build & test + ADR compliance), then hand off for review/merge.
