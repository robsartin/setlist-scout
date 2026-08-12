package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.AppProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Runs once at startup. Safe to run every deploy -- imports seed-bands.txt as the seed owner's
 * SEED artists, skipping bands that already exist by name. Other users start empty.
 */
@Component
public class CatalogSeeder implements CommandLineRunner {

    private final ArtistRepository artistRepository;
    private final AppProperties appProperties;
    private final ArtistSeedService seedService;

    public CatalogSeeder(ArtistRepository artistRepository,
                          AppProperties appProperties,
                          ArtistSeedService seedService) {
        this.artistRepository = artistRepository;
        this.appProperties = appProperties;
        this.seedService = seedService;
    }

    @Override
    public void run(String... args) throws Exception {
        String seedOwner = appProperties.auth().seedOwner();
        if (!artistRepository.findByOwnerAndStatus(seedOwner, ArtistStatus.SEED).isEmpty()) {
            return; // already imported
        }
        ClassPathResource resource = new ClassPathResource("data/seed-bands.txt");
        if (!resource.exists()) return;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                seedService.addSeedIfNew(seedOwner, line);
            }
        }
    }
}
