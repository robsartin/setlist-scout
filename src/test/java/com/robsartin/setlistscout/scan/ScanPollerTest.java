package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.PollerProperties;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.observability.Correlation;
import com.robsartin.setlistscout.shared.observability.CorrelationIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.anyString;
import java.util.Set;
import static org.mockito.Mockito.never;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
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
    @Mock private SourceHealthService sourceHealth;

    private PollerProperties properties;
    private ScanPoller poller;

    @BeforeEach
    void setUp() {
        properties = new PollerProperties(
                20, 20, Duration.ofMinutes(5).toMillis(),
                Duration.ofDays(14), Duration.ofDays(28), 6, Map.of(), true, Duration.ofHours(2));
        // Healthy by default: Mockito's default boolean is FALSE, which would silently put every
        // test on #265's probe path and reschedule at PROBE_INTERVAL instead of the real interval.
        lenient().when(sourceHealth.isHealthy(anyString())).thenReturn(true);
        lenient().when(sourceHealth.unhealthySources()).thenReturn(Set.of());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        poller = new ScanPoller(scanJobRepository, scanUnitRunner, properties, sourceHealth, clock);
    }

    private static ScanJob job(int attempts) {
        ScanJob job = new ScanJob(ARTIST_ID, SOURCE, JobStatus.RUNNING, attempts, NOW);
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
    @DisplayName("a thrown RuntimeException backs off: attempts++, lastError set (truncated to 8000), FAILED, nextDueAt backed off")
    void failureBacksOff() {
        ScanJob job = job(0);
        String longMessage = "boom: " + "x".repeat(8500);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenThrow(new RuntimeException(longMessage));

        poller.tick();

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getClaimedAt()).isNull();
        assertThat(job.getLastError()).hasSize(8000).isEqualTo(longMessage.substring(0, 8000));
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
                Duration.ofDays(14), Duration.ofDays(28), 6, Map.of(SOURCE, Duration.ofDays(3)), true, Duration.ofHours(2));
        poller = new ScanPoller(scanJobRepository, scanUnitRunner, properties, sourceHealth,
                Clock.fixed(NOW, ZoneOffset.UTC));
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

    @Test
    @DisplayName("a reschedule that loses an optimistic-lock race is swallowed; the tick continues")
    void concurrentRedueDuringRunIsSkipped() {
        ScanJob job = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        // The unit runs fine, but the reschedule save loses to a concurrent redueAll:
        when(scanJobRepository.save(job))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ScanJob.class, 1L));

        // Must not propagate out of tick():
        assertThatCode(() -> poller.tick()).doesNotThrowAnyException();
        verify(scanUnitRunner).run(job.getOwner(), job.getArtistId(), job.getSource());
    }

    @Test
    @DisplayName("a conflict on one claimed job doesn't stop the rest of the batch from being processed")
    void conflictOnOneJobDoesNotSkipRestOfBatch() {
        ScanJob conflicting = job(0);
        ScanJob ok = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(conflicting, ok));
        when(scanJobRepository.save(conflicting))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ScanJob.class, 1L));

        assertThatCode(() -> poller.tick()).doesNotThrowAnyException();

        verify(scanUnitRunner, times(2)).run(OWNER, ARTIST_ID, SOURCE);
        verify(scanJobRepository).save(ok);
        assertThat(ok.getStatus()).isEqualTo(JobStatus.SCHEDULED);
    }

    @Test
    @DisplayName("a failure reschedule that loses an optimistic-lock race is swallowed; the tick continues")
    void concurrentRedueDuringFailureRescheduleIsSkipped() {
        ScanJob job = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        // The unit fails, and the failure-path reschedule save loses to a concurrent redueAll:
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenThrow(new RuntimeException("boom"));
        when(scanJobRepository.save(job))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ScanJob.class, 1L));

        // Must not propagate out of tick():
        assertThatCode(() -> poller.tick()).doesNotThrowAnyException();
        verify(scanUnitRunner).run(job.getOwner(), job.getArtistId(), job.getSource());
    }

    @Test
    @DisplayName("a conflict on the failure-path reschedule doesn't stop the rest of the batch")
    void conflictOnFailureRescheduleDoesNotSkipRestOfBatch() {
        ScanJob conflicting = job(0);
        ScanJob ok = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(conflicting, ok));
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(0);
        when(scanJobRepository.save(conflicting))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ScanJob.class, 1L));

        assertThatCode(() -> poller.tick()).doesNotThrowAnyException();

        verify(scanUnitRunner, times(2)).run(OWNER, ARTIST_ID, SOURCE);
        verify(scanJobRepository).save(ok);
        assertThat(ok.getStatus()).isEqualTo(JobStatus.SCHEDULED);
    }

    // -- #135: correlation id scoped around the ScanUnitRunner.run call --------------------------

    @Test
    @DisplayName("#135: a valid cid is in MDC for the duration of the unit-runner call, and cleared once tick() returns")
    void unitRunnerCallCarriesAValidCidClearedAfterTick() {
        ScanJob job = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        AtomicReference<String> cidDuring = new AtomicReference<>();
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenAnswer(invocation -> {
            cidDuring.set(MDC.get(Correlation.CID));
            return 0;
        });

        poller.tick();

        assertThat(CorrelationIds.isValid(cidDuring.get()))
                .as("a valid cid was visible to the unit runner while it ran").isTrue();
        assertThat(MDC.get(Correlation.CID)).as("cleared once tick() returns").isNull();
    }

    @Test
    @DisplayName("#135: MDC is cleared after tick() returns even when the unit runner throws")
    void unitRunnerExceptionStillClearsMdcAfterTick() {
        ScanJob job = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        AtomicReference<String> cidDuring = new AtomicReference<>();
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenAnswer(invocation -> {
            cidDuring.set(MDC.get(Correlation.CID));
            throw new RuntimeException("boom");
        });

        poller.tick();

        assertThat(CorrelationIds.isValid(cidDuring.get()))
                .as("a valid cid was visible to the unit runner even on the failing path").isTrue();
        assertThat(MDC.get(Correlation.CID))
                .as("the job-failure path must not leak a stale cid to whatever runs next on this thread")
                .isNull();
    }

    @Test
    @DisplayName("#135: two jobs claimed in the same tick each get a different cid, not one reused")
    void eachClaimedJobGetsItsOwnCid() {
        ScanJob first = job(0);
        ScanJob second = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(first, second));
        List<String> observedCids = new ArrayList<>();
        when(scanUnitRunner.run(OWNER, ARTIST_ID, SOURCE)).thenAnswer(invocation -> {
            observedCids.add(MDC.get(Correlation.CID));
            return 0;
        });

        poller.tick();

        assertThat(observedCids).hasSize(2);
        assertThat(observedCids.get(0)).isNotEqualTo(observedCids.get(1));
    }

    // ---- #265: a source detected dead ----

    @Test
    @DisplayName("issue #265: a dead source is EXCLUDED from the normal claim -- 3,252 jobs that "
            + "cannot succeed must not fill every batch and starve the sources that still work")
    void aDeadSourceIsExcludedFromTheNormalClaim() {
        when(sourceHealth.unhealthySources()).thenReturn(Set.of("bandsintown"));
        when(scanJobRepository.claimDueExcludingSources(any(), any(), anyInt(), any()))
                .thenReturn(List.of());
        when(scanJobRepository.claimProbe(any(), any(), anyInt(), eq("bandsintown")))
                .thenReturn(List.of());

        poller.tick();

        verify(scanJobRepository).claimDueExcludingSources(eq(NOW), any(), eq(20),
                eq(Set.of("bandsintown")));
        verify(scanJobRepository, never()).claimDue(any(), any(), anyInt());
    }

    @Test
    @DisplayName("issue #265: a dead source still gets exactly ONE probe per tick -- without it the "
            + "source could never prove it recovered, and would stay excluded forever")
    void aDeadSourceStillGetsOneProbe() {
        ScanJob probe = job(0);
        when(sourceHealth.unhealthySources()).thenReturn(Set.of("bandsintown"));
        when(scanJobRepository.claimDueExcludingSources(any(), any(), anyInt(), any()))
                .thenReturn(List.of());
        when(scanJobRepository.claimProbe(any(), any(), anyInt(), eq("bandsintown")))
                .thenReturn(List.of(probe));

        poller.tick();

        verify(scanJobRepository).claimProbe(eq(NOW), any(), eq(ScanPoller.PROBE_BATCH),
                eq("bandsintown"));
        verify(scanUnitRunner).run(OWNER, ARTIST_ID, SOURCE);
    }

    @Test
    @DisplayName("issue #265: every source healthy means the plain claim, because Postgres treats "
            + "NOT IN () as a syntax error rather than a no-op")
    void allHealthyUsesThePlainClaim() {
        when(sourceHealth.unhealthySources()).thenReturn(Set.of());
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of());

        poller.tick();

        verify(scanJobRepository).claimDue(eq(NOW), any(), eq(20));
        verify(scanJobRepository, never()).claimDueExcludingSources(any(), any(), anyInt(), any());
    }

    /**
     * The half of the 2026-08-25 outage that cost the most. Every 403 was recorded as a success and
     * pushed a full 14-day interval out, so once the fleet had drained once, nothing would retry
     * Bandsintown for a fortnight -- even after the credential was fixed.
     */
    @Test
    @DisplayName("issue #265: while its source is down a job re-dues in MINUTES, not the full "
            + "interval -- otherwise nothing retries for 14 days after the credential is fixed")
    void aDeadSourcesJobRedeuesAtTheProbeInterval() {
        when(sourceHealth.isHealthy(SOURCE)).thenReturn(false);
        ScanJob job = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));

        poller.tick();

        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(ScanPoller.PROBE_INTERVAL));
        assertThat(job.getNextDueAt()).isBefore(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("issue #265: a healthy source's job still re-dues at its normal interval")
    void aHealthySourcesJobKeepsTheNormalInterval() {
        ScanJob job = job(0);
        when(scanJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));

        poller.tick();

        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    }
}
