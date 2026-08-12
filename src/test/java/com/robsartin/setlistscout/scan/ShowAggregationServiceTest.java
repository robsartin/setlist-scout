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
