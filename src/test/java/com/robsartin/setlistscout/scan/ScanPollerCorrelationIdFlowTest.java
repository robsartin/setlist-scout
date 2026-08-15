package com.robsartin.setlistscout.scan;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.robsartin.setlistscout.PollerProperties;
import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSiteUrlService;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.scan.source.TicketmasterShowSource;
import com.robsartin.setlistscout.service.LogCapture;
import com.robsartin.setlistscout.service.TestAppProperties;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SearchSettingsRepository;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import com.robsartin.setlistscout.shared.observability.Correlation;
import com.robsartin.setlistscout.shared.observability.CorrelationIds;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #135 payoff test: proves the correlation id set around {@code ScanUnitRunner.run} (see
 * {@code ScanPoller#runOne}) actually reaches all the way down into a log line emitted by one of
 * the real external-service classes it calls into -- not just that the poller sets/clears MDC
 * around itself. Everything between {@link ScanPoller} and {@link TicketmasterService} is real
 * production wiring; only the DB-backed collaborators (no DB touches this class -- it's plain
 * Mockito, not a Testcontainers test) and the network are stubbed, the latter via a
 * {@link MockWebServer} the same way {@code TicketmasterServiceTest} does.
 */
class ScanPollerCorrelationIdFlowTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 7L;
    private static final String SOURCE = "ticketmaster";

    private MockWebServer server;
    private ScanPoller poller;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        TicketmasterService ticketmasterService =
                new TicketmasterService(TestAppProperties.withKeys(), server.url("/").toString());
        TicketmasterShowSource showSource = new TicketmasterShowSource(ticketmasterService);

        ArtistRepository artistRepository = mock(ArtistRepository.class);
        ArtistSiteUrlService artistSiteUrlService = mock(ArtistSiteUrlService.class);
        ShowRepository showRepository = mock(ShowRepository.class);
        SearchSettingsRepository settingsRepository = mock(SearchSettingsRepository.class);
        MusicBrainzService musicBrainz = mock(MusicBrainzService.class);

        Artist artist = new Artist("Dawes", ArtistSource.SEED_LIST, ArtistStatus.APPROVED, null, null);
        artist.setOwner(OWNER);
        artist.setOfficialSiteUrl("https://dawes.example"); // pre-set: skips the MusicBrainz lookup
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        settings.setPostalCode("78701");
        when(settingsRepository.findByOwner(OWNER)).thenReturn(Optional.of(settings));

        ScanUnitRunner scanUnitRunner = new ScanUnitRunner(List.of(showSource), artistRepository,
                artistSiteUrlService, showRepository, settingsRepository, musicBrainz);

        ScanJobRepository scanJobRepository = mock(ScanJobRepository.class);
        ScanJob job = new ScanJob(ARTIST_ID, SOURCE, JobStatus.RUNNING, 0, NOW);
        job.setOwner(OWNER);
        job.setClaimedAt(NOW);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));

        PollerProperties properties = new PollerProperties(
                20, 20, Duration.ofMinutes(5).toMillis(),
                Duration.ofDays(14), Duration.ofDays(28), 6, Map.of(), true, Duration.ofHours(2));
        poller = new ScanPoller(scanJobRepository, scanUnitRunner, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("#135: a poller-driven job run's correlation id reaches a real external-service "
            + "class's log line, not just the poller's own MDC scope")
    void logLineFromExternalServiceClassCarriesTheJobsCorrelationId() {
        // A failing call is the simplest reliable trigger for TicketmasterService's own WARN log
        // line (the catch block in #searchShows) -- see TicketmasterServiceTest#shouldReturnEmptyOnServerError.
        server.enqueue(new MockResponse().setResponseCode(500));

        try (LogCapture logs = LogCapture.attach(TicketmasterService.class)) {
            poller.tick();

            ILoggingEvent warnEvent = logs.events().stream()
                    .filter(e -> "show search failed".equals(e.getMessage()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "expected TicketmasterService to log a WARN for the failed search"));

            String cid = warnEvent.getMDCPropertyMap().get(Correlation.CID);
            assertThat(CorrelationIds.isValid(cid))
                    .as("the job's correlation id reached the external-service class's own MDC context")
                    .isTrue();
        }

        assertThat(MDC.get(Correlation.CID)).as("cleared once tick() returns").isNull();
    }
}
