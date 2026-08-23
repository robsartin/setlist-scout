package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.scan.source.TicketmasterShowSource;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SearchSettingsRepository;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Real-path Testcontainers proof of issue #254: {@link ScanUnitRunner} publishes
 * {@code ArtistShowsFound} inside a committed transaction, and the real
 * {@code expansion.ExpandJobListener} (an {@code @ApplicationModuleListener}) enqueues the
 * artist's expand jobs.
 * <p>
 * A Modulith {@code Scenario} test would be a false green here (CLAUDE.md): it wraps the publish in
 * its own transaction, so it passes even when the production publish site never commits.
 * {@code ScanPoller} deliberately runs {@code ScanUnitRunner#run} with NO ambient transaction, so
 * the {@code TransactionTemplate} around the publish is the only thing making the event survive at
 * all -- drop it and this flow is architecturally complete and functionally dead, the shape of
 * #211 and #230. Only a test that drives the real runner catches that.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false"
})
class ArtistShowsFoundFlowTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "shows-found-flow@example.com";

    @Autowired
    private ScanUnitRunner scanUnitRunner;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ExpandJobRepository expandJobRepository;

    @Autowired
    private SearchSettingsRepository settingsRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TicketmasterShowSource ticketmasterShowSource;

    private Long approvedArtistId;

    @BeforeEach
    void setUp() {
        expandJobRepository.deleteAll();
        artistRepository.deleteAll();
        settingsRepository.deleteAll();

        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings.setPostalCode("78701");
        settingsRepository.save(settings);

        // APPROVED, not SEED: the population #254 is about. On activation this artist gets no
        // expand jobs at all -- it has to earn them by turning up a show.
        Artist approved = new Artist("Shows Found Flow Artist", ArtistSource.SIMILAR_EXPANSION,
                ArtistStatus.APPROVED, null, null);
        approved.setOwner(OWNER);
        approvedArtistId = artistRepository.save(approved).getId();

        when(ticketmasterShowSource.id()).thenReturn("ticketmaster");
    }

    @Test
    @DisplayName("a scan that finds a show enqueues the artist's expand jobs, via the real listener (#254)")
    void aFoundShowEarnsExpansion() {
        when(ticketmasterShowSource.search(any())).thenReturn(List.of(show()));

        scanUnitRunner.run(OWNER, approvedArtistId, "ticketmaster");

        List<?> jobs = awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(OWNER, approvedArtistId),
                l -> !l.isEmpty());
        assertThat(jobs)
                .as("expand jobs enqueued by the real ExpandJobListener after a committed publish")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a scan that finds nothing enqueues no expand jobs -- the gate itself (#254)")
    void anEmptyScanEarnsNothing() {
        when(ticketmasterShowSource.search(any())).thenReturn(List.of());

        scanUnitRunner.run(OWNER, approvedArtistId, "ticketmaster");

        awaitQuiescence(jdbcTemplate);
        assertThat(expandJobRepository.findByOwnerAndArtistId(OWNER, approvedArtistId))
                .as("an artist that produced no shows must not expand")
                .isEmpty();
    }

    private static Show show() {
        return new Show("Shows Found Flow Artist", LocalDateTime.now().plusDays(30),
                "Some Venue", "Austin", null, "ticketmaster", null, Show.Kind.MUSIC);
    }
}
