package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.service.TestAppProperties;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShowControllerTest {

    private static final String OWNER = "rob@example.com";

    private ShowRepository showRepository;
    private ArtistRepository artistRepository;
    private ScanJobRepository scanJobRepository;
    private SettingsService settingsService;
    private ShowController controller;

    @BeforeEach
    void setUp() {
        showRepository = mock(ShowRepository.class);
        artistRepository = mock(ArtistRepository.class);
        scanJobRepository = mock(ScanJobRepository.class);
        settingsService = mock(SettingsService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        when(artistRepository.findByOwnerAndSource(OWNER, ArtistSource.TRIBUTE_EXPANSION))
                .thenReturn(List.of());
        AppProperties appProperties = TestAppProperties.withKeys();
        controller = new ShowController(showRepository, artistRepository, scanJobRepository,
                settingsService, currentUser, appProperties);
    }

    @Test
    @DisplayName("scanNow (htmx) re-dues the owner's scan jobs and returns the queued confirmation fragment")
    void scanNowHtmxReturnsQueuedFragment() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        when(showRepository.findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(anyString(), any(), any()))
                .thenReturn(new java.util.ArrayList<>(List.of()));
        Model model = new ExtendedModelMap();

        String view = controller.scanNow("hx", "eventDate", model);

        verify(scanJobRepository).redueAll(eq(OWNER), any(Instant.class));
        assertThat(view).isEqualTo("shows :: showsRegion");
        assertThat(model.getAttribute("scanQueued")).isEqualTo(true);
    }

    @Test
    @DisplayName("scanNow without htmx still re-dues the jobs and redirects (no-JS fallback)")
    void scanNowNonHtmxRedirects() {
        Model model = new ExtendedModelMap();

        String view = controller.scanNow(null, "eventDate", model);

        verify(scanJobRepository).redueAll(eq(OWNER), any(Instant.class));
        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("shows puts the owner's lowercased tribute-artist names in the model")
    void showsIncludesTributeArtistNames() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        when(showRepository.findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(anyString(), any(), any()))
                .thenReturn(new java.util.ArrayList<>(List.of()));
        Artist tribute = new Artist("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION, ArtistStatus.APPROVED,
                "expansion", "tribute act");
        when(artistRepository.findByOwnerAndSource(OWNER, ArtistSource.TRIBUTE_EXPANSION))
                .thenReturn(List.of(tribute));
        Model model = new ExtendedModelMap();

        controller.shows("eventDate", model);

        @SuppressWarnings("unchecked")
        Set<String> tributeNames = (Set<String>) model.getAttribute("tributeArtistNames");
        assertThat(tributeNames).containsExactly("damn the torpedoes");
    }
}
