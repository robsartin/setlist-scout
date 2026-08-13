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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level TDD for the poller's claim -> run -> reschedule-or-backoff loop (Phase B PR4a, Task
 * 6): {@link ScanJobRepository} and {@link ScanUnitRunner} are mocked, so this never touches a
 * real database -- {@code ScanJobRepositoryTest} already covers {@code claimDue} against real
 * Postgres, and a full real-path poller test is the next task. This is deliberately still gated
 * off by default in production ({@code @ConditionalOnProperty}); see
 * {@code PollerConditionalWiringTest} for that half.
 */
@ExtendWith(MockitoExtension.class)
class ScanPollerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 7L;
    private static final String SOURCE = "ticketmaster";

    @Mock private ScanJobRepository scanJobRepository;
    @Mock private ScanUnitRunner scanUnitRunner;

    private PollerProperties properties;
    private ScanPoller poller;

    @BeforeEach
    void setUp() {
        properties = new PollerProperties(
                20, 20, Duration.ofMinutes(5).toMillis(),
                Duration.ofDays(14), Duration.ofDays(28), 6, Map.of());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        poller = new ScanPoller(scanJobRepository, scanUnitRunner, properties, clock);
    }

    private static ScanJob job(int attempts) {
        ScanJob job = new ScanJob(ARTIST_ID, SOURCE, JobStatus.RUNNING, attempts, NOW, null);
        job.setOwner(OWNER);
        job.setClaimedAt(NOW);
        return job;
    }

    @Test
    @DisplayName("tick() claims with now/leaseCutoff/batch from PollerProperties")
    void ticksClaimsWithConfiguredWindowAndBatch() {
        when(scanJobRepository.claimDue(eq(NOW), eq(NOW.minus(Duration.ofMinutes(5))), eq(20)))
                .thenReturn(List.of());

        poller.tick();

        verify(scanJobRepository).claimDue(NOW, NOW.minus(Duration.ofMinutes(5)), 20);
    }

    @Test
    @DisplayName("a successful unit run reschedules: attempts=0, claimedAt=null, SCHEDULED, nextDueAt = now + interval")
    void successReschedules() {
        ScanJob job = job(2); // was already mid-backoff; success should reset it
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenReturn(3);

        poller.tick();

        assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getClaimedAt()).isNull();
        assertThat(job.getLastRunAt()).isEqualTo(NOW);
        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        verify(scanJobRepository).save(job);
    }

    @Test
    @DisplayName("a thrown RuntimeException backs off: attempts++, lastError set (truncated to 255), FAILED, nextDueAt backed off")
    void failureBacksOff() {
        ScanJob job = job(0);
        String longMessage = "boom: " + "x".repeat(300);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenThrow(new RuntimeException(longMessage));

        poller.tick();

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getClaimedAt()).isNull();
        assertThat(job.getLastError()).hasSize(255).isEqualTo(longMessage.substring(0, 255));
        // attempts=1 -> 10m * 2^1 = 20m, well under the 14d interval cap
        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(20)));
        verify(scanJobRepository).save(job);
    }

    @Test
    @DisplayName("a null exception message doesn't blow up truncation")
    void failureWithNullMessageIsHandled() {
        ScanJob job = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenThrow(new RuntimeException());

        poller.tick();

        assertThat(job.getLastError()).isNull();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    @DisplayName("parks at the cap: attempts reaching pollerParkCap pushes nextDueAt a full interval out, not further up the backoff ladder")
    void parksAtCap() {
        ScanJob job = job(5); // one more failure reaches the cap of 6
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenThrow(new RuntimeException("still broken"));

        poller.tick();

        assertThat(job.getAttempts()).isEqualTo(6);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getClaimedAt()).isNull();
        // Parked: pushed a full interval out (14d), not the 10m*2^6=640m the ladder would give.
        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    }

    @Test
    @DisplayName("a per-source interval override wins over the default scanInterval")
    void perSourceIntervalOverrideWins() {
        properties = new PollerProperties(
                20, 20, Duration.ofMinutes(5).toMillis(),
                Duration.ofDays(14), Duration.ofDays(28), 6, Map.of(SOURCE, Duration.ofDays(3)));
        poller = new ScanPoller(scanJobRepository, scanUnitRunner, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        ScanJob job = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenReturn(0);

        poller.tick();

        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("each claimed job is saved individually, even when one of several fails")
    void eachClaimedJobHandledIndependently() {
        ScanJob ok = job(0);
        ScanJob failing = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(ok, failing));
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenReturn(0).thenThrow(new RuntimeException("nope"));

        poller.tick();

        assertThat(ok.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(failing.getStatus()).isEqualTo(JobStatus.FAILED);
        verify(scanJobRepository).save(ok);
        verify(scanJobRepository).save(failing);
    }
}
