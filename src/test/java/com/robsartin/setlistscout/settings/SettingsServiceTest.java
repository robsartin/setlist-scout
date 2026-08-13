package com.robsartin.setlistscout.settings;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.shared.events.SettingsChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingsServiceTest {

    private static final String OWNER = "rob@example.com";

    private SearchSettingsRepository settingsRepository;
    private GeocodingService geocodingService;
    private ApplicationEventPublisher publisher;
    private SettingsService settingsService;

    @BeforeEach
    void setUp() {
        settingsRepository = mock(SearchSettingsRepository.class);
        geocodingService = mock(GeocodingService.class);
        AppProperties appProperties = mock(AppProperties.class);
        publisher = mock(ApplicationEventPublisher.class);
        settingsService = new SettingsService(settingsRepository, appProperties, geocodingService, publisher);
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
        verify(publisher).publishEvent(new SettingsChanged(OWNER));
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
        verify(publisher).publishEvent(new SettingsChanged(OWNER));
    }

    @Test
    @DisplayName("getOrCreateSettings does not publish SettingsChanged")
    void getOrCreateSettingsDoesNotPublish() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));

        settingsService.getOrCreateSettings(OWNER);

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("locationFingerprint returns same hash for same settings")
    void locationFingerprintIsStableForSameSettings() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings.setPostalCode("78701");
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));

        String fingerprint1 = settingsService.locationFingerprint(OWNER);
        String fingerprint2 = settingsService.locationFingerprint(OWNER);

        assertThat(fingerprint1).isEqualTo(fingerprint2);
    }

    @Test
    @DisplayName("locationFingerprint returns different hash for different radius")
    void locationFingerprintDifferentForDifferentRadius() {
        SearchSettings settings1 = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings1.setPostalCode("78701");
        SearchSettings settings2 = new SearchSettings(OWNER, "Austin", "TX", 25, 6);
        settings2.setPostalCode("78701");
        when(settingsRepository.findByOwner(OWNER))
                .thenReturn(Optional.of(settings1))
                .thenReturn(Optional.of(settings2));

        String fingerprint1 = settingsService.locationFingerprint(OWNER);
        String fingerprint2 = settingsService.locationFingerprint(OWNER);

        assertThat(fingerprint1).isNotEqualTo(fingerprint2);
    }

    @Test
    @DisplayName("locationFingerprint returns different hash for different postal code")
    void locationFingerprintDifferentForDifferentPostalCode() {
        SearchSettings settings1 = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings1.setPostalCode("78701");
        SearchSettings settings2 = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings2.setPostalCode("75001");
        when(settingsRepository.findByOwner(OWNER))
                .thenReturn(Optional.of(settings1))
                .thenReturn(Optional.of(settings2));

        String fingerprint1 = settingsService.locationFingerprint(OWNER);
        String fingerprint2 = settingsService.locationFingerprint(OWNER);

        assertThat(fingerprint1).isNotEqualTo(fingerprint2);
    }

    @Test
    @DisplayName("locationFingerprint returns different hash for different months ahead")
    void locationFingerprintDifferentForDifferentMonthsAhead() {
        SearchSettings settings1 = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings1.setPostalCode("78701");
        SearchSettings settings2 = new SearchSettings(OWNER, "Austin", "TX", 50, 12);
        settings2.setPostalCode("78701");
        when(settingsRepository.findByOwner(OWNER))
                .thenReturn(Optional.of(settings1))
                .thenReturn(Optional.of(settings2));

        String fingerprint1 = settingsService.locationFingerprint(OWNER);
        String fingerprint2 = settingsService.locationFingerprint(OWNER);

        assertThat(fingerprint1).isNotEqualTo(fingerprint2);
    }

    @Test
    @DisplayName("locationFingerprint does not publish any event")
    void locationFingerprintDoesNotPublish() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings.setPostalCode("78701");
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));

        settingsService.locationFingerprint(OWNER);

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("locationFingerprint for an owner with no settings row hashes the configured "
            + "defaults instead of creating one -- this is the fix for the PR3b startup race, "
            + "where scan.ScanJobListener calls this once per enqueued job for every ArtistActivated "
            + "event, and CatalogSeeder fires dozens of those for the same seed owner at boot")
    void locationFingerprintForMissingSettingsHashesDefaultsWithoutCreating() {
        AppProperties appProperties = mock(AppProperties.class);
        AppProperties.Defaults defaults = new AppProperties.Defaults("73301", "Austin", "TX", 50, 6);
        when(appProperties.defaults()).thenReturn(defaults);
        settingsService = new SettingsService(settingsRepository, appProperties, geocodingService, publisher);
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.empty());

        String fingerprint = settingsService.locationFingerprint(OWNER);

        String expected = Integer.toHexString(
                java.util.Objects.hash(defaults.postalCode(), defaults.radiusMiles(), defaults.monthsAhead()));
        assertThat(fingerprint).isEqualTo(expected);
        verify(settingsRepository, never()).save(any());
        verify(geocodingService, never()).geocode(any());
    }
}
