# Cover/Tribute Band Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third artist-expansion dimension that discovers cover/tribute bands for each seed band via the LLM and routes them through the existing pending-review gate.

**Architecture:** A new `TributeLlmService` (a near-clone of `SimilarArtistLlmService`) asks Claude for tribute/cover acts of a band. `ExpansionService` calls it for `SEED` artists only, saving results as `PENDING_REVIEW` with the new `TRIBUTE_EXPANSION` source. No UI, repository, or show-search changes — the review gate and `/artists` page already handle a new source generically.

**Tech Stack:** Java 21, Spring Boot, Spring `WebClient`, JUnit 5, Mockito, AssertJ, OkHttp `MockWebServer`, Gradle 8.14.3, ADR toolkit (`scripts/check_adrs.py`).

## Global Constraints

- **Branch:** `10-tribute-band-expansion` (already cut from `main`); PR to `main`, squash-merge. Issue: #10.
- **TDD:** red → green → refactor → commit for every task. Failing test runs before the implementation exists.
- **LLM model:** `claude-sonnet-5` — copy verbatim from `SimilarArtistLlmService`; do not change the model string.
- **Discovery scope:** tribute expansion runs for `ArtistStatus.SEED` artists only. Member and similar expansion keep their existing `SEED + APPROVED` scope.
- **Degradation:** any LLM/network/parse failure returns an empty list (`onErrorReturn(Map.of())` + null guards) — never throws into the expansion loop.
- **Local test command:** `./gradlew --no-daemon test` (Gradle 8.14.3 launches on the local JDK 25). Full merge gate: `./gradlew --no-daemon build` **and** `python3 scripts/check_adrs.py`, matching `.github/workflows/ci.yml`.
- **ADR discipline:** a new source gets an ADR (per ADR-0013); the ADR must be linked from `docs/adr/README.md` or `check_adrs.py` fails.

---

### Task 1: `TributeLlmService` + unit tests

