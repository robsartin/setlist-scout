package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSiteUrlService;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanUnitRunnerTest {

    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 7L;
    private static final String SOURCE_ID = "ticketmaster";

    private ArtistRepository artistRepository;
    private ArtistSiteUrlService artistSiteUrlService;
    private ShowRepository showRepository;
    private SearchSettingsRepository settingsRepository;
    private MusicBrainzService musicBrainz;
    private ShowSource showSource;
    private ScanUnitRunner runner;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        artistSiteUrlService = mock(ArtistSiteUrlService.class);
        showRepository = mock(ShowRepository.class);
        settingsRepository = mock(SearchSettingsRepository.class);
        musicBrainz = mock(MusicBrainzService.class);
        showSource = mock(ShowSource.class);
        when(showSource.id()).thenReturn(SOURCE_ID);
        when(showSource.search(any())).thenReturn(List.of());
        runner = new ScanUnitRunner(List.of(showSource), artistRepository, artistSiteUrlService, showRepository,
                settingsRepository, musicBrainz);

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
    @DisplayName("runs the unit: builds a ScanQuery with the resolved URL + settings, persists via persistNew")
    void runsUnitAndPersistsNewShow() {
        Artist zz = seed("ZZ Top");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(zz));
        when(musicBrainz.findOfficialHomepage("ZZ Top")).thenReturn(Optional.of("https://zztop.com"));

        Show show = new Show("ZZ Top", LocalDateTime.now().plusDays(10), "Moody Center", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1", Show.Kind.MUSIC);
        when(showSource.search(any())).thenReturn(List.of(show));
        // Issue #230: the insert itself goes through insertIfAbsent's atomic ON CONFLICT DO
        // NOTHING, never existsBy/findBy + save -- see ScanUnitRunner#persistNew's javadoc.
        when(showRepository.insertIfAbsent(eq(OWNER), eq("ZZ Top"), eq(show.getEventDateTime()),
                eq("Moody Center"), eq("Austin"), eq(BigDecimal.TEN), eq(SOURCE_ID),
                eq("https://tickets.example/1"), eq("MUSIC"), eq(show.getDiscoveredAt()), eq(ARTIST_ID)))
                .thenReturn(1);

        int saved = runner.run(OWNER, ARTIST_ID, SOURCE_ID);

        assertThat(saved).isEqualTo(1);
        verify(showSource).search(argThat(q ->
                q.artistName().equals("ZZ Top")
                        && "https://zztop.com".equals(q.officialSiteUrl())
                        && "78701".equals(q.postalCode())
                        && q.radiusMiles() == 50
                        && "Austin".equals(q.city())));
        verify(artistSiteUrlService).recordOfficialSiteUrl(zz.getId(), OWNER, "https://zztop.com");
        verify(artistRepository, never()).save(any());
        verify(showRepository, never()).save(any());
        assertThat(show.getArtistId())
                .as("issue #223: the persist path writes the scanning artist's id through")
                .isEqualTo(ARTIST_ID);
    }

    @Test
    @DisplayName("issue #223: writes the scanning artist's id through even when artist_name is an "
            + "event title name-matching could never resolve (the Ticketmaster case)")
    void writesArtistIdThroughEvenWhenNameIsAnEventTitle() {
        Artist symphony = seed("Austin Symphony Orchestra");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(symphony));

        // TicketmasterService sets artist_name to the EVENT TITLE, not the artist's catalog name --
        // this string matches nothing in the catalog by name, on purpose (see TicketmasterService).
        String eventTitle = "A Very Merry Symphony ft. Austin Symphony Orchestra";
        Show show = new Show(eventTitle, LocalDateTime.now().plusDays(10), "Moody Center", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1", Show.Kind.MUSIC);
        when(showSource.search(any())).thenReturn(List.of(show));
        when(showRepository.insertIfAbsent(eq(OWNER), eq(eventTitle), any(), eq("Moody Center"),
                any(), any(), any(), any(), any(), any(), eq(ARTIST_ID))).thenReturn(1);

        int saved = runner.run(OWNER, ARTIST_ID, SOURCE_ID);

        assertThat(saved).isEqualTo(1);
        assertThat(show.getArtistId())
                .as("100% accurate even though name-matching could never have resolved this row")
                .isEqualTo(ARTIST_ID);
    }

    @Test
    @DisplayName("a duplicate show is not re-persisted")
    void skipsDuplicateShow() {
        Artist zz = seed("ZZ Top");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(zz));

        Show show = new Show("ZZ Top", LocalDateTime.now().plusDays(10), "Moody Center", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1", Show.Kind.MUSIC);
        when(showSource.search(any())).thenReturn(List.of(show));
        // insertIfAbsent's ON CONFLICT DO NOTHING suppresses the collision and returns 0 -- it
        // already existed (issue #230).
        when(showRepository.insertIfAbsent(eq(OWNER), eq("ZZ Top"), any(), eq("Moody Center"),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        Show existing = new Show("ZZ Top", show.getEventDateTime(), "Moody Center", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1", Show.Kind.MUSIC);
        existing.setArtistId(ARTIST_ID);
        when(showRepository.findByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                eq(OWNER), eq("ZZ Top"), any(), eq("Moody Center"))).thenReturn(Optional.of(existing));

        int saved = runner.run(OWNER, ARTIST_ID, SOURCE_ID);

        assertThat(saved).isEqualTo(0);
        verify(showRepository, never()).save(any());
    }

    @Test
    @DisplayName("issue #223: a rescan fills a null artist_id on an existing row (self-heal)")
    void rescanRepairsNullArtistIdOnExistingRow() {
        Artist zz = seed("ZZ Top");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(zz));

        Show rediscovered = new Show("ZZ Top", LocalDateTime.now().plusDays(10), "Moody Center", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1", Show.Kind.MUSIC);
        when(showSource.search(any())).thenReturn(List.of(rediscovered));
        when(showRepository.insertIfAbsent(eq(OWNER), eq("ZZ Top"), any(), eq("Moody Center"),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        // The existing row was written before artist_id existed (or otherwise left unresolved) --
        // artistId is left null here to model that.
        Show existing = new Show("ZZ Top", rediscovered.getEventDateTime(), "Moody Center", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1", Show.Kind.MUSIC);
        when(showRepository.findByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                eq(OWNER), eq("ZZ Top"), any(), eq("Moody Center"))).thenReturn(Optional.of(existing));

        int saved = runner.run(OWNER, ARTIST_ID, SOURCE_ID);

        assertThat(saved).as("the row already existed -- not counted as newly saved").isEqualTo(0);
        assertThat(existing.getArtistId()).as("the null was repaired").isEqualTo(ARTIST_ID);
        verify(showRepository).save(existing);
        verify(showRepository, never()).save(rediscovered);
    }

    @Test
    @DisplayName("issue #223: a rescan never overwrites an existing row's already-set artist_id")
    void rescanNeverOverwritesNonNullArtistId() {
        Artist zz = seed("ZZ Top");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(zz));

        Show rediscovered = new Show("ZZ Top", LocalDateTime.now().plusDays(10), "Moody Center", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1", Show.Kind.MUSIC);
        when(showSource.search(any())).thenReturn(List.of(rediscovered));
        when(showRepository.insertIfAbsent(eq(OWNER), eq("ZZ Top"), any(), eq("Moody Center"),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        Show existing = new Show("ZZ Top", rediscovered.getEventDateTime(), "Moody Center", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1", Show.Kind.MUSIC);
        existing.setArtistId(999L); // a different, already-resolved artist -- must survive untouched
        when(showRepository.findByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                eq(OWNER), eq("ZZ Top"), any(), eq("Moody Center"))).thenReturn(Optional.of(existing));

        int saved = runner.run(OWNER, ARTIST_ID, SOURCE_ID);

        assertThat(saved).isEqualTo(0);
        assertThat(existing.getArtistId())
                .as("never overwritten, even though this scan's artistId differs").isEqualTo(999L);
        verify(showRepository, never()).save(any());
    }

    @Test
    @DisplayName("missing artist -- no-op, nothing persisted")
    void missingArtistIsNoOp() {
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.empty());

        int saved = runner.run(OWNER, ARTIST_ID, SOURCE_ID);

        assertThat(saved).isEqualTo(0);
        verify(showSource, never()).search(any());
    }

    @Test
    @DisplayName("missing SearchSettings -- no-op, nothing persisted")
    void missingSettingsIsNoOp() {
        Artist zz = seed("ZZ Top");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(zz));
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.empty());

        int saved = runner.run(OWNER, ARTIST_ID, SOURCE_ID);

        assertThat(saved).isEqualTo(0);
        verify(showSource, never()).search(any());
    }

    @Test
    @DisplayName("unknown sourceId -- no-op, nothing persisted")
    void unknownSourceIsNoOp() {
        Artist zz = seed("ZZ Top");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(zz));

        int saved = runner.run(OWNER, ARTIST_ID, "not-a-real-source");

        assertThat(saved).isEqualTo(0);
        verify(showSource, never()).search(any());
        // Source lookup happens before ScanQuery-building, so an unknown source never
        // wastes a MusicBrainz call or writes the artist's cached site URL.
        verify(musicBrainz, never()).findOfficialHomepage(any());
        verify(artistSiteUrlService, never()).recordOfficialSiteUrl(any(), any(), any());
    }

    @Test
    @DisplayName("source disabled via config (bean absent from injected list) -- no-op, nothing persisted")
    void disabledSourceIsNoOp() {
        Artist zz = seed("ZZ Top");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(zz));

        // "bandsintown" is a real production source id (see BandsintownShowSource#id), but this
        // runner only has "ticketmaster" injected -- exactly what happens when
        // setlistscout.sources.bandsintown=false: Spring never creates that bean, so it's simply
        // absent from the injected List<ShowSource>. Same no-op path as an unrecognized id
        // (#unknownSourceIsNoOp), pinned here under a real disabled-source id rather than a typo.
        int saved = runner.run(OWNER, ARTIST_ID, "bandsintown");

        assertThat(saved).isEqualTo(0);
        verify(showSource, never()).search(any());
    }

    @Test
    @DisplayName("a blank artist name is skipped (defense in depth)")
    void blankArtistNameIsSkipped() {
        Artist blank = seed("   ");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(blank));

        int saved = runner.run(OWNER, ARTIST_ID, SOURCE_ID);

        assertThat(saved).isEqualTo(0);
        verify(showSource, never()).search(any());
    }

    /**
     * Issue #230: {@code persistNew} used to be {@code existsBy}/{@code findBy} + {@code save} --
     * exactly the read-then-write pattern CLAUDE.md's idempotent-write rule forbids. A real
     * constraint violation on one show's {@code save()} (the shape Spring produces for a genuine
     * Postgres unique-index collision -- e.g. two concurrent runs racing the same natural key,
     * since {@code ScanPoller} runs claimed jobs with no ambient transaction around {@code
     * ScanUnitRunner.run}, so each repository call commits independently) threw out of the loop
     * and killed every show after it in the batch. {@code insertIfAbsent}'s {@code ON CONFLICT DO
     * NOTHING} can never throw on that collision -- it just reports 0 rows inserted -- so this pins
     * that a colliding show no longer takes the rest of the batch down with it.
     * <p>
     * Stubs BOTH the old call ({@code save(showB)} throwing, faithfully mirroring what a real
     * constraint violation does) and the new one ({@code insertIfAbsent} for showB's fields
     * returning 0) so this test is meaningful on either implementation: against the pre-fix
     * {@code persistNew} the thrown exception propagates out of this test method (red); against the
     * fixed one, {@code insertIfAbsent} is what's actually called, so the {@code save} stub simply
     * goes unused and every non-colliding show is still attempted (green).
     */
    @Test
    @DisplayName("issue #230: a constraint violation on one show does not abort the rest of the batch")
    void aConstraintViolationOnOneShowDoesNotAbortTheRestOfTheBatch() {
        Show showA = new Show("Artist A", LocalDateTime.now().plusDays(5), "Venue A", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/a", Show.Kind.MUSIC);
        Show showB = new Show("Artist B", LocalDateTime.now().plusDays(6), "Venue B", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/b", Show.Kind.MUSIC);
        Show showC = new Show("Artist C", LocalDateTime.now().plusDays(7), "Venue C", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/c", Show.Kind.MUSIC);

        when(showRepository.save(showB)).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(showRepository.insertIfAbsent(eq(OWNER), eq("Artist A"), any(), eq("Venue A"),
                any(), any(), any(), any(), any(), any(), eq(ARTIST_ID))).thenReturn(1);
        when(showRepository.insertIfAbsent(eq(OWNER), eq("Artist B"), any(), eq("Venue B"),
                any(), any(), any(), any(), any(), any(), eq(ARTIST_ID))).thenReturn(0);
        when(showRepository.insertIfAbsent(eq(OWNER), eq("Artist C"), any(), eq("Venue C"),
                any(), any(), any(), any(), any(), any(), eq(ARTIST_ID))).thenReturn(1);

        int saved = runner.persistNew(OWNER, ARTIST_ID, List.of(showA, showB, showC));

        assertThat(saved).as("only the two genuinely-new shows are counted, not the colliding one").isEqualTo(2);
        verify(showRepository).insertIfAbsent(eq(OWNER), eq("Artist A"), any(), eq("Venue A"),
                any(), any(), any(), any(), any(), any(), eq(ARTIST_ID));
        // issue #230: showC, AFTER the colliding show in the list, must still be attempted -- the
        // whole point of moving off the throwing existsBy/findBy + save pattern.
        verify(showRepository).insertIfAbsent(eq(OWNER), eq("Artist C"), any(), eq("Venue C"),
                any(), any(), any(), any(), any(), any(), eq(ARTIST_ID));
        verify(showRepository, never()).save(any());
    }
}
