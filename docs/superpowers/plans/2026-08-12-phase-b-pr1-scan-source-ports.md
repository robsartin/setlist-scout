# Phase B — PR1: scan source ports & adapters (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the scan module's three show sources (Ticketmaster, Bandsintown, band-site) behind a `ShowSource` port with one query-only adapter each, and have `ShowAggregationService` iterate the injected `List<ShowSource>` — behavior byte-for-byte unchanged.

**Architecture:** Introduce `scan.source.ShowSource` (port) + `scan.source.ScanQuery` (the per-artist query context) and adapters `TicketmasterShowSource`/`BandsintownShowSource`/`BandSiteShowSource` in `scan.source.*`. Adapters are **query-only** (no writes). The one write in today's flow — caching a MusicBrainz-discovered official-site URL onto the `Artist` — stays in the orchestrator (`ShowAggregationService.resolveSiteUrl`), so adapters stay pure. Source order (Ticketmaster → Bandsintown → band-site) is pinned with `@Order` because show de-dup is first-writer-wins.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Modulith 1.3.12, JUnit 5 + Mockito. Build: `JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem ./gradlew …`.

## Global Constraints

- **Behavior unchanged.** Same shows found/persisted, same de-dup, same blank-name skip (#49), same band-site URL discovery+caching + city-match filter (#22/#28), same per-artist logging intent. This is a pure structural refactor.
- **`ModularityTests.verifiesModularStructure()` (verify()) MUST stay green.** New package `scan.source.*` is internal to the `scan` module; it may depend on `scan` types (`Show`, the three source services) and `catalog`/`settings`/`shared` exposed types — no new cross-module edges, no cycles.
- **Keep-green at every task:** `compileJava compileTestJava` + the affected tests green after each commit. Docker is up locally → run the full `./gradlew test` (108/108) as the CI-parity gate; if Docker is down the only acceptable failures are the 5 Testcontainers tests (ApplicationContextSmokeTest, 3 migration tests, web/ArtistPageRenderTest → note: that test now lives at shared/observability path after Phase A; the Testcontainers ones are ApplicationContextSmokeTest + the 3 migration tests + ArtistPageRenderTest).
- **Adapters are query-only** — never call `artistRepository.save`, `showRepository.save`, or any mutator.
- Commit trailer on every commit: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Branch: `86-pr1-source-ports-and-adapters` (already checked out). Do not create a worktree.

## File Structure

- Create `src/main/java/com/robsartin/setlistscout/scan/source/ShowSource.java` — the port.
- Create `src/main/java/com/robsartin/setlistscout/scan/source/ScanQuery.java` — immutable query context.
- Create `.../scan/source/TicketmasterShowSource.java`, `BandsintownShowSource.java`, `BandSiteShowSource.java` — adapters (`@Component` + `@Order`).
- Modify `src/main/java/com/robsartin/setlistscout/scan/ShowAggregationService.java` — inject `List<ShowSource>`, add `resolveSiteUrl`, drop direct source deps + `scrapeBandSite`.
- Create tests `.../scan/source/TicketmasterShowSourceTest.java`, `BandsintownShowSourceTest.java`, `BandSiteShowSourceTest.java`.
- Modify `src/test/java/com/robsartin/setlistscout/scan/ShowAggregationServiceTest.java` — construct with mocked `List<ShowSource>`.

Reference — verbatim current call sites in `ShowAggregationService.scanForShows` (source of truth for the mappings):
- `ticketmaster.searchShows(artist.getName(), settings.getPostalCode(), settings.getRadiusMiles(), start, end)`
- `bandsintown.searchShows(artist.getName(), settings.getLatitude(), settings.getLongitude(), settings.getRadiusMiles(), start, end)`
- band-site: resolve URL (`artist.getOfficialSiteUrl()`; if null → `musicBrainz.findOfficialHomepage(name)`, and cache back), then `bandSiteScraper.scrapeShows(name, url, start, end)`, then filter `s.getVenueCity().equalsIgnoreCase(settings.getCity())` (no filter if city null; empty list if url null).

Signatures (verified): `TicketmasterService.searchShows(String, String postalCode, int radiusMiles, LocalDateTime, LocalDateTime) -> List<Show>`; `BandsintownService.searchShows(String, Double lat, Double lon, int radiusMiles, LocalDateTime, LocalDateTime) -> List<Show>`; `BandSiteScraperService.scrapeShows(String, String url, LocalDateTime, LocalDateTime) -> List<Show>`; `SearchSettings`: `getPostalCode()->String`, `getLatitude()/getLongitude()->Double`, `getCity()->String`, `getRadiusMiles()->int`.

---

### Task 1: `ShowSource` port + `ScanQuery` + `TicketmasterShowSource`

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/source/ShowSource.java`
- Create: `src/main/java/com/robsartin/setlistscout/scan/source/ScanQuery.java`
- Create: `src/main/java/com/robsartin/setlistscout/scan/source/TicketmasterShowSource.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/source/TicketmasterShowSourceTest.java`

**Interfaces:**
- Produces: `ShowSource { String id(); List<Show> search(ScanQuery query); }`;
  `ScanQuery(String artistName, String officialSiteUrl, String postalCode, Double latitude, Double longitude, int radiusMiles, String city, java.time.LocalDateTime windowStart, java.time.LocalDateTime windowEnd)` (a `record`).

- [ ] **Step 1: Write the failing test** — `TicketmasterShowSourceTest`:

```java
package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.TicketmasterService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketmasterShowSourceTest {
    private final TicketmasterService ticketmaster = mock(TicketmasterService.class);
    private final TicketmasterShowSource source = new TicketmasterShowSource(ticketmaster);

    @Test
    void idIsTicketmaster() {
        assertThat(source.id()).isEqualTo("ticketmaster");
    }

    @Test
    void delegatesToTicketmasterWithMappedArgs() {
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(6);
        List<Show> expected = List.of(new Show());
        when(ticketmaster.searchShows(eq("ZZ Top"), eq("78701"), eq(50), eq(start), eq(end)))
                .thenReturn(expected);
        ScanQuery q = new ScanQuery("ZZ Top", null, "78701", 30.26, -97.74, 50, "Austin", start, end);

        assertThat(source.search(q)).isSameAs(expected);
    }
}
```

Note: if `new Show()` needs a no-arg constructor it already has one (JPA entity). If `Show`'s only constructor requires args, use `mock(Show.class)` instead — check `scan/Show.java` first and adjust.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem ./gradlew test --tests "com.robsartin.setlistscout.scan.source.TicketmasterShowSourceTest" --console=plain`
Expected: FAIL — `ShowSource`/`ScanQuery`/`TicketmasterShowSource` do not compile/exist.

- [ ] **Step 3: Write minimal implementation**

`ShowSource.java`:
```java
package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.Show;
import java.util.List;

/** A single show-search source (Ticketmaster, Bandsintown, band-site …). Query-only: never writes. */
public interface ShowSource {
    /** Stable source key used in logs and (Phase B) the scan_job.source column. */
    String id();

    List<Show> search(ScanQuery query);
}
```

`ScanQuery.java`:
```java
package com.robsartin.setlistscout.scan.source;

import java.time.LocalDateTime;

/**
 * Everything a show source might need for one artist at the owner's location/window. The orchestrator
 * resolves {@code officialSiteUrl} (and caches a newly discovered one) before building this; sources
 * read only the fields they need and never write.
 */
public record ScanQuery(
        String artistName,
        String officialSiteUrl,
        String postalCode,
        Double latitude,
        Double longitude,
        int radiusMiles,
        String city,
        LocalDateTime windowStart,
        LocalDateTime windowEnd) {
}
```

`TicketmasterShowSource.java`:
```java
package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.TicketmasterService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Ticketmaster show search behind the {@link ShowSource} port. */
@Component
@Order(1)
public class TicketmasterShowSource implements ShowSource {

    private final TicketmasterService ticketmaster;

    public TicketmasterShowSource(TicketmasterService ticketmaster) {
        this.ticketmaster = ticketmaster;
    }

    @Override
    public String id() {
        return "ticketmaster";
    }

    @Override
    public List<Show> search(ScanQuery q) {
        return ticketmaster.searchShows(q.artistName(), q.postalCode(), q.radiusMiles(),
                q.windowStart(), q.windowEnd());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=… ./gradlew test --tests "com.robsartin.setlistscout.scan.source.TicketmasterShowSourceTest" --console=plain`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/source/ src/test/java/com/robsartin/setlistscout/scan/source/TicketmasterShowSourceTest.java
git commit -m "PR1: add ShowSource port + ScanQuery + TicketmasterShowSource adapter

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `BandsintownShowSource`

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/source/BandsintownShowSource.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/source/BandsintownShowSourceTest.java`

**Interfaces:**
- Consumes: `ShowSource`, `ScanQuery` (Task 1).
- Produces: `BandsintownShowSource` (`id()` == `"bandsintown"`, `@Order(2)`).

- [ ] **Step 1: Write the failing test**

```java
package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandsintownService;
import com.robsartin.setlistscout.scan.Show;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BandsintownShowSourceTest {
    private final BandsintownService bandsintown = mock(BandsintownService.class);
    private final BandsintownShowSource source = new BandsintownShowSource(bandsintown);

    @Test
    void idIsBandsintown() {
        assertThat(source.id()).isEqualTo("bandsintown");
    }

    @Test
    void delegatesWithLatLongRadiusWindow() {
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(6);
        List<Show> expected = List.of(new Show());
        when(bandsintown.searchShows(eq("ZZ Top"), eq(30.26), eq(-97.74), eq(50), eq(start), eq(end)))
                .thenReturn(expected);
        ScanQuery q = new ScanQuery("ZZ Top", null, "78701", 30.26, -97.74, 50, "Austin", start, end);

        assertThat(source.search(q)).isSameAs(expected);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=… ./gradlew test --tests "com.robsartin.setlistscout.scan.source.BandsintownShowSourceTest" --console=plain` → FAIL (class missing).

- [ ] **Step 3: Write minimal implementation**

```java
package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandsintownService;
import com.robsartin.setlistscout.scan.Show;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Bandsintown show search behind the {@link ShowSource} port (distance-filtered by the geocoded lat/long). */
@Component
@Order(2)
public class BandsintownShowSource implements ShowSource {

    private final BandsintownService bandsintown;

    public BandsintownShowSource(BandsintownService bandsintown) {
        this.bandsintown = bandsintown;
    }

    @Override
    public String id() {
        return "bandsintown";
    }

    @Override
    public List<Show> search(ScanQuery q) {
        return bandsintown.searchShows(q.artistName(), q.latitude(), q.longitude(), q.radiusMiles(),
                q.windowStart(), q.windowEnd());
    }
}
```

- [ ] **Step 4: Run test to verify it passes** — PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/source/BandsintownShowSource.java src/test/java/com/robsartin/setlistscout/scan/source/BandsintownShowSourceTest.java
git commit -m "PR1: add BandsintownShowSource adapter

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `BandSiteShowSource` (URL-gated + city filter, query-only)

**Files:**
- Create: `src/main/java/com/robsartin/setlistscout/scan/source/BandSiteShowSource.java`
- Test: `src/test/java/com/robsartin/setlistscout/scan/source/BandSiteShowSourceTest.java`

**Interfaces:**
- Consumes: `ShowSource`, `ScanQuery` (Task 1); `BandSiteScraperService.scrapeShows(String,String,LocalDateTime,LocalDateTime)`.
- Produces: `BandSiteShowSource` (`id()` == `"band-site"`, `@Order(3)`).

Behavior to preserve exactly (from `scrapeBandSite`): if `officialSiteUrl` is null → return `List.of()` (no scrape); else scrape; then if `city` is non-null, keep only shows whose `getVenueCity()` equals the city ignoring case; if `city` is null, return all scraped. **No URL discovery, no caching here** — the orchestrator supplies `officialSiteUrl`.

- [ ] **Step 1: Write the failing test**

```java
package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandSiteScraperService;
import com.robsartin.setlistscout.scan.Show;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BandSiteShowSourceTest {
    private final BandSiteScraperService scraper = mock(BandSiteScraperService.class);
    private final BandSiteShowSource source = new BandSiteShowSource(scraper);
    private final LocalDateTime start = LocalDateTime.now();
    private final LocalDateTime end = start.plusMonths(6);

    private Show showInCity(String city) {
        Show s = mock(Show.class);
        when(s.getVenueCity()).thenReturn(city);
        return s;
    }

    @Test
    void idIsBandSite() {
        assertThat(source.id()).isEqualTo("band-site");
    }

    @Test
    void nullUrlSkipsScrapeAndReturnsEmpty() {
        ScanQuery q = new ScanQuery("ZZ Top", null, "78701", 30.26, -97.74, 50, "Austin", start, end);
        assertThat(source.search(q)).isEmpty();
        verify(scraper, never()).scrapeShows(any(), any(), any(), any());
    }

    @Test
    void filtersScrapedShowsToCityWhenCityPresent() {
        Show austin = showInCity("Austin");
        Show dallas = showInCity("Dallas");
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end))
                .thenReturn(List.of(austin, dallas));
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", 30.26, -97.74, 50, "Austin", start, end);

        assertThat(source.search(q)).containsExactly(austin);
    }

    @Test
    void nullCityReturnsAllScraped() {
        Show a = showInCity("Austin");
        Show b = showInCity("Dallas");
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end)).thenReturn(List.of(a, b));
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", 30.26, -97.74, 50, null, start, end);

        assertThat(source.search(q)).containsExactly(a, b);
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — FAIL (class missing).

- [ ] **Step 3: Write minimal implementation**

```java
package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandSiteScraperService;
import com.robsartin.setlistscout.scan.Show;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The artist's official site (scraped for tour dates) behind the {@link ShowSource} port. Query-only:
 * the orchestrator resolves + caches the site URL and passes it in {@link ScanQuery#officialSiteUrl()}.
 * v1 filters scraped shows by a loose city-name match to the owner's location (precise per-show distance
 * filtering is deferred -- see #28).
 */
@Component
@Order(3)
public class BandSiteShowSource implements ShowSource {

    private final BandSiteScraperService scraper;

    public BandSiteShowSource(BandSiteScraperService scraper) {
        this.scraper = scraper;
    }

    @Override
    public String id() {
        return "band-site";
    }

    @Override
    public List<Show> search(ScanQuery q) {
        if (q.officialSiteUrl() == null) {
            return List.of();
        }
        List<Show> shows = scraper.scrapeShows(q.artistName(), q.officialSiteUrl(),
                q.windowStart(), q.windowEnd());
        if (q.city() == null) {
            return shows;
        }
        return shows.stream()
                .filter(s -> s.getVenueCity() != null && s.getVenueCity().equalsIgnoreCase(q.city()))
                .toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes** — PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/source/BandSiteShowSource.java src/test/java/com/robsartin/setlistscout/scan/source/BandSiteShowSourceTest.java
git commit -m "PR1: add BandSiteShowSource adapter (query-only; URL supplied by orchestrator)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Rewire `ShowAggregationService` to iterate `List<ShowSource>`

**Files:**
- Modify: `src/main/java/com/robsartin/setlistscout/scan/ShowAggregationService.java`
- Modify: `src/test/java/com/robsartin/setlistscout/scan/ShowAggregationServiceTest.java`

**Interfaces:**
- Consumes: `List<ShowSource>` (Spring injects all `@Component` sources in `@Order` — Ticketmaster(1), Bandsintown(2), band-site(3)); `MusicBrainzService.findOfficialHomepage(String)->Optional<String>`; `ArtistRepository`, `ShowRepository`, `SearchSettingsRepository` (unchanged).
- Produces: unchanged public method `void scanForShows(String owner)`.

**What changes:** the constructor drops `TicketmasterService`, `BandsintownService`, `BandSiteScraperService` and gains `List<ShowSource> showSources`; keeps `artistRepository`, `showRepository`, `settingsRepository`, `musicBrainz`. The per-artist body resolves the site URL once (the sole write stays here), builds a `ScanQuery`, then loops the sources. `persistNew` is unchanged. `scrapeBandSite` is deleted (its URL logic → `resolveSiteUrl`, its scrape+filter → `BandSiteShowSource`).

- [ ] **Step 1: Rewrite the failing test** — replace `ShowAggregationServiceTest` with a version that constructs the service from mocked `ShowSource`s. It must still assert the blank-name skip (#49) and add a URL-caching assertion:

```java
package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.scan.source.ScanQuery;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SearchSettingsRepository;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShowAggregationServiceTest {

    private static final String OWNER = "rob@example.com";

    private ArtistRepository artistRepository;
    private ShowRepository showRepository;
    private SearchSettingsRepository settingsRepository;
    private MusicBrainzService musicBrainz;
    private ShowSource showSource;
    private ShowAggregationService aggregation;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        showRepository = mock(ShowRepository.class);
        settingsRepository = mock(SearchSettingsRepository.class);
        musicBrainz = mock(MusicBrainzService.class);
        showSource = mock(ShowSource.class);
        when(showSource.search(any())).thenReturn(List.of());
        aggregation = new ShowAggregationService(artistRepository, showRepository, settingsRepository,
                musicBrainz, List.of(showSource));

        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings.setPostalCode("78701");
        settings.setLatitude(30.2672);
        settings.setLongitude(-97.7431);
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));
        when(musicBrainz.findOfficialHomepage(any())).thenReturn(Optional.empty());
    }

    private static Artist seed(String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(OWNER);
        return artist;
    }

    @Test
    @DisplayName("a blank-named active artist is skipped -- source is never queried for it")
    void skipsBlankNamedArtist() {
        when(artistRepository.findByOwnerAndStatusIn(eq(OWNER), any()))
                .thenReturn(List.of(seed("   "), seed("ZZ Top")));

        aggregation.scanForShows(OWNER);

        verify(showSource).search(argThat(q -> q.artistName().equals("ZZ Top")));
        verify(showSource, never())
                .search(argThat(q -> q.artistName() == null || q.artistName().isBlank()));
    }

    @Test
    @DisplayName("a discovered official-site URL is cached back onto the artist (the one write)")
    void cachesDiscoveredSiteUrl() {
        Artist zz = seed("ZZ Top");
        when(artistRepository.findByOwnerAndStatusIn(eq(OWNER), any())).thenReturn(List.of(zz));
        when(musicBrainz.findOfficialHomepage("ZZ Top")).thenReturn(Optional.of("https://zztop.com"));

        aggregation.scanForShows(OWNER);

        verify(artistRepository).save(argThat(a -> "https://zztop.com".equals(a.getOfficialSiteUrl())));
        verify(showSource).search(argThat(q -> "https://zztop.com".equals(q.officialSiteUrl())));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=… ./gradlew test --tests "com.robsartin.setlistscout.scan.ShowAggregationServiceTest" --console=plain`
Expected: FAIL — the current constructor signature `(…, TicketmasterService, BandsintownService, MusicBrainzService, BandSiteScraperService)` doesn't match `(…, MusicBrainzService, List<ShowSource>)`.

- [ ] **Step 3: Rewrite `ShowAggregationService`**

Replace the fields/constructor and the per-artist loop; delete `scrapeBandSite`; add `resolveSiteUrl`. Keep imports for `Artist`, `ArtistStatus`, `SearchSettings`, `MusicBrainzService`, `Show`, and add `import com.robsartin.setlistscout.scan.source.ScanQuery; import com.robsartin.setlistscout.scan.source.ShowSource;`. `persistNew` stays byte-for-byte.

```java
    private final ArtistRepository artistRepository;
    private final ShowRepository showRepository;
    private final SearchSettingsRepository settingsRepository;
    private final MusicBrainzService musicBrainz;
    private final List<ShowSource> showSources;

    public ShowAggregationService(ArtistRepository artistRepository,
                                   ShowRepository showRepository,
                                   SearchSettingsRepository settingsRepository,
                                   MusicBrainzService musicBrainz,
                                   List<ShowSource> showSources) {
        this.artistRepository = artistRepository;
        this.showRepository = showRepository;
        this.settingsRepository = settingsRepository;
        this.musicBrainz = musicBrainz;
        this.showSources = showSources;
    }

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
            // Defense in depth (issue #49): a blank name searches Ticketmaster with keyword="",
            // which returns every local event. Never let a bad row trigger that, whatever its source.
            if (artist.getName() == null || artist.getName().isBlank()) continue;
            searched++;

            ScanQuery query = new ScanQuery(artist.getName(), resolveSiteUrl(artist),
                    settings.getPostalCode(), settings.getLatitude(), settings.getLongitude(),
                    settings.getRadiusMiles(), settings.getCity(), start, end);

            for (ShowSource source : showSources) {
                List<Show> shows = source.search(query);
                found += shows.size();
                saved += persistNew(owner, shows);
                log.atDebug()
                        .addKeyValue("artist", artist.getName())
                        .addKeyValue("source", source.id())
                        .addKeyValue("count", shows.size())
                        .log("artist source scanned");
            }
        }

        log.atInfo()
                .addKeyValue("artistsSearched", searched)
                .addKeyValue("showsFound", found)
                .addKeyValue("showsSaved", saved)
                .addKeyValue("durationMs", (System.nanoTime() - startNanos) / 1_000_000)
                .log("scan finished");
    }

    /**
     * The artist's official-site URL for band-site scraping (#22): the cached value, or a MusicBrainz
     * "official homepage" lookup on first use, cached back onto the artist. This is the one write in the
     * scan flow -- the show sources themselves are query-only.
     */
    private String resolveSiteUrl(Artist artist) {
        String url = artist.getOfficialSiteUrl();
        if (url == null) {
            url = musicBrainz.findOfficialHomepage(artist.getName()).orElse(null);
            if (url != null) {
                artist.setOfficialSiteUrl(url);
                artistRepository.save(artist);
            }
        }
        return url;
    }
