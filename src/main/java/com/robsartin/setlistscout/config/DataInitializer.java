package com.robsartin.setlistscout.config;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.domain.SearchSettings;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.repository.SearchSettingsRepository;
import com.robsartin.setlistscout.service.GeocodingService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Runs once at startup. Safe to run every deploy -- both steps are idempotent
 * (SearchSettings is a pinned singleton row; seed artists are skipped if they
 * already exist by name).
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final SearchSettingsRepository settingsRepository;
    private final ArtistRepository artistRepository;
    private final AppProperties appProperties;
    private final GeocodingService geocodingService;

    public DataInitializer(SearchSettingsRepository settingsRepository,
                            ArtistRepository artistRepository,
                            AppProperties appProperties,
                            GeocodingService geocodingService) {
        this.settingsRepository = settingsRepository;
        this.artistRepository = artistRepository;
        this.appProperties = appProperties;
        this.geocodingService = geocodingService;
    }

    @Override
    public void run(String... args) throws Exception {
        seedAndBackfillSettings();
        importSeedBandsIfEmpty();
    }

    /**
     * Ensures the singleton settings row exists and has a ZIP + geocoded coordinates --
     * creating it from defaults if missing, and backfilling the ZIP/lat/long for a row
     * that predates ZIP-based location. Idempotent and best-effort (a failed geocode just
     * leaves coordinates null; Bandsintown then falls back to all-in-window).
     */
    private void seedAndBackfillSettings() {
        var d = appProperties.defaults();
        SearchSettings settings = settingsRepository.findById(1L)
                .orElseGet(() -> new SearchSettings(d.city(), d.state(), d.radiusMiles(), d.monthsAhead()));
        if (settings.getPostalCode() == null || settings.getPostalCode().isBlank()) {
            settings.setPostalCode(d.postalCode());
        }
        if (settings.getLatitude() == null || settings.getLongitude() == null) {
            geocodingService.geocode(settings.getPostalCode()).ifPresent(geo -> {
                settings.setLatitude(geo.latitude());
                settings.setLongitude(geo.longitude());
                settings.setCity(geo.city());
                settings.setState(geo.state());
            });
        }
        settingsRepository.save(settings);
    }

    private void importSeedBandsIfEmpty() throws Exception {
        if (!artistRepository.findByStatus(ArtistStatus.SEED).isEmpty()) {
            return; // already imported
        }
        ClassPathResource resource = new ClassPathResource("data/seed-bands.txt");
        if (!resource.exists()) return;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                if (!artistRepository.existsByNameIgnoreCase(name)) {
                    artistRepository.save(new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null));
                }
            }
        }
    }
}
