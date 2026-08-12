package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.CandidateDiscovered;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One end-to-end proof that the durable event path actually works: publishing
 * {@link CandidateDiscovered} -- as expansion does after PR3a's switch -- results in a
 * PENDING_REVIEW {@link Artist} persisted by {@link CandidatePersistenceListener}, via the
 * real async, JPA-durable {@code @ApplicationModuleListener} delivery (not a direct method call).
 * The guard/dedup branches themselves are unit-tested in {@link CandidatePersistenceListenerTest}.
 */
@ApplicationModuleTest
@Testcontainers
class CandidateDiscoveredFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "flow-test@example.com";

    @Autowired
    private ArtistRepository artistRepository;

    @Test
    @DisplayName("publishing CandidateDiscovered persists a PENDING_REVIEW Artist via the durable listener")
    void publishingCandidateDiscoveredPersistsAPendingReviewArtist(Scenario scenario) {
        CandidateDiscovered event = new CandidateDiscovered(
                OWNER, "Some Real Band", "MEMBER_EXPANSION", "Base Artist",
                "member/lineup relation of Base Artist");

        scenario.publish(event)
                .andWaitForStateChange(() -> artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .andVerify(artists -> assertThat(artists)
                        .extracting(Artist::getName)
                        .contains("Some Real Band"));
    }
}