```

Keep the existing `persistNew` method unchanged. Delete the old `scrapeBandSite` method entirely.

- [ ] **Step 4: Run the affected tests to verify they pass**

Run: `JAVA_HOME=… ./gradlew test --tests "com.robsartin.setlistscout.scan.ShowAggregationServiceTest" --tests "com.robsartin.setlistscout.scan.source.*" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/robsartin/setlistscout/scan/ShowAggregationService.java src/test/java/com/robsartin/setlistscout/scan/ShowAggregationServiceTest.java
git commit -m "PR1: iterate List<ShowSource> in ShowAggregationService; URL caching stays in orchestrator

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Full-suite + verify() green gate

**Files:** none (verification only).

- [ ] **Step 1: Run verify() + the full suite (CI parity, Docker up)**

Run:
```bash
export JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem
./gradlew test --tests "com.robsartin.setlistscout.ModularityTests" --console=plain
./gradlew --no-daemon clean build --console=plain
```
Expected: `ModularityTests` green; `BUILD SUCCESSFUL`, all tests pass (Docker up). The new `scan.source` package introduces no cross-module edges beyond what `scan` already had.

- [ ] **Step 2: If anything is red, fix and re-run** (do not proceed until green). Likely-only issue: a stray reference to the removed `scrapeBandSite`/old constructor elsewhere — `grep -rn "scrapeBandSite\|new ShowAggregationService(" src` should return only the updated sites.

- [ ] **Step 3: No commit** (Task 4 already committed the code; this task is the gate).

---

## Self-Review

- **Spec coverage:** PR1 per the spec's delivery-plan = "Introduce `ShowSource` port + `scan.source.*` adapters; scan orchestrator iterates injected `List<ShowSource>`." ✅ Tasks 1–4. (Expansion `RelationSource` ports + `serialized_event` widening are intentionally **moved to PR2** — see the handoff note; not in this plan.)
- **Placeholder scan:** none — every step has concrete code/commands.
- **Type consistency:** `ShowSource.search(ScanQuery)` / `ScanQuery(...)` used identically across Tasks 1–4; adapter ids `"ticketmaster"`/`"bandsintown"`/`"band-site"` match `@Order` 1/2/3; `ShowAggregationService` new constructor `(ArtistRepository, ShowRepository, SearchSettingsRepository, MusicBrainzService, List<ShowSource>)` matches the Task 4 test.
- **Behavior:** blank-skip, URL discovery+caching, city filter, empty-on-null-URL, de-dup order (TM→Bandsintown→band-site via `@Order`) all preserved; only the per-source debug log shape changes (now one line per `source.id()` instead of a combined line) — non-behavioral.
