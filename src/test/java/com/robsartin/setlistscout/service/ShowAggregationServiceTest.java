package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.repository.ShowRepository;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SearchSettingsRepository;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private TicketmasterService ticketmaster;
    private BandsintownService bandsintown;
    private MusicBrainzService musicBrainz;
    private BandSiteScraperService bandSiteScraper;
    private ShowAggregationService aggregation;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        showRepository = mock(ShowRepository.class);
        settingsRepository = mock(SearchSettingsRepository.class);
        ticketmaster = mock(TicketmasterService.class);
        bandsintown = mock(BandsintownService.class);
        musicBrainz = mock(MusicBrainzService.class);
        bandSiteScraper = mock(BandSiteScraperService.class);
        aggregation = new ShowAggregationService(artistRepository, showRepository, settingsRepository,
                ticketmaster, bandsintown, musicBrainz, bandSiteScraper);

        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings.setPostalCode("78701");
        settings.setLatitude(30.2672);
        settings.setLongitude(-97.7431);
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));
        when(ticketmaster.searchShows(any(), any(), anyInt(), any(), any())).thenReturn(List.of());
        when(bandsintown.searchShows(any(), any(), any(), anyInt(), any(), any())).thenReturn(List.of());
        when(musicBrainz.findOfficialHomepage(any())).thenReturn(Optional.empty());
    }

    private static Artist seed(String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(OWNER);
        return artist;
    }

    @Test
    @DisplayName("a blank-named active artist is skipped -- never triggers a keyword-less Ticketmaster search")
    void skipsBlankNamedArtist() {
        when(artistRepository.findByOwnerAndStatusIn(eq(OWNER), any()))
                .thenReturn(List.of(seed("   "), seed("ZZ Top")));

        aggregation.scanForShows(OWNER);

        verify(ticketmaster).searchShows(eq("ZZ Top"), any(), anyInt(), any(), any());
        verify(ticketmaster, never())
                .searchShows(argThat(name -> name == null || name.isBlank()), any(), anyInt(), any(), any());
    }
}
