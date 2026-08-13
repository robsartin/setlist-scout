package com.robsartin.setlistscout.settings;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.shared.events.SettingsChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Owns settings persistence + geocoding: the owner's default row and updates from the settings form. */
@Service
public class SettingsService {

    private final SearchSettingsRepository settingsRepository;
    private final AppProperties appProperties;
    private final GeocodingService geocodingService;
    private final ApplicationEventPublisher publisher;

    public SettingsService(SearchSettingsRepository settingsRepository,
                            AppProperties appProperties,
                            GeocodingService geocodingService,
                            ApplicationEventPublisher publisher) {
        this.settingsRepository = settingsRepository;
        this.appProperties = appProperties;
        this.geocodingService = geocodingService;
        this.publisher = publisher;
    }

    /** The user's settings, creating a default row (default ZIP, geocoded) on their first visit. */
    public SearchSettings getOrCreateSettings(String owner) {
        return settingsRepository.findByOwner(owner).orElseGet(() -> {
            var d = appProperties.defaults();
            SearchSettings settings = new SearchSettings(owner, d.city(), d.state(), d.radiusMiles(), d.monthsAhead());
            settings.setPostalCode(d.postalCode());
            applyGeocode(settings, d.postalCode());
            return settingsRepository.save(settings);
        });
    }

    @Transactional
    public SearchSettings updateSettings(String owner, String postalCode, int radiusMiles, int monthsAhead) {
        SearchSettings settings = getOrCreateSettings(owner);
        settings.setPostalCode(postalCode);
        settings.setRadiusMiles(radiusMiles);
        settings.setMonthsAhead(monthsAhead);
        // Geocode the ZIP to lat/long (+ display city/state). On failure, keep the last-known
        // coordinates so a bad/temporary lookup doesn't blank out the search location.
        applyGeocode(settings, postalCode);
        settingsRepository.save(settings);
        publisher.publishEvent(new SettingsChanged(owner));
        return settings;
    }

    private void applyGeocode(SearchSettings settings, String postalCode) {
        geocodingService.geocode(postalCode).ifPresent(geo -> {
            settings.setLatitude(geo.latitude());
            settings.setLongitude(geo.longitude());
            settings.setCity(geo.city());
            settings.setState(geo.state());
        });
    }

    /**
     * Returns a stable hash of the owner's search location (postal code, radius, months ahead).
     * Pure read; does not publish any event, and -- unlike {@link #getOrCreateSettings} -- does
     * NOT create a settings row for an owner who doesn't have one yet, hashing the configured
     * defaults instead. This matters because {@code scan.ScanJobListener} calls this from an
     * async {@code @ApplicationModuleListener} once per enqueued job: at startup, {@code
     * CatalogSeeder} fires dozens of {@code ArtistActivated} events for the same seed owner in
     * quick succession, and each one used to race the others through {@code getOrCreateSettings}'s
     * non-atomic check-then-insert against the {@code search_settings} owner unique constraint.
     * Lets scan jobs detect a stale location on SettingsChanged.
     */
    public String locationFingerprint(String owner) {
        return settingsRepository.findByOwner(owner)
                .map(s -> Integer.toHexString(Objects.hash(s.getPostalCode(), s.getRadiusMiles(), s.getMonthsAhead())))
                .orElseGet(() -> {
                    var d = appProperties.defaults();
                    return Integer.toHexString(Objects.hash(d.postalCode(), d.radiusMiles(), d.monthsAhead()));
                });
    }
}
