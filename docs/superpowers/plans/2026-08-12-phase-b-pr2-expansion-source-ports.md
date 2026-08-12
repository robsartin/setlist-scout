# Phase B — PR2: expansion RelationSource ports & adapters (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Put the expansion module's five related-artist sources behind a `RelationSource` port with one query-only adapter each (mirroring PR1's `ShowSource`), so each source is a uniform, isolatable unit — behavior byte-for-byte unchanged.

**Architecture:** New `expansion.source` package: a `RelationSource` port (`String id(); List<String> related(String artistName)`) + five adapters that wrap the existing clients and bake their per-call limits. `ExpansionService` injects the five adapters and calls `.related(name)` in place of the direct client calls; its dimension logic (member = MusicBrainz ∪ Discogs; similar = Last.fm + LLM with the confirmed-by-both note; tribute = LLM, SEED-only), the name-plausibility guard, the owner-dedup, and the `saveIfNew` persistence are all unchanged. (Persistence stays direct in PR2; the `CandidateDiscovered` event switch is PR3.)

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Modulith 1.3.12, JUnit 5 + Mockito + AssertJ. Build: `JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem ./gradlew …`.

## Global Constraints

- **Behavior unchanged.** Same candidates discovered/persisted, same classification (`MEMBER_EXPANSION`/`SIMILAR_EXPANSION`/`TRIBUTE_EXPANSION`), same notes verbatim (incl. "similar to X (confirmed by Last.fm + LLM)" / "(single-source match)"), same SEED-only tribute rule, same name-guard + owner-dedup, same per-dimension failure isolation (`safely`).
- **`ModularityTests` verify() MUST stay green.** New `expansion.source.*` is internal to the `expansion` module; adapters may depend on `expansion` clients + the `shared` MusicBrainz client (both allowed) — no new cross-module edges, no cycles.
- **Adapters are query-only** — they wrap a single client read and return names; never persist.
- **Per-call limits move INTO the adapters** (Last.fm 8, SimilarLlm 8, Tribute 5) — the numbers leave `ExpansionService`.
- Keep-green each task: `compileJava compileTestJava` + affected tests. Docker up → full `./gradlew build` as CI-parity gate.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Branch `86-pr2-events-and-job-model` (already checked out; despite the name PR2 is ports-only). No worktree.

## Source signatures (verified)
- `shared/MusicBrainzService.findRelatedArtists(String) -> List<String>`
- `expansion/DiscogsService.findRelatedArtists(String) -> List<String>`
- `expansion/LastFmService.findSimilarArtists(String, int limit) -> List<String>`
- `expansion/SimilarArtistLlmService.findSimilarArtists(String, int count) -> List<String>`
- `expansion/TributeLlmService.findTributeBands(String, int count) -> List<String>`

