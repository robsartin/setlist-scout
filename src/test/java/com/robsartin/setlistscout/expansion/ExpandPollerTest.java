package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.PollerProperties;
import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level TDD for {@link ExpandPoller}'s claim -> resolve-name -> run -> reschedule-or-backoff
 * loop (Phase B PR4a, Task 6). Mirrors {@code ScanPollerTest}; see its class doc for what's
 * intentionally out of scope here (real DB, the disabled-by-default wiring -- that's
 * {@code PollerConditionalWiringTest}).
 */
@ExtendWith(MockitoExtension.class)
class ExpandPollerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 7L;
    private static final String SOURCE = "lastfm";
    private static final String ARTIST_NAME = "Dawes";

    @Mock private ExpandJobRepository expandJobRepository;
    @Mock private ExpandUnitRunner expandUnitRunner;
    @Mock private ArtistRepository artistRepository;

    private PollerProperties properties;
    private ExpandPoller poller;

    @BeforeEach
    void setUp() {
        properties = new PollerProperties(
                20, 20, Duration.ofMinutes(5).toMillis(),
                Duration.ofDays(14), Duration.ofDays(28), 6, Map.of(), true, Duration.ofHours(2));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        poller = new ExpandPoller(expandJobRepository, expandUnitRunner, artistRepository, properties, clock);
    }

    private static ExpandJob job(int attempts) {
        ExpandJob job = new ExpandJob(ARTIST_ID, SOURCE, JobStatus.RUNNING, attempts, NOW);
        job.setOwner(OWNER);
        job.setClaimedAt(NOW);
        return job;
    }

    private static Artist artist() {
        Artist artist = new Artist(ARTIST_NAME, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(OWNER);
        return artist;
    }

    @Test
    @DisplayName("tick() claims with now/leaseCutoff/batch from PollerProperties")
    void ticksClaimsWithConfiguredWindowAndBatch() {
        when(expandJobRepository.claimDue(eq(NOW), eq(NOW.minus(Duration.ofMinutes(5))), eq(20)))
                .thenReturn(List.of());

        poller.tick();

        verify(expandJobRepository).claimDue(NOW, NOW.minus(Duration.ofMinutes(5)), 20);
    }

    @Test
    @DisplayName("a successful unit run resolves the artist name, runs, and reschedules: attempts=0, claimedAt=null, SCHEDULED, nextDueAt = now + interval")
    void successReschedules() {
        ExpandJob job = job(2);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));

        poller.tick();

        verify(expandUnitRunner).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getClaimedAt()).isNull();
        assertThat(job.getLastRunAt()).isEqualTo(NOW);
        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofDays(28)));
        verify(expandJobRepository).save(job);
    }

    @Test
    @DisplayName("a missing artist is skipped (no unit run) but still counted as a successful reschedule, not a failure")
    void missingArtistSkipsRunButStillReschedules() {
        ExpandJob job = job(0);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.empty());

        poller.tick();

        verify(expandUnitRunner, never()).run(any(), any(), any(), any());
        assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofDays(28)));
    }

    @Test
    @DisplayName("a thrown RuntimeException backs off: attempts++, lastError set (truncated to 8000), FAILED, nextDueAt backed off")
    void failureBacksOff() {
        ExpandJob job = job(0);
        String longMessage = "boom: " + "x".repeat(8500);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));
        doThrow(new RuntimeException(longMessage)).when(expandUnitRunner).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);

        poller.tick();

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getClaimedAt()).isNull();
        assertThat(job.getLastError()).hasSize(8000).isEqualTo(longMessage.substring(0, 8000));
        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(20)));
        verify(expandJobRepository).save(job);
    }

    @Test
    @DisplayName("parks at the cap: attempts reaching pollerParkCap pushes nextDueAt a full interval out, not further up the backoff ladder")
    void parksAtCap() {
        ExpandJob job = job(5);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));
        doThrow(new RuntimeException("still broken")).when(expandUnitRunner).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);

        poller.tick();

        assertThat(job.getAttempts()).isEqualTo(6);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getClaimedAt()).isNull();
        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofDays(28)));
    }

    @Test
    @DisplayName("a per-source interval override wins over the default expansionInterval")
    void perSourceIntervalOverrideWins() {
        properties = new PollerProperties(
                20, 20, Duration.ofMinutes(5).toMillis(),
                Duration.ofDays(14), Duration.ofDays(28), 6, Map.of(SOURCE, Duration.ofDays(7)), true, Duration.ofHours(2));
        poller = new ExpandPoller(expandJobRepository, expandUnitRunner, artistRepository, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ExpandJob job = job(0);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));

        poller.tick();

        assertThat(job.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("a reschedule that loses an optimistic-lock race is swallowed; the tick continues")
    void concurrentRedueDuringRunIsSkipped() {
        ExpandJob job = job(0);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));
        // The unit runs fine, but the reschedule save loses to a concurrent redueAll:
        when(expandJobRepository.save(job))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ExpandJob.class, 1L));

        // Must not propagate out of tick():
        assertThatCode(() -> poller.tick()).doesNotThrowAnyException();
        verify(expandUnitRunner).run(job.getOwner(), job.getArtistId(), job.getSource(), ARTIST_NAME);
    }

    @Test
    @DisplayName("a conflict on one claimed job doesn't stop the rest of the batch from being processed")
    void conflictOnOneJobDoesNotSkipRestOfBatch() {
        ExpandJob conflicting = job(0);
        ExpandJob ok = job(0);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(conflicting, ok));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));
        when(expandJobRepository.save(conflicting))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ExpandJob.class, 1L));

        assertThatCode(() -> poller.tick()).doesNotThrowAnyException();

        verify(expandUnitRunner, times(2)).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);
        verify(expandJobRepository).save(ok);
        assertThat(ok.getStatus()).isEqualTo(JobStatus.SCHEDULED);
    }

    @Test
    @DisplayName("a failure reschedule that loses an optimistic-lock race is swallowed; the tick continues")
    void concurrentRedueDuringFailureRescheduleIsSkipped() {
        ExpandJob job = job(0);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));
        // The unit fails, and the failure-path reschedule save loses to a concurrent redueAll:
        doThrow(new RuntimeException("boom")).when(expandUnitRunner).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);
        when(expandJobRepository.save(job))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ExpandJob.class, 1L));

        // Must not propagate out of tick():
        assertThatCode(() -> poller.tick()).doesNotThrowAnyException();
        verify(expandUnitRunner).run(job.getOwner(), job.getArtistId(), job.getSource(), ARTIST_NAME);
    }

    @Test
    @DisplayName("a conflict on the failure-path reschedule doesn't stop the rest of the batch")
    void conflictOnFailureRescheduleDoesNotSkipRestOfBatch() {
        ExpandJob conflicting = job(0);
        ExpandJob ok = job(0);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(conflicting, ok));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));
        doThrow(new RuntimeException("boom")).doNothing()
                .when(expandUnitRunner).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);
        when(expandJobRepository.save(conflicting))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ExpandJob.class, 1L));

        assertThatCode(() -> poller.tick()).doesNotThrowAnyException();

        verify(expandUnitRunner, times(2)).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);
        verify(expandJobRepository).save(ok);
        assertThat(ok.getStatus()).isEqualTo(JobStatus.SCHEDULED);
    }

    // -- #135: correlation id scoped around the ExpandUnitRunner.run call ------------------------

    @Test
    @DisplayName("#135: a valid cid is in MDC for the duration of the unit-runner call, and cleared once tick() returns")
    void unitRunnerCallCarriesAValidCidClearedAfterTick() {
        ExpandJob job = job(0);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));
        AtomicReference<String> cidDuring = new AtomicReference<>();
        doAnswer(invocation -> {
            cidDuring.set(MDC.get(Correlation.CID));
            return null;
        }).when(expandUnitRunner).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);

        poller.tick();

        assertThat(CorrelationIds.isValid(cidDuring.get()))
                .as("a valid cid was visible to the unit runner while it ran").isTrue();
        assertThat(MDC.get(Correlation.CID)).as("cleared once tick() returns").isNull();
    }

    @Test
    @DisplayName("#135: MDC is cleared after tick() returns even when the unit runner throws")
    void unitRunnerExceptionStillClearsMdcAfterTick() {
        ExpandJob job = job(0);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(job));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));
        AtomicReference<String> cidDuring = new AtomicReference<>();
        doAnswer(invocation -> {
            cidDuring.set(MDC.get(Correlation.CID));
            throw new RuntimeException("boom");
        }).when(expandUnitRunner).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);

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
        ExpandJob first = job(0);
        ExpandJob second = job(0);
        when(expandJobRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(first, second));
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist()));
        List<String> observedCids = new ArrayList<>();
        doAnswer(invocation -> {
            observedCids.add(MDC.get(Correlation.CID));
            return null;
        }).when(expandUnitRunner).run(OWNER, ARTIST_ID, SOURCE, ARTIST_NAME);

        poller.tick();

        assertThat(observedCids).hasSize(2);
        assertThat(observedCids.get(0)).isNotEqualTo(observedCids.get(1));
    }
}
