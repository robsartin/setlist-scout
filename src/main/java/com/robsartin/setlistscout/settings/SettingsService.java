package com.robsartin.setlistscout.settings;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.shared.events.SettingsChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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
        return settingsRepository.findByOwner(owner).orElseGet(() -> newDefaultSettings(owner, geocodeDefault()));
    }

    /**
     * Same as {@link #getOrCreateSettings(String)}, but for a caller that already resolved the
     * default-ZIP geocode itself via {@link #geocodeDefault()} -- skips this method's own geocode
     * lookup for a brand-new owner's row entirely, rather than making the same network call twice.
     */
    public SearchSettings getOrCreateSettings(String owner, Optional<GeocodingService.GeoResult> precomputedDefaultGeocode) {
        return settingsRepository.findByOwner(owner).orElseGet(() -> newDefaultSettings(owner, precomputedDefaultGeocode));
    }

    /**
     * Resolves the app's default-ZIP geocode on its own, for a caller that must complete this slow
     * external lookup BEFORE opening its own transaction (ADR-0024) -- see {@code
     * SharedScanService#create}, which provisions a brand-new owner key whose settings row always
     * needs this (it can never already exist), and cannot hold its creating transaction's DB
     * connection open across the network call that would otherwise make.
     */
    public Optional<GeocodingService.GeoResult> geocodeDefault() {
        return geocodingService.geocode(appProperties.defaults().postalCode());
    }

    private SearchSettings newDefaultSettings(String owner, Optional<GeocodingService.GeoResult> geocode) {
        var d = appProperties.defaults();
        SearchSettings settings = new SearchSettings(owner, d.city(), d.state(), d.radiusMiles(), d.monthsAhead());
        settings.setPostalCode(d.postalCode());
        geocode.ifPresent(geo -> applyGeocodeResult(settings, geo));
        return settingsRepository.save(settings);
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
        geocodingService.geocode(postalCode).ifPresent(geo -> applyGeocodeResult(settings, geo));
    }

    private void applyGeocodeResult(SearchSettings settings, GeocodingService.GeoResult geo) {
        settings.setLatitude(geo.latitude());
        settings.setLongitude(geo.longitude());
        settings.setCity(geo.city());
        settings.setState(geo.state());
    }
}
