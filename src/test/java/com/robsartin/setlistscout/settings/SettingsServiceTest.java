package com.robsartin.setlistscout.settings;

import com.robsartin.setlistscout.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingsServiceTest {

    private static final String OWNER = "rob@example.com";

    private SearchSettingsRepository settingsRepository;
    private GeocodingService geocodingService;
    private SettingsService settingsService;

    @BeforeEach
    void setUp() {
        settingsRepository = mock(SearchSettingsRepository.class);
        geocodingService = mock(GeocodingService.class);
        AppProperties appProperties = mock(AppProperties.class);
        settingsService = new SettingsService(settingsRepository, appProperties, geocodingService);
    }

    @Test
    @DisplayName("updateSettings geocodes the ZIP and stores lat/long + derived city/state")
    void updateSettingsGeocodesZip() {
        SearchSettings settings = new SearchSettings(OWNER, "OldCity", "OL", 25, 3);
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));
        when(geocodingService.geocode("78701"))
                .thenReturn(Optional.of(new GeocodingService.GeoResult(30.2672, -97.7431, "Austin", "TX")));

        SearchSettings result = settingsService.updateSettings(OWNER, "78701", 50, 6);

        assertThat(result).isSameAs(settings);
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

        settingsService.updateSettings(OWNER, "00000", 50, 6);

        assertThat(settings.getPostalCode()).isEqualTo("00000");
        assertThat(settings.getLatitude()).isEqualTo(30.0);
        assertThat(settings.getLongitude()).isEqualTo(-97.0);
        verify(settingsRepository).save(settings);
    }
}
