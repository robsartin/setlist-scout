package com.robsartin.setlistscout.config;

import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.service.ArtistSeedService;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SearchSettingsRepository;
import com.robsartin.setlistscout.settings.GeocodingService;
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
    private final ArtistSeedService seedService;

    public DataInitializer(SearchSettingsRepository settingsRepository,
                            ArtistRepository artistRepository,
                            AppProperties appProperties,
                            GeocodingService geocodingService,
                            ArtistSeedService seedService) {
        this.settingsRepository = settingsRepository;
        this.artistRepository = artistRepository;
        this.appProperties = appProperties;
        this.geocodingService = geocodingService;
        this.seedService = seedService;
    }

    @Override
    public void run(String... args) throws Exception {
        String seedOwner = appProperties.auth().seedOwner();
        seedSettingsIfMissing(seedOwner);
        importSeedBandsIfEmpty(seedOwner);
    }

    /** Creates the seed owner's settings row (default ZIP, geocoded) if they don't have one yet. */
    private void seedSettingsIfMissing(String owner) {
        if (settingsRepository.findByOwner(owner).isPresent()) return;
        var d = appProperties.defaults();
        SearchSettings settings = new SearchSettings(owner, d.city(), d.state(), d.radiusMiles(), d.monthsAhead());
        settings.setPostalCode(d.postalCode());
        geocodingService.geocode(d.postalCode()).ifPresent(geo -> {
            settings.setLatitude(geo.latitude());
            settings.setLongitude(geo.longitude());
            settings.setCity(geo.city());
            settings.setState(geo.state());
        });
        settingsRepository.save(settings);
    }

    /** Imports seed-bands.txt as the seed owner's SEED artists. Other users start empty. */
    private void importSeedBandsIfEmpty(String owner) throws Exception {
        if (!artistRepository.findByOwnerAndStatus(owner, ArtistStatus.SEED).isEmpty()) {
            return; // already imported
        }
        ClassPathResource resource = new ClassPathResource("data/seed-bands.txt");
        if (!resource.exists()) return;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                seedService.addSeedIfNew(owner, line);
            }
        }
    }
}
