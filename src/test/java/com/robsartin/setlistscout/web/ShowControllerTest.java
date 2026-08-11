package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.config.AppProperties;
import com.robsartin.setlistscout.domain.SearchSettings;
import com.robsartin.setlistscout.repository.SearchSettingsRepository;
import com.robsartin.setlistscout.repository.ShowRepository;
import com.robsartin.setlistscout.service.GeocodingService;
import com.robsartin.setlistscout.service.ShowAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShowControllerTest {

    private static final String OWNER = "rob@example.com";

    private SearchSettingsRepository settingsRepository;
    private GeocodingService geocodingService;
    private ShowController controller;

    @BeforeEach
    void setUp() {
        ShowRepository showRepository = mock(ShowRepository.class);
        settingsRepository = mock(SearchSettingsRepository.class);
        ShowAggregationService showAggregationService = mock(ShowAggregationService.class);
        geocodingService = mock(GeocodingService.class);
        AppProperties appProperties = mock(AppProperties.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        controller = new ShowController(showRepository, settingsRepository, showAggregationService,
                geocodingService, appProperties, currentUser);
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
}