Source ids (stable keys, used later in PR4's `expand_job.source`): `musicbrainz`, `discogs`, `lastfm`, `similar-llm`, `tribute-llm`.

---

### Task 1: `RelationSource` port + five adapters

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/expansion/source/RelationSource.java`
- Create: `.../expansion/source/MusicBrainzRelationSource.java`, `DiscogsRelationSource.java`, `LastFmSimilarSource.java`, `SimilarLlmSource.java`, `TributeLlmSource.java`
- Test: `src/test/java/com/robsartin/setlistscout/expansion/source/RelationSourceAdaptersTest.java`

**Interfaces:**
- Produces: `RelationSource { String id(); List<String> related(String artistName); }`; five `@Component` adapters with the ids above.

- [ ] **Step 1: Write the failing test** — `RelationSourceAdaptersTest`:

```java
package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.expansion.DiscogsService;
import com.robsartin.setlistscout.expansion.LastFmService;
import com.robsartin.setlistscout.expansion.SimilarArtistLlmService;
import com.robsartin.setlistscout.expansion.TributeLlmService;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationSourceAdaptersTest {

    @Test
    void musicBrainzAdapterDelegatesAndIsIdMusicbrainz() {
        MusicBrainzService mb = mock(MusicBrainzService.class);
        when(mb.findRelatedArtists("Dawes")).thenReturn(List.of("Taylor Goldsmith"));
        MusicBrainzRelationSource s = new MusicBrainzRelationSource(mb);
        assertThat(s.id()).isEqualTo("musicbrainz");
        assertThat(s.related("Dawes")).containsExactly("Taylor Goldsmith");
    }

    @Test
    void discogsAdapterDelegatesAndIsIdDiscogs() {
        DiscogsService d = mock(DiscogsService.class);
        when(d.findRelatedArtists("Dawes")).thenReturn(List.of("Middle Brother"));
        DiscogsRelationSource s = new DiscogsRelationSource(d);
        assertThat(s.id()).isEqualTo("discogs");
        assertThat(s.related("Dawes")).containsExactly("Middle Brother");
    }

    @Test
    void lastFmAdapterDelegatesWithLimit8AndIsIdLastfm() {
        LastFmService lf = mock(LastFmService.class);
        when(lf.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        LastFmSimilarSource s = new LastFmSimilarSource(lf);
        assertThat(s.id()).isEqualTo("lastfm");
        assertThat(s.related("Dawes")).containsExactly("Nickel Creek");
    }

    @Test
    void similarLlmAdapterDelegatesWithLimit8AndIsIdSimilarLlm() {
        SimilarArtistLlmService llm = mock(SimilarArtistLlmService.class);
        when(llm.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        SimilarLlmSource s = new SimilarLlmSource(llm);
        assertThat(s.id()).isEqualTo("similar-llm");
        assertThat(s.related("Dawes")).containsExactly("Nickel Creek");
    }

    @Test
    void tributeLlmAdapterDelegatesWithLimit5AndIsIdTributeLlm() {
        TributeLlmService t = mock(TributeLlmService.class);
        when(t.findTributeBands("Iron Maiden", 5)).thenReturn(List.of("The Iron Maidens"));
        TributeLlmSource s = new TributeLlmSource(t);
        assertThat(s.id()).isEqualTo("tribute-llm");
        assertThat(s.related("Iron Maiden")).containsExactly("The Iron Maidens");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=… ./gradlew test --tests "com.robsartin.setlistscout.expansion.source.RelationSourceAdaptersTest" --console=plain` → FAIL (classes missing).

- [ ] **Step 3: Write minimal implementation**

`RelationSource.java`:
```java
package com.robsartin.setlistscout.expansion.source;

import java.util.List;

/** A single related-artist source (MusicBrainz, Discogs, Last.fm, LLMs). Query-only: never writes. */
public interface RelationSource {
    /** Stable source key used in logs and (Phase B PR4) the expand_job.source column. */
    String id();

    /** Candidate related-artist names for the given base artist. */
    List<String> related(String artistName);
}
```

`MusicBrainzRelationSource.java`:
```java
package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Member/lineup relations from MusicBrainz behind the {@link RelationSource} port. */
@Component
public class MusicBrainzRelationSource implements RelationSource {

    private final MusicBrainzService musicBrainz;

    public MusicBrainzRelationSource(MusicBrainzService musicBrainz) {
        this.musicBrainz = musicBrainz;
    }

    @Override
    public String id() {
        return "musicbrainz";
    }

    @Override
    public List<String> related(String artistName) {
        return musicBrainz.findRelatedArtists(artistName);
    }
}
```

`DiscogsRelationSource.java` — same shape, dep `DiscogsService discogs`, `id()` `"discogs"`, `related` returns `discogs.findRelatedArtists(artistName)`.

`LastFmSimilarSource.java` — dep `LastFmService lastFm`, `id()` `"lastfm"`, `related` returns `lastFm.findSimilarArtists(artistName, 8)`.

`SimilarLlmSource.java` — dep `SimilarArtistLlmService similarArtistLlm`, `id()` `"similar-llm"`, `related` returns `similarArtistLlm.findSimilarArtists(artistName, 8)`.

`TributeLlmSource.java` — dep `TributeLlmService tributeLlm`, `id()` `"tribute-llm"`, `related` returns `tributeLlm.findTributeBands(artistName, 5)`.

(All five are `@Component`, package `com.robsartin.setlistscout.expansion.source`, one client dep each, query-only.)

- [ ] **Step 4: Run test to verify it passes** — PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/expansion/source/ src/test/java/com/robsartin/setlistscout/expansion/source/RelationSourceAdaptersTest.java
git commit -m "PR2: add RelationSource port + five expansion source adapters

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Rewire `ExpansionService` to use the adapters

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/expansion/ExpansionService.java`
- Modify: `src/test/java/com/robsartin/setlistscout/expansion/ExpansionServiceTest.java`

**Interfaces:**
- Consumes: the five `RelationSource` adapters (Task 1), injected by their concrete types.
- Produces: unchanged public `void expandAll(String owner)`.

**What changes:** the constructor drops `MusicBrainzService`, `DiscogsService`, `LastFmService`, `SimilarArtistLlmService`, `TributeLlmService` and gains the five adapters (`MusicBrainzRelationSource`, `DiscogsRelationSource`, `LastFmSimilarSource`, `SimilarLlmSource`, `TributeLlmSource`); keeps `ArtistRepository`. The three `expand…` methods swap each direct client call for the matching adapter's `related(name)` (the `8`/`5` limits are now inside the adapters). `expandAll`, `safely`, `saveIfNew`, `looksLikeArtistName`, and all notes/classifications are unchanged.

- [ ] **Step 1: Rewrite the failing test** — update `ExpansionServiceTest` to mock the five adapters instead of the five clients. Apply this mechanical translation to EVERY test method, preserving each assertion exactly:
  - Replace the five `@Mock` client fields with `@Mock MusicBrainzRelationSource musicBrainzSource; @Mock DiscogsRelationSource discogsSource; @Mock LastFmSimilarSource lastFmSource; @Mock SimilarLlmSource similarLlmSource; @Mock TributeLlmSource tributeSource;`
  - New `@BeforeEach`: `expansionService = new ExpansionService(artistRepository, musicBrainzSource, discogsSource, lastFmSource, similarLlmSource, tributeSource);`
  - Translate stubs/verifies: `musicBrainz.findRelatedArtists(x)` → `musicBrainzSource.related(x)`; `discogs.findRelatedArtists(x)` → `discogsSource.related(x)`; `lastFm.findSimilarArtists(x, 8)` → `lastFmSource.related(x)`; `similarArtistLlm.findSimilarArtists(x, 8)` → `similarLlmSource.related(x)`; `tributeLlm.findTributeBands(x, 5)` → `tributeSource.related(x)`. Drop the `eq(8)`/`anyInt()` limit args (adapters bake the limit) — e.g. `when(lastFm.findSimilarArtists(any(), eq(8)))` becomes `when(lastFmSource.related(any()))`, and the tribute-skip verify `verify(tributeLlm, never()).findTributeBands(any(), anyInt())` becomes `verify(tributeSource, never()).related(any())`.
  - All `artistRepository` stubs/verifies and every `assertThat(...)` on the saved `Artist` (names, sources, notes, counts, dimension-isolation) stay identical.

Fully-worked example — `shouldNoteConfirmedByBothSources` becomes:
```java
    @Test
    @DisplayName("should note a similar artist found by both sources as confirmed")
    void shouldNoteConfirmedByBothSources() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related("Dawes")).thenReturn(List.of("Nickel Creek"));
        when(similarLlmSource.related("Dawes")).thenReturn(List.of("Nickel Creek"));
        when(artistRepository.existsByOwnerAndNameIgnoreCase(any(), any())).thenReturn(false);

        expansionService.expandAll(OWNER);

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(captor.capture());
        Artist saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(ArtistSource.SIMILAR_EXPANSION);
        assertThat(saved.getNote()).isEqualTo("similar to Dawes (confirmed by Last.fm + LLM)");
    }
```
Remove now-unused imports (`anyInt`, `eq` if no longer referenced). Keep `@ExtendWith(MockitoExtension.class)`. (With `MockitoExtension` in strict-stubs mode, only stub what each test uses — the translation preserves the existing per-test stub sets, so this stays satisfied.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=… ./gradlew test --tests "com.robsartin.setlistscout.expansion.ExpansionServiceTest" --console=plain`
Expected: FAIL — constructor signature mismatch (old `(ArtistRepository, MusicBrainzService, DiscogsService, LastFmService, SimilarArtistLlmService, TributeLlmService)` vs new `(ArtistRepository, MusicBrainzRelationSource, DiscogsRelationSource, LastFmSimilarSource, SimilarLlmSource, TributeLlmSource)`).

- [ ] **Step 3: Rewrite `ExpansionService`** — swap the fields/constructor and the client calls in the three `expand…` methods. Concretely:

Fields/constructor:
```java
    private final ArtistRepository artistRepository;
    private final MusicBrainzRelationSource musicBrainzSource;
    private final DiscogsRelationSource discogsSource;
    private final LastFmSimilarSource lastFmSource;
    private final SimilarLlmSource similarLlmSource;
    private final TributeLlmSource tributeSource;

    public ExpansionService(ArtistRepository artistRepository,
                             MusicBrainzRelationSource musicBrainzSource,
                             DiscogsRelationSource discogsSource,
                             LastFmSimilarSource lastFmSource,
                             SimilarLlmSource similarLlmSource,
                             TributeLlmSource tributeSource) {
        this.artistRepository = artistRepository;
        this.musicBrainzSource = musicBrainzSource;
        this.discogsSource = discogsSource;
        this.lastFmSource = lastFmSource;
        this.similarLlmSource = similarLlmSource;
        this.tributeSource = tributeSource;
    }
```
Add `import com.robsartin.setlistscout.expansion.source.*;` (or the five explicit imports). Remove the now-unused imports of `MusicBrainzService`, `DiscogsService`, `LastFmService`, `SimilarArtistLlmService`, `TributeLlmService`.

In the three methods, swap only the source calls (everything else — the `Set<String> found` union, the confirmed-by-both logic, the notes, `saveIfNew`, the SEED-only guard — stays byte-for-byte):
- `expandMemberRelations`: `found.addAll(musicBrainzSource.related(base.getName())); found.addAll(discogsSource.related(base.getName()));`
- `expandSimilarArtists`: `Set<String> lastFmResults = new HashSet<>(lastFmSource.related(base.getName())); Set<String> llmResults = new HashSet<>(similarLlmSource.related(base.getName()));`
- `expandTributeBands`: `for (String name : tributeSource.related(base.getName())) { … }`

- [ ] **Step 4: Run the affected tests to verify they pass**

Run: `JAVA_HOME=… ./gradlew test --tests "com.robsartin.setlistscout.expansion.*" --console=plain`
Expected: PASS (the rewritten `ExpansionServiceTest` + the Task-1 adapter test).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/expansion/ExpansionService.java src/test/java/com/robsartin/setlistscout/expansion/ExpansionServiceTest.java
git commit -m "PR2: ExpansionService queries via RelationSource adapters (limits baked into adapters)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Full-suite + verify() gate

**Files:** none (verification only).

- [ ] **Step 1: Run verify() + the full suite (CI parity, Docker up)**

Run:
```bash
export JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem
./gradlew test --tests "com.robsartin.setlistscout.ModularityTests" --console=plain
./gradlew --no-daemon clean build --console=plain
```
Expected: `ModularityTests` green; `BUILD SUCCESSFUL`, all tests pass. `expansion.source` adds no cross-module edge beyond what `expansion`/`shared` already had.

- [ ] **Step 2: If red, fix and re-run.** Likely-only issue: a stray old-constructor call or unused import — `grep -rn "new ExpansionService(" src` should show only the updated test; `grep -rn "findRelatedArtists\|findSimilarArtists\|findTributeBands" src/main/java/com/robsartin/setlistscout/expansion/ExpansionService.java` should return nothing (all calls now go through adapters).

- [ ] **Step 3: No commit** (gate only).

---

## Self-Review

- **Spec coverage:** PR2 (tightened) = "Introduce `RelationSource` port + `expansion.source.*` adapters; the orchestrator queries via adapters." ✅ Tasks 1–2. (`CandidateDiscovered` event + `serialized_event` widening moved to PR3 — see handoff note; not here.)
- **Placeholder scan:** none — adapters fully specified; the test rewrite is a mechanical translation with an explicit rule + a fully-worked example + concrete before/after for the tricky similar-source cases.
- **Type consistency:** `RelationSource.related(String)->List<String>` used identically across Tasks 1–2; adapter type names + ids (`musicbrainz`/`discogs`/`lastfm`/`similar-llm`/`tribute-llm`) match; new `ExpansionService` constructor `(ArtistRepository, MusicBrainzRelationSource, DiscogsRelationSource, LastFmSimilarSource, SimilarLlmSource, TributeLlmSource)` matches the Task 2 `@BeforeEach`.
- **Behavior:** member-union / similar-confirmed-by-both / tribute-SEED-only, name-guard, owner-dedup, `safely` isolation, and all note strings preserved; only the per-call limits relocate (into adapters) — not observable.