Self-contained: a new service returning `List<String>` of tribute-act names. It does not reference `ArtistSource`, so it needs nothing from later tasks. Mirrors `SimilarArtistLlmService` exactly except for the method name and prompt.

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/service/TributeLlmService.java`
- Test: `src/test/java/com/robsartin/setlistscout/service/TributeLlmServiceTest.java`

**Interfaces:**
- Consumes: `AppProperties` (existing); `TestAppProperties.withKeys()` (existing test fixture).
- Produces: `List<String> TributeLlmService.findTributeBands(String artistName, int count)` — used by Task 2. Package-private test-seam constructor `TributeLlmService(AppProperties props, String baseUrl)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/setlistscout/service/TributeLlmServiceTest.java`:

```java
package com.robsartin.setlistscout.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TributeLlmServiceTest {

    private MockWebServer server;
    private TributeLlmService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new TributeLlmService(TestAppProperties.withKeys(), server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("should strip numbering and bullets but keep plain lines as-is")
    void shouldParseMixedFormattingLines() {
        server.enqueue(json("""
                {"content": [{"text": "1. The Iron Maidens\\n- Dread Zeppelin\\nMandonna"}]}
                """));

        List<String> result = service.findTributeBands("Iron Maiden", 3);

        assertThat(result).containsExactly("The Iron Maidens", "Dread Zeppelin", "Mandonna");
    }

    @Test
    @DisplayName("should skip blank lines")
    void shouldSkipBlankLines() {
        server.enqueue(json("""
                {"content": [{"text": "The Iron Maidens\\n\\nDread Zeppelin"}]}
                """));

        List<String> result = service.findTributeBands("Iron Maiden", 2);

        assertThat(result).containsExactly("The Iron Maidens", "Dread Zeppelin");
    }

    @Test
    @DisplayName("should return an empty list when the model reports no known tributes")
    void shouldReturnEmptyWhenNoneKnown() {
        server.enqueue(json("""
                {"content": [{"text": ""}]}
                """));

        List<String> result = service.findTributeBands("Some Obscure Band", 5);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return an empty list when content is missing")
    void shouldReturnEmptyWhenContentMissing() {
        server.enqueue(json("{}"));

        List<String> result = service.findTributeBands("Iron Maiden", 3);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return an empty list on server error")
    void shouldReturnEmptyOnServerError() {
        server.enqueue(new MockResponse().setResponseCode(500));

        List<String> result = service.findTributeBands("Iron Maiden", 3);

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "com.robsartin.setlistscout.service.TributeLlmServiceTest"`
Expected: FAIL — compilation error, `TributeLlmService` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/robsartin/setlistscout/service/TributeLlmService.java`:

```java
package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers cover/tribute acts for a band via the LLM. Tribute bands don't appear
 * in MusicBrainz lineup relations or Last.fm "similar artist" lists, so this is the
 * only source for them (see ADR-0017). Results feed the pending-review gate like any
 * other expansion source; false positives are absorbed there (ADR-0004).
 */
@Service
public class TributeLlmService {

    private final WebClient webClient;
    private final String apiKey;
    private static final Pattern LINE_ITEM = Pattern.compile("^\\s*[-\\d.]+\\s*[).]?\\s*(.+)$");

    public TributeLlmService(AppProperties props) {
        this(props, "https://api.anthropic.com/v1");
    }

    /** Test seam: points at a local stub server instead of the real Anthropic API. */
    TributeLlmService(AppProperties props, String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.apiKey = props.apis().anthropicApiKey();
    }

    @SuppressWarnings("unchecked")
    public List<String> findTributeBands(String artistName, int count) {
        List<String> result = new ArrayList<>();
        String prompt = "List up to " + count + " well-known tribute or cover bands that perform"
                + " the music of \"" + artistName + "\". Include only real, currently- or recently-"
                + "active tribute acts. One name per line, no numbering, no commentary."
                + " If you don't know any, return nothing.";

        // Model string as of mid-2026 -- check docs.claude.com/en/docs/about-claude/models
        // if this starts returning errors, since these names change over time.
        Map<String, Object> body = Map.of(
                "model", "claude-sonnet-5",
                "max_tokens", 300,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        Map<String, Object> response = webClient.post()
                .uri("/messages")
                .header("x-api-key", apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of())
                .block();

        if (response == null) return result;
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null || content.isEmpty()) return result;

        String text = (String) content.get(0).get("text");
        if (text == null) return result;

        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            Matcher m = LINE_ITEM.matcher(line.trim());
            result.add(m.matches() ? m.group(1).trim() : line.trim());
        }
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "com.robsartin.setlistscout.service.TributeLlmServiceTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/service/TributeLlmService.java \
        src/test/java/com/robsartin/setlistscout/service/TributeLlmServiceTest.java
git commit -m "Add TributeLlmService for LLM tribute-band discovery (#10)"
```

---

### Task 2: `TRIBUTE_EXPANSION` source + `ExpansionService` wiring

Adds the enum constant and wires tribute discovery into the expansion loop, seed-only. The enum add, the constructor change, and the new expansion branch are one reviewable deliverable — the wiring can't compile or be tested without the enum.

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/domain/ArtistSource.java`
- Modify: `src/main/java/com/robsartin/setlistscout/service/ExpansionService.java`
- Test: `src/test/java/com/robsartin/setlistscout/service/ExpansionServiceTest.java`

**Interfaces:**
- Consumes: `TributeLlmService.findTributeBands(String, int)` from Task 1; existing `Artist`, `ArtistSource`, `ArtistStatus`, `ArtistRepository.existsByNameIgnoreCase`, `ArtistRepository.save`.
- Produces: `ArtistSource.TRIBUTE_EXPANSION`; `ExpansionService(ArtistRepository, MusicBrainzService, DiscogsService, LastFmService, SimilarArtistLlmService, TributeLlmService)` (6-arg constructor).

- [ ] **Step 1: Write the failing test**

In `src/test/java/com/robsartin/setlistscout/service/ExpansionServiceTest.java`, add a `TributeLlmService` mock, update the `setUp()` constructor call to pass it, and add two new tests. Apply these three edits:

Add the mock field alongside the others:

```java
    @Mock private SimilarArtistLlmService similarArtistLlm;
    @Mock private TributeLlmService tributeLlm;
```

Update `setUp()` to the 6-arg constructor:

```java
    @BeforeEach
    void setUp() {
        expansionService = new ExpansionService(
                artistRepository, musicBrainz, discogs, lastFm, similarArtistLlm, tributeLlm);
    }
```

Add an `APPROVED`-artist helper next to `seedArtist(...)`:

```java
    private static Artist approvedArtist(String name) {
        return new Artist(name, ArtistSource.SIMILAR_EXPANSION, ArtistStatus.APPROVED, "x", "x");
    }
```

Add these two tests:

```java
    @Test
    @DisplayName("should save a tribute act for a SEED base with the TRIBUTE_EXPANSION source")
    void shouldSaveTributeForSeed() {
        when(artistRepository.findByStatusIn(any())).thenReturn(List.of(seedArtist("Iron Maiden")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(tributeLlm.findTributeBands("Iron Maiden", 5)).thenReturn(List.of("The Iron Maidens"));
        when(artistRepository.existsByNameIgnoreCase("The Iron Maidens")).thenReturn(false);

        expansionService.expandAll();

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(captor.capture());
        Artist saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("The Iron Maidens");
        assertThat(saved.getSource()).isEqualTo(ArtistSource.TRIBUTE_EXPANSION);
        assertThat(saved.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(saved.getDiscoveredVia()).isEqualTo("Iron Maiden");
        assertThat(saved.getNote()).isEqualTo("tribute/cover act for Iron Maiden");
    }

    @Test
    @DisplayName("should NOT run tribute expansion for an APPROVED (non-seed) base")
    void shouldSkipTributeForApproved() {
        when(artistRepository.findByStatusIn(any())).thenReturn(List.of(approvedArtist("Nickel Creek")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());

        expansionService.expandAll();

        verify(tributeLlm, never()).findTributeBands(any(), anyInt());
    }
```

Add the missing Mockito import at the top with the other static imports:

```java
import static org.mockito.ArgumentMatchers.anyInt;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests "com.robsartin.setlistscout.service.ExpansionServiceTest"`
Expected: FAIL — compilation error, `ExpansionService` has no 6-arg constructor and `ArtistSource.TRIBUTE_EXPANSION` does not exist.

- [ ] **Step 3: Write minimal implementation**

Edit `src/main/java/com/robsartin/setlistscout/domain/ArtistSource.java` — add the constant (note the comma after `SIMILAR_EXPANSION`):

```java
public enum ArtistSource {
    SEED_LIST,           // hand-entered starting list
    MEMBER_EXPANSION,    // found via MusicBrainz/Discogs lineup relationships
    SIMILAR_EXPANSION,   // found via Last.fm / LLM similarity
    TRIBUTE_EXPANSION    // found via LLM tribute/cover-band lookup (seed bands only)
}
```

Edit `src/main/java/com/robsartin/setlistscout/service/ExpansionService.java`:

Add the field:

```java
    private final SimilarArtistLlmService similarArtistLlm;
    private final TributeLlmService tributeLlm;
```

Replace the constructor:

```java
    public ExpansionService(ArtistRepository artistRepository,
                             MusicBrainzService musicBrainz,
                             DiscogsService discogs,
                             LastFmService lastFm,
                             SimilarArtistLlmService similarArtistLlm,
                             TributeLlmService tributeLlm) {
        this.artistRepository = artistRepository;
        this.musicBrainz = musicBrainz;
        this.discogs = discogs;
        this.lastFm = lastFm;
        this.similarArtistLlm = similarArtistLlm;
        this.tributeLlm = tributeLlm;
    }
```

Add the seed-only tribute branch in the `expandAll()` loop:

```java
        for (Artist base : baseArtists) {
            expandMemberRelations(base);
            expandSimilarArtists(base);
            if (base.getStatus() == ArtistStatus.SEED) {
                expandTributeBands(base);
            }
        }
```

Add the private method (place it after `expandSimilarArtists`):

```java
    private void expandTributeBands(Artist base) {
        for (String name : tributeLlm.findTributeBands(base.getName(), 5)) {
            saveIfNew(name, ArtistSource.TRIBUTE_EXPANSION, base.getName(),
                    "tribute/cover act for " + base.getName());
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --no-daemon test --tests "com.robsartin.setlistscout.service.ExpansionServiceTest"`
Expected: PASS — all existing tests plus the two new ones. (Existing tests are unaffected: their `SEED` base now also triggers `tributeLlm.findTributeBands`, which an unstubbed Mockito mock answers with an empty list.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/domain/ArtistSource.java \
        src/main/java/com/robsartin/setlistscout/service/ExpansionService.java \
        src/test/java/com/robsartin/setlistscout/service/ExpansionServiceTest.java
git commit -m "Wire seed-only tribute expansion into ExpansionService (#10)"
```

---

### Task 3: ADR-0017 + index update

Documents the LLM-only + seed-only decision and links it from the ADR index so `check_adrs.py` stays green. No code.

**Files:**
- Create: `docs/adr/0017-tribute-band-expansion-sources.md`
- Modify: `docs/adr/README.md` (add the index entry)

**Interfaces:** none (documentation).

- [ ] **Step 1: Write the ADR**

Create `docs/adr/0017-tribute-band-expansion-sources.md`:

```markdown
# 0017: Tribute/cover band expansion sources

Date: 2026-08-10
Status: Accepted

## Context

The seed list expands along member/lineup relations (ADR-0001) and taste-similar
artists (ADR-0002). A third dimension from the original vision — cover/tribute
bands (e.g. "The Iron Maidens" for Iron Maiden) — was never built. Tribute acts
are a distinct discovery problem: they do not appear in MusicBrainz lineup
relations and are not returned by Last.fm "similar artist" queries, so neither
existing source finds them.

## Decision

Discover tribute/cover bands with an LLM only, via a dedicated `TributeLlmService`
(a near-clone of the similar-artist LLM service). Run this expansion for `SEED`
artists only — not the full `SEED + APPROVED` set the other dimensions use.

Rejected alternative: name-pattern search against the show APIs (Ticketmaster /
Bandsintown for "<band> tribute", "tribute to <band>", etc.). It finds only
tributes that happen to be touring, misses creatively-named acts ("The Iron
Maidens" contains neither word), and blurs discovery into show search.

## Consequences

- Tribute acts flow through the pending-review gate (ADR-0004) like every other
  discovered artist; LLM false positives are absorbed there rather than polluting
  show results.
- Seed-only scope bounds LLM cost to the seed count and avoids proposing tributes
  for soloists or third-hop discoveries that realistically have none. It also
  sidesteps "tribute band of a tribute band" recursion once tributes are approved.
- To cover a non-seed band's tributes, promote it to a seed — a manual step, out
  of scope here.
```

- [ ] **Step 2: Add the index entry**

In `docs/adr/README.md`, under the `## Uncategorized` list, add after the `0016` entry:

```markdown
- [0017: Tribute/cover band expansion sources](0017-tribute-band-expansion-sources.md)
  Tribute acts don't appear in MusicBrainz lineup relations or Last.fm "similar artist" queries, so neither existing source finds them.
```

- [ ] **Step 3: Run the ADR check to verify it passes**

Run: `python3 scripts/check_adrs.py`
Expected: exit 0 — numbering contiguous (0001–0017), no unresolved template tokens, `0017` linked from the index.

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0017-tribute-band-expansion-sources.md docs/adr/README.md
git commit -m "Add ADR-0017 for tribute-band expansion sources (#10)"
```

---

### Task 4: Full gate, push, and PR

Runs the complete merge gate exactly as CI does, then puts the branch up for review. (Do not merge — stop at PR for review.)

**Files:** none (verification + integration).

**Interfaces:** none.

- [ ] **Step 1: Run the full build gate**

Run: `./gradlew --no-daemon build`
Expected: `BUILD SUCCESSFUL` — full compile + entire test suite green.

- [ ] **Step 2: Run the ADR compliance gate**

Run: `python3 scripts/check_adrs.py`
Expected: exit 0.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin 10-tribute-band-expansion
```

- [ ] **Step 4: Open the PR**

```bash
gh pr create --repo robsartin/setlist-scout --base main \
  --title "Add cover/tribute band expansion dimension" \
  --body "Closes #10. Adds LLM-only tribute/cover-band discovery (\`TributeLlmService\`), scoped to seed bands only, feeding the existing pending-review gate. New \`TRIBUTE_EXPANSION\` source; ADR-0017 records the decision. TDD throughout; full build + ADR gates green."
```

Expected: PR URL printed. Leave it open for Rob's review — do not merge.

---

## Self-Review

**Spec coverage:**
- LLM-only discovery via `TributeLlmService` → Task 1. ✓
- Seed-only scope → Task 2 (`if base.getStatus() == SEED`) + `shouldSkipTributeForApproved` test. ✓
- New `TRIBUTE_EXPANSION` source + save with source/note → Task 2 + `shouldSaveTributeForSeed` test. ✓
- ADR-0017 + index → Task 3. ✓
- Tests mirroring the similar-artist test, incl. a "no known tributes" case → Task 1 (`shouldReturnEmptyWhenNoneKnown`). ✓
- Full CI gate green before PR → Task 4. ✓
- Review gate / `/artists` unchanged, no repo/show-search change → no task needed (verified by design; `saveIfNew` + generic template already handle it). ✓

**Placeholder scan:** no TBD/TODO/"handle edge cases"/"similar to Task N"; every code step has literal code. ✓

**Type consistency:** `findTributeBands(String, int)` defined in Task 1, called with `(base.getName(), 5)` in Task 2 and stubbed `("Iron Maiden", 5)` in the Task 2 test. 6-arg `ExpansionService` constructor consistent between impl and test `setUp`. `TRIBUTE_EXPANSION` spelled identically in enum, service, and test. ✓
