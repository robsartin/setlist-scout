package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.config.AppProperties;
import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.repository.ShowRepository;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SearchSettingsRepository;
import com.robsartin.setlistscout.shared.CurrentUser;
import com.robsartin.setlistscout.service.AsyncScanRunner;
import com.robsartin.setlistscout.settings.GeocodingService;
import com.robsartin.setlistscout.service.ScanStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShowControllerTest {

    private static final String OWNER = "rob@example.com";

    private ShowRepository showRepository;
    private SearchSettingsRepository settingsRepository;
    private ArtistRepository artistRepository;
    private GeocodingService geocodingService;
    private ScanStateService scanState;
    private AsyncScanRunner asyncScanRunner;
    private ShowController controller;

    @BeforeEach
    void setUp() {
        showRepository = mock(ShowRepository.class);
        settingsRepository = mock(SearchSettingsRepository.class);
        artistRepository = mock(ArtistRepository.class);
        geocodingService = mock(GeocodingService.class);
        AppProperties appProperties = mock(AppProperties.class);
        scanState = mock(ScanStateService.class);
        asyncScanRunner = mock(AsyncScanRunner.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        when(artistRepository.findByOwnerAndSource(OWNER, ArtistSource.TRIBUTE_EXPANSION))
                .thenReturn(List.of());
        controller = new ShowController(showRepository, settingsRepository, artistRepository, asyncScanRunner,
                scanState, geocodingService, appProperties, currentUser);
    }

    @Test
    @DisplayName("updateSettings geocodes the ZIP and stores lat/long + derived city/state")
    void updateSettingsGeocodesZip() {
        SearchSettings settings = new SearchSettings(OWNER, "OldCity", "OL", 25, 3);
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));
        when(geocodingService.geocode("78701"))
                .thenReturn(Optional.of(new GeocodingService.GeoResult(30.2672, -97.7431, "Austin", "TX")));

        String view = controller.updateSettings("78701", 50, 6);

        assertThat(view).isEqualTo("redirect:/");
        assertThat(settings.getPostalCode()).isEqualTo("78701");
        assertThat(settings.getLatitude()).isEqualTo(30.2672);
        assertThat(settings.getLongitude()).isEqualTo(-97.7431);
        assertThat(settings.getCity()).isEqualTo("Austin");
        assertThat(settings.getState()).isEqualTo("TX");
        assertThat(settings.getRadiusMiles()).isEqualTo(50);
        assertThat(settings.getMonthsAhead()).isEqualTo(6);
        verify(settingsRepository).save(settings);
    }

    @Test
    @DisplayName("updateSettings keeps last-known coordinates when geocoding fails")
    void updateSettingsKeepsCoordsOnGeocodeFailure() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings.setLatitude(30.0);
        settings.setLongitude(-97.0);
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));
        when(geocodingService.geocode("00000")).thenReturn(Optional.empty());

        controller.updateSettings("00000", 50, 6);

        assertThat(settings.getPostalCode()).isEqualTo("00000");
        assertThat(settings.getLatitude()).isEqualTo(30.0);
        assertThat(settings.getLongitude()).isEqualTo(-97.0);
        verify(settingsRepository).save(settings);
    }

    @Test
    @DisplayName("scanNow (htmx) kicks off the async scan and returns the scanning fragment")
    void scanNowHtmxReturnsScanningFragment() {
        Model model = new ExtendedModelMap();

        String view = controller.scanNow("hx", model);

        verify(asyncScanRunner).startScan(OWNER);
        assertThat(view).isEqualTo("shows :: showsRegion");
        assertThat(model.getAttribute("scanning")).isEqualTo(true);
        assertThat(model.getAttribute("scanLabel")).isEqualTo("Scanning...");
    }

    @Test
    @DisplayName("scanNow without htmx still starts the scan and redirects (no-JS fallback)")
    void scanNowNonHtmxRedirects() {
        Model model = new ExtendedModelMap();

        String view = controller.scanNow(null, model);

        verify(asyncScanRunner).startScan(OWNER);
        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("scanStatus returns the scanning fragment while a scan is in progress")
    void scanStatusWhileRunningReturnsScanningFragment() {
        when(scanState.isRunning(OWNER)).thenReturn(true);
        Model model = new ExtendedModelMap();

        String view = controller.scanStatus("eventDate", model);

        assertThat(view).isEqualTo("shows :: showsRegion");
        assertThat(model.getAttribute("scanning")).isEqualTo(true);
        assertThat(model.getAttribute("scanLabel")).isEqualTo("Scanning...");
    }

    @Test
    @DisplayName("shows puts the owner's lowercased tribute-artist names in the model")
    void showsIncludesTributeArtistNames() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));
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

    @Test
    @DisplayName("scanStatus returns the refreshed shows fragment once the scan is done")
    void scanStatusWhenDoneReturnsShowsFragment() {
        when(scanState.isRunning(OWNER)).thenReturn(false);
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));
        when(showRepository.findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(anyString(), any(), any()))
                .thenReturn(new java.util.ArrayList<>(List.of()));
        Model model = new ExtendedModelMap();

        String view = controller.scanStatus("eventDate", model);

        assertThat(view).isEqualTo("shows :: showsRegion");
        assertThat(model.getAttribute("scanning")).isEqualTo(false);
        assertThat(model.getAttribute("justScanned")).isEqualTo(true);
        assertThat(model.getAttribute("shows")).isNotNull();
    }
}
