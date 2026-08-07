package com.robsartin.setlistscout.config;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.domain.SearchSettings;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.repository.SearchSettingsRepository;
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

    public DataInitializer(SearchSettingsRepository settingsRepository,
                            ArtistRepository artistRepository,
                            AppProperties appProperties) {
        this.settingsRepository = settingsRepository;
        this.artistRepository = artistRepository;
        this.appProperties = appProperties;
    }

    @Override
    public void run(String... args) throws Exception {
        seedSettingsIfMissing();
        importSeedBandsIfEmpty();
    }

    private void seedSettingsIfMissing() {
        if (settingsRepository.findById(1L).isEmpty()) {
            var d = appProperties.defaults();
            settingsRepository.save(new SearchSettings(d.city(), d.state(), d.radiusMiles(), d.monthsAhead()));
        }
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
