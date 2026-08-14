package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.RelationSource;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Task 4 (PR4b): a startup reconciler that backfills one expand_job per RelationSource for every
 * active artist that predates the job tables, mirroring scan.ScanJobBackfillTest. Tribute expand
 * jobs stay SEED-only, matching ExpandJobListener#onArtistActivated.
 */
@SpringBootTest
@Testcontainers
class ExpandJobBackfillTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "expand-backfill-test@example.com";

    @Autowired
    private ExpandJobBackfill expandJobBackfill;

    @Autowired
    private ExpandJobRepository expandJobRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private List<RelationSource> relationSources;

    /** Startup seeders (SettingsSeeder) geocode the seed owner's default ZIP; stub it out so
     * context startup doesn't depend on network access to Zippopotam.us. */
    @MockitoBean
    private GeocodingService geocodingService;

    @Test
    @DisplayName("backfill enqueues one expand job per source for each active artist, jittered, "
            + "idempotently, with tribute-only-for-SEED")
    void backfillEnqueuesJobsForActiveArtists() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        // Two active (SEED + APPROVED) + one REJECTED artist for the same owner.
        Artist seed = save(artist(OWNER, "Wilco", ArtistStatus.SEED));
        Artist approved = save(artist(OWNER, "Dawes", ArtistStatus.APPROVED));
        save(artist(OWNER, "Nope", ArtistStatus.REJECTED));

        long tributeSources = relationSources.stream()
                .filter(s -> s.classification() == ArtistSource.TRIBUTE_EXPANSION)
                .count();

        Instant before = Instant.now();
        expandJobBackfill.run(null);   // ApplicationRunner#run(ApplicationArguments)

        List<ExpandJob> seedJobs = expandJobRepository.findByOwnerAndArtistId(OWNER, seed.getId());
        List<ExpandJob> approvedJobs = expandJobRepository.findByOwnerAndArtistId(OWNER, approved.getId());
        // SEED gets every source, including tribute; APPROVED gets everything except tribute.
        assertThat(seedJobs).hasSize(relationSources.size());
        assertThat(approvedJobs).hasSize(relationSources.size() - (int) tributeSources);
        assertThat(approvedJobs).extracting(ExpandJob::getSource)
                .noneMatch(sourceId -> relationSources.stream()
                        .anyMatch(s -> s.id().equals(sourceId)
                                && s.classification() == ArtistSource.TRIBUTE_EXPANSION));
        // No jobs for the rejected artist.
        assertThat(expandJobRepository.findByOwner(OWNER))
                .allSatisfy(j -> assertThat(j.getArtistId()).isIn(seed.getId(), approved.getId()));
        // next_due_at jittered into [now, now + spread].
        assertThat(seedJobs).allSatisfy(j -> assertThat(j.getNextDueAt())
                .isBetween(before, before.plus(Duration.ofHours(2)).plusSeconds(5)));

        // Idempotent: a second run adds nothing.
        expandJobBackfill.run(null);
        assertThat(expandJobRepository.findByOwnerAndArtistId(OWNER, seed.getId()))
                .hasSize(relationSources.size());
        assertThat(expandJobRepository.findByOwnerAndArtistId(OWNER, approved.getId()))
                .hasSize(relationSources.size() - (int) tributeSources);
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
