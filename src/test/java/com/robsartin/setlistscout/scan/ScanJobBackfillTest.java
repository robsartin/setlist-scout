package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.GeocodingService;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import java.util.Optional;

/**
 * Task 4 (PR4b): a startup reconciler that backfills one scan_job per ShowSource for every
 * active artist that predates the job tables (PR3b's ArtistActivated listeners only fired for
 * artists activated after that point). Idempotent via insertIfAbsent, jittered next_due_at.
 */
@SpringBootTest
@Testcontainers
class ScanJobBackfillTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "scan-backfill-test@example.com";

    @Autowired
    private ScanJobBackfill scanJobBackfill;

    @Autowired
    private ScanJobRepository scanJobRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private List<ShowSource> showSources;

    /** Startup seeders (SettingsSeeder) geocode the seed owner's default ZIP; stub it out so
     * context startup doesn't depend on network access to Zippopotam.us. */
    @MockitoBean
    private GeocodingService geocodingService;

    @Test
    @DisplayName("backfill enqueues one scan job per source for each active artist, jittered, idempotently")
    void backfillEnqueuesJobsForActiveArtists() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        // Two active (SEED + APPROVED) + one REJECTED artist for the same owner.
        Artist seed = save(artist(OWNER, "Wilco", ArtistStatus.SEED));
        Artist approved = save(artist(OWNER, "Dawes", ArtistStatus.APPROVED));
        save(artist(OWNER, "Nope", ArtistStatus.REJECTED));

        Instant before = Instant.now();
        scanJobBackfill.run(null);   // ApplicationRunner#run(ApplicationArguments)

        List<ScanJob> seedJobs = scanJobRepository.findByOwnerAndArtistId(OWNER, seed.getId());
        assertThat(seedJobs).hasSize(showSources.size());
        assertThat(scanJobRepository.findByOwnerAndArtistId(OWNER, approved.getId()))
                .hasSize(showSources.size());
        // No jobs for the rejected artist.
        assertThat(scanJobRepository.findByOwner(OWNER))
                .allSatisfy(j -> assertThat(j.getArtistId()).isIn(seed.getId(), approved.getId()));
        // next_due_at jittered into [now, now + spread].
        assertThat(seedJobs).allSatisfy(j -> assertThat(j.getNextDueAt())
                .isBetween(before, before.plus(Duration.ofHours(2)).plusSeconds(5)));

        // Idempotent: a second run adds nothing.
        scanJobBackfill.run(null);
        assertThat(scanJobRepository.findByOwnerAndArtistId(OWNER, seed.getId()))
                .hasSize(showSources.size());
    }

    private Artist artist(String owner, String name, ArtistStatus status) {
        Artist a = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        a.setOwner(owner);
        return a;
    }

    private Artist save(Artist artist) {
        return artistRepository.save(artist);
    }
}
