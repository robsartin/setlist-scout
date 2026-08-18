package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.PollerProperties;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level TDD for {@link ArtistImportPoller}'s claim -> seed -> DONE-or-backoff loop (#177,
 * Task 2). Mirrors {@code scan.ScanPollerTest}'s shape: {@link ArtistImportRepository} and
 * {@link ArtistSeedService} are mocked, so this never touches a real database --
 * {@code ArtistImportRepositoryTest} already covers {@code claimDue} against real Postgres.
 * <p>
 * {@code addSeedIfNew} is mocked at its own boundary here, the same way {@code ScanUnitRunner} is
 * mocked in {@code ScanPollerTest} rather than wiring the real unit runner against a mocked
 * {@code ShowRepository}: its return-value/exception contract is exhaustively covered by
 * {@code ArtistSeedServiceTest}, so this class only needs to prove the POLLER reacts correctly to
 * that contract (true, false, and thrown) -- not re-prove the service's own internals.
 */
@ExtendWith(MockitoExtension.class)
class ArtistImportPollerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final String OWNER = "rob@example.com";
    private static final String OTHER_OWNER = "david@example.com";
    private static final String NAME = "Wilco";
    private static final int BATCH_SIZE = 25;
    private static final int MAX_ATTEMPTS = 3;

    @Mock private ArtistImportRepository artistImportRepository;
    @Mock private ArtistSeedService artistSeedService;

    private PollerProperties properties;
    private ArtistImportPoller poller;

    @BeforeEach
    void setUp() {
        properties = new PollerProperties(
                20, 20, Duration.ofMinutes(5).toMillis(),
                Duration.ofDays(14), Duration.ofDays(28), 6, Map.of(), true, Duration.ofHours(2));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        poller = new ArtistImportPoller(artistImportRepository, artistSeedService, properties,
                BATCH_SIZE, MAX_ATTEMPTS, clock);
    }

    private static ArtistImport row(String owner, String name, int attempts) {
        ArtistImport row = new ArtistImport(owner, name, ArtistNameNormalizer.normalize(name));
        row.setAttempts(attempts);
        row.setClaimedAt(NOW);
        row.setNextDueAt(NOW.minusSeconds(60));
        return row;
    }

    private static ArtistImport row(int attempts) {
        return row(OWNER, NAME, attempts);
    }

    @Test
    @DisplayName("tick() claims with now/leaseCutoff/batch from configuration")
    void ticksClaimsWithConfiguredWindowAndBatch() {
        when(artistImportRepository.claimDue(eq(NOW), eq(NOW.minus(Duration.ofMinutes(5))), eq(BATCH_SIZE)))
                .thenReturn(List.of());

        poller.tick();

        verify(artistImportRepository).claimDue(NOW, NOW.minus(Duration.ofMinutes(5)), BATCH_SIZE);
    }

    @Test
    @DisplayName("happy path: a claimed PENDING row is seeded and becomes DONE")
    void happyPathSeedsAndMarksDone() {
        ArtistImport row = row(0);
        when(artistImportRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(row));
        when(artistSeedService.addSeedIfNew(OWNER, NAME)).thenReturn(true);

        poller.tick();

        verify(artistSeedService).addSeedIfNew(OWNER, NAME);
        assertThat(row.getStatus()).isEqualTo(ArtistImportStatus.DONE);
        assertThat(row.getClaimedAt()).isNull();
        verify(artistImportRepository).save(row);
    }

    @Test
    @DisplayName("a name that already matched an active artist still ends DONE -- addSeedIfNew "
            + "returning false is a legitimate no-op, not a failure")
    void alreadyActiveNameStillMarksDone() {
        ArtistImport row = row(0);
        when(artistImportRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(row));
        when(artistSeedService.addSeedIfNew(OWNER, NAME)).thenReturn(false);

        poller.tick();

        assertThat(row.getStatus()).as("false is a legitimate no-op, not a failure")
                .isEqualTo(ArtistImportStatus.DONE);
        assertThat(row.getClaimedAt()).isNull();
        assertThat(row.getAttempts()).as("not treated as a failed attempt").isZero();
        assertThat(row.getLastError()).isNull();
        verify(artistImportRepository).save(row);
    }

    @Test
    @DisplayName("batch limit respected: claimDue is asked for exactly the configured batch size, "
            + "and every row it returns is processed")
    void batchLimitRespected() {
        int smallBatch = 2;
        poller = new ArtistImportPoller(artistImportRepository, artistSeedService, properties,
                smallBatch, MAX_ATTEMPTS, Clock.fixed(NOW, ZoneOffset.UTC));
        ArtistImport first = row(OWNER, "Wilco", 0);
        ArtistImport second = row(OWNER, "Dawes", 0);
        when(artistImportRepository.claimDue(eq(NOW), any(), eq(smallBatch)))
                .thenReturn(List.of(first, second));
        when(artistSeedService.addSeedIfNew(eq(OWNER), any())).thenReturn(true);

        poller.tick();

        verify(artistImportRepository).claimDue(eq(NOW), any(), eq(smallBatch));
        assertThat(first.getStatus()).isEqualTo(ArtistImportStatus.DONE);
        assertThat(second.getStatus()).isEqualTo(ArtistImportStatus.DONE);
    }

    @Test
    @DisplayName("failure retries: a thrown exception backs off -- attempts++, lastError set "
            + "(truncated to 8000), claimedAt cleared, back to PENDING with nextDueAt in the future")
    void failureRetriesBelowCap() {
        ArtistImport row = row(0);
        String longMessage = "boom: " + "x".repeat(8500);
        when(artistImportRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(row));
        when(artistSeedService.addSeedIfNew(OWNER, NAME)).thenThrow(new RuntimeException(longMessage));

        poller.tick();

        assertThat(row.getStatus()).isEqualTo(ArtistImportStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getClaimedAt()).isNull();
        assertThat(row.getLastError()).hasSize(8000).isEqualTo(longMessage.substring(0, 8000));
        // attempts=1 -> 10m * 2^1 = 20m
        assertThat(row.getNextDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(20)));
        verify(artistImportRepository).save(row);
    }

    @Test
    @DisplayName("retry cap: once attempts reaches the cap, the row goes FAILED (terminal) instead "
            + "of back to PENDING")
    void retryCapReachesFailed() {
        ArtistImport row = row(MAX_ATTEMPTS - 1); // one more failure reaches the cap
        when(artistImportRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(row));
        when(artistSeedService.addSeedIfNew(OWNER, NAME)).thenThrow(new RuntimeException("still broken"));

        poller.tick();

        assertThat(row.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(row.getStatus()).as("terminal -- claimDue only ever selects PENDING rows, so a "
                + "FAILED row can never be claimed again").isEqualTo(ArtistImportStatus.FAILED);
        assertThat(row.getClaimedAt()).isNull();
        verify(artistImportRepository).save(row);
    }

    @Test
    @DisplayName("owner isolation: a row for owner A is only ever seeded for owner A, never for "
            + "owner B")
    void ownerIsolation() {
        ArtistImport rowA = row(OWNER, "Wilco", 0);
        ArtistImport rowB = row(OTHER_OWNER, "Dawes", 0);
        when(artistImportRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(rowA, rowB));
        when(artistSeedService.addSeedIfNew(any(), any())).thenReturn(true);

        poller.tick();

        verify(artistSeedService).addSeedIfNew(OWNER, "Wilco");
        verify(artistSeedService).addSeedIfNew(OTHER_OWNER, "Dawes");
        verify(artistSeedService, never()).addSeedIfNew(OWNER, "Dawes");
        verify(artistSeedService, never()).addSeedIfNew(OTHER_OWNER, "Wilco");
        assertThat(rowA.getStatus()).isEqualTo(ArtistImportStatus.DONE);
        assertThat(rowB.getStatus()).isEqualTo(ArtistImportStatus.DONE);
    }

    @Test
    @DisplayName("a null exception message doesn't blow up truncation")
    void failureWithNullMessageIsHandled() {
        ArtistImport row = row(0);
        when(artistImportRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(row));
        when(artistSeedService.addSeedIfNew(OWNER, NAME)).thenThrow(new RuntimeException());

        poller.tick();

        assertThat(row.getLastError()).isNull();
        assertThat(row.getStatus()).isEqualTo(ArtistImportStatus.PENDING);
    }

    @Test
    @DisplayName("each claimed row is handled independently, even when one of several fails")
    void eachClaimedRowHandledIndependently() {
        ArtistImport ok = row(OWNER, "Wilco", 0);
        ArtistImport failing = row(OWNER, "Dawes", 0);
        when(artistImportRepository.claimDue(any(), any(), anyInt())).thenReturn(List.of(ok, failing));
        when(artistSeedService.addSeedIfNew(OWNER, "Wilco")).thenReturn(true);
        when(artistSeedService.addSeedIfNew(OWNER, "Dawes")).thenThrow(new RuntimeException("nope"));

        poller.tick();

        assertThat(ok.getStatus()).isEqualTo(ArtistImportStatus.DONE);
        assertThat(failing.getStatus()).isEqualTo(ArtistImportStatus.PENDING);
        assertThat(failing.getAttempts()).isEqualTo(1);
        verify(artistImportRepository).save(ok);
        verify(artistImportRepository).save(failing);
    }
}
