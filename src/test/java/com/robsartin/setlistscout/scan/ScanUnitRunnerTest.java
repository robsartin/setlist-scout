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
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1");
        when(showSource.search(any())).thenReturn(List.of(show));
        when(showRepository.existsByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                eq(OWNER), eq("ZZ Top"), any(), eq("Moody Center"))).thenReturn(false);

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
        verify(showRepository).save(show);
    }

    @Test
    @DisplayName("a duplicate show is not re-persisted")
    void skipsDuplicateShow() {
        Artist zz = seed("ZZ Top");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(zz));

        Show show = new Show("ZZ Top", LocalDateTime.now().plusDays(10), "Moody Center", "Austin",
                BigDecimal.TEN, SOURCE_ID, "https://tickets.example/1");
        when(showSource.search(any())).thenReturn(List.of(show));
        when(showRepository.existsByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                eq(OWNER), eq("ZZ Top"), any(), eq("Moody Center"))).thenReturn(true);

        int saved = runner.run(OWNER, ARTIST_ID, SOURCE_ID);

        assertThat(saved).isEqualTo(0);
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
}
