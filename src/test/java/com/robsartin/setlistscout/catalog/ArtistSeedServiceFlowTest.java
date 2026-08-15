package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-path Testcontainers proof of issue #124: {@link ArtistSeedService#addSeedIfNew} routes its
 * duplicate check through the same {@link ArtistNameMatcher} normalized-name equality that
 * {@code RelationDiscoveredListener} uses for expansion-discovered names (issue #118), so the
 * user-facing "Add a band" box, the bulk upload, and startup seeding stop creating punctuation-
 * only-variant duplicates against the real DB and its real {@code (owner, name)} unique
 * constraint. {@link JobEnqueueFlowTest#reAddingARejectedArtistsNameReactivatesItAndEnqueuesJobs()}
 * covers the REJECTED-reactivation-enqueues-jobs scenario; this class covers the matching/isolation
 * scenarios that don't need the job-enqueue plumbing.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false"
})
class ArtistSeedServiceFlowTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "seed-flow-124@example.com";

    @Autowired
    private ArtistSeedService artistSeedService;

    @Autowired
    private ArtistRepository artistRepository;

    @BeforeEach
    void setUp() {
        // Shared container/context across this class's test methods (see
        // AbstractPostgresIntegrationTest and RelationDiscoveredFlowTest's identical note) -- clear
        // first so a prior method's committed rows don't collide on (owner, name).
        artistRepository.deleteAll();
    }

    @Test
    @DisplayName("issue #124: an en-dash/hyphen punctuation variant of an existing SEED artist is "
            + "not duplicated, against the real DB")
    void enDashVariantOfExistingArtistIsNotDuplicated() {
        Artist existing = new Artist("Only Murders In The Building - Cast", ArtistSource.SEED_LIST,
                ArtistStatus.SEED, null, null);
        existing.setOwner(OWNER);
        artistRepository.save(existing);

        boolean added = artistSeedService.addSeedIfNew(OWNER, "Only Murders in the Building – Cast");

        assertThat(added).as("matched an already-active artist -- no new row, no-op").isFalse();
        assertThat(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.SEED))
                .as("still just the one original row").hasSize(1);
    }

    @Test
    @DisplayName("issue #124: two genuinely different artist names both get added as distinct SEED "
            + "rows -- the normalized-name guard must not over-merge")
    void distinctArtistsBothGetAdded() {
        assertThat(artistSeedService.addSeedIfNew(OWNER, "Radiohead")).isTrue();
        assertThat(artistSeedService.addSeedIfNew(OWNER, "Radioheads Tribute Band")).isTrue();

        assertThat(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.SEED))
                .extracting(Artist::getName)
                .containsExactlyInAnyOrder("Radiohead", "Radioheads Tribute Band");
    }

    @Test
    @DisplayName("issue #124: owner isolation -- one owner's artist doesn't block another owner "
            + "from adding the same name")
    void ownerIsolation() {
        String otherOwner = "other-seed-flow-124@example.com";
        Artist existing = new Artist("Foo Bar", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        existing.setOwner(OWNER);
        artistRepository.save(existing);

        boolean added = artistSeedService.addSeedIfNew(otherOwner, "Foo Bar");

        assertThat(added).as("owner B is unaffected by owner A's existing artist of the same name")
                .isTrue();
        assertThat(artistRepository.findByOwnerAndStatus(otherOwner, ArtistStatus.SEED))
                .extracting(Artist::getName).containsExactly("Foo Bar");
        assertThat(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.SEED))
                .as("owner A's original row is untouched").hasSize(1);
    }
}
