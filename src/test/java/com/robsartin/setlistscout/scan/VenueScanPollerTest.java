package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.PollerProperties;
import com.robsartin.setlistscout.shared.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level TDD for {@link VenueScanPoller}'s claim -> dispatch loop (#206 Task 3). Mirrors
 * {@code catalog.ArtistImportPollerTest}'s shape: {@link VenueScanJobRepository} and {@link
 * VenueScanRunner} are mocked, so this never touches a real database -- {@code
 * VenueScanJobRepositoryTest} (#206 Task 2) already covers {@code claimDue} against real Postgres,
 * and {@code VenueScanRunnerTest} (#206 Task 3) already covers the runner's own scrape/persist/
 * bookkeeping contract exhaustively.
 * <p>
 * Unlike {@code ArtistImportPoller}/{@code ScanPoller}, success/failure bookkeeping is NOT this
 * poller's job -- {@code VenueScanRunner#run} owns it entirely and never throws (see its own
 * javadoc, proven by {@code VenueScanRunnerTest.recordsScraperFailure}), so this class only needs
 * to prove the poller claims with the right arguments and dispatches every claimed row to the
 * runner -- not re-prove the runner's own outcome-recording behavior.
 */
@ExtendWith(MockitoExtension.class)
class VenueScanPollerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final String OWNER = "rob@example.com";
    private static final int BATCH_SIZE = 25;

    @Mock
    private VenueScanJobRepository venueScanJobRepository;

    @Mock
    private VenueScanRunner venueScanRunner;

    private PollerProperties properties;
    private VenueScanPoller poller;

    @BeforeEach
    void setUp() {
        properties = new PollerProperties(
                20, 20, Duration.ofMinutes(5).toMillis(),
                Duration.ofDays(14), Duration.ofDays(28), 6, Map.of(), true, Duration.ofHours(2));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        poller = new VenueScanPoller(venueScanJobRepository, venueScanRunner, properties, BATCH_SIZE, clock);
    }

    private static VenueScanJob row(String owner, Long venueId) {
        return new VenueScanJob(owner, venueId, JobStatus.SCHEDULED, 0, NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("tick() claims with now/leaseCutoff/batch from configuration")
    void ticksClaimsWithConfiguredWindowAndBatch() {
        when(venueScanJobRepository.claimDue(eq(NOW), eq(NOW.minus(Duration.ofMinutes(5))), eq(BATCH_SIZE)))
                .thenReturn(List.of());

        poller.tick();

        verify(venueScanJobRepository).claimDue(NOW, NOW.minus(Duration.ofMinutes(5)), BATCH_SIZE);
    }

    @Test
    @DisplayName("each claimed row is handed to the runner")
    void dispatchesEachClaimedRowToTheRunner() {
        VenueScanJob job = row(OWNER, 1L);
        when(venueScanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));

        poller.tick();

        verify(venueScanRunner).run(job);
    }

    @Test
    @DisplayName("batch limit respected: claimDue is asked for exactly the configured batch size, "
            + "and every row it returns reaches the runner")
    void batchLimitRespectedAndEveryRowDispatched() {
        int smallBatch = 2;
        poller = new VenueScanPoller(venueScanJobRepository, venueScanRunner, properties,
                smallBatch, Clock.fixed(NOW, ZoneOffset.UTC));
        VenueScanJob first = row(OWNER, 1L);
        VenueScanJob second = row(OWNER, 2L);
        when(venueScanJobRepository.claimDue(eq(NOW), any(), eq(smallBatch)))
                .thenReturn(List.of(first, second));

        poller.tick();

        verify(venueScanJobRepository).claimDue(eq(NOW), any(), eq(smallBatch));
        verify(venueScanRunner).run(first);
        verify(venueScanRunner).run(second);
    }

    @Test
    @DisplayName("no claimed rows means the runner is never invoked")
    void noClaimedRowsMeansNoRunnerCalls() {
        when(venueScanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of());

        poller.tick();

        verify(venueScanRunner, never()).run(any());
    }

    @Test
    @DisplayName("owner isolation: rows for two different owners are each still individually dispatched")
    void ownerIsolation() {
        VenueScanJob rowA = row(OWNER, 1L);
        VenueScanJob rowB = row("other@example.com", 2L);
        when(venueScanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(rowA, rowB));

        poller.tick();

        verify(venueScanRunner).run(rowA);
        verify(venueScanRunner).run(rowB);
    }

    @Test
    @DisplayName("a RuntimeException escaping the runner for one row does not stop the remaining rows in the batch")
    void oneRunnerFailureDoesNotStopTheBatch() {
        VenueScanJob ok = row(OWNER, 1L);
        VenueScanJob throwing = row(OWNER, 2L);
        when(venueScanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(throwing, ok));
        doThrow(new RuntimeException("unexpected")).when(venueScanRunner).run(throwing);

        assertThat(catchThrowable(() -> poller.tick())).isNull();

        verify(venueScanRunner).run(throwing);
        verify(venueScanRunner).run(ok);
    }
}
