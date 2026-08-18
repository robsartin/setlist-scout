package com.robsartin.setlistscout.admin;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistImport;
import com.robsartin.setlistscout.catalog.ArtistImportRepository;
import com.robsartin.setlistscout.catalog.ArtistImportStatus;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ImportOwnerStatusCount;
import com.robsartin.setlistscout.expansion.ExpandJob;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.scan.ScanJob;
import com.robsartin.setlistscout.scan.ScanJobRepository;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.JobStatusCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Java unit test (mocked repositories, no Spring, no DB) for the #201 admin queues
 * aggregation logic. {@link #neverLoadsEntityListsToComputeCounts()} is the "aggregate-query
 * requirement" pin the issue asks for: it fails immediately if a future change swaps any count
 * for {@code findAll().size()}, without needing ~18,000 real rows to notice.
 * <p>
 * Real-data correctness of the aggregate SQL itself (across statuses, including a shared-scan
 * owner) is proven separately against actual Postgres in scan.ScanJobRepositoryTest,
 * expansion.ExpandJobRepositoryTest, and catalog.ArtistImportRepositoryTest.
 */
class AdminQueueServiceTest {

    private ScanJobRepository scanJobRepository;
    private ExpandJobRepository expandJobRepository;
    private ArtistImportRepository artistImportRepository;
    private ArtistRepository artistRepository;
    private AdminQueueService service;

    @BeforeEach
    void setUp() {
        scanJobRepository = mock(ScanJobRepository.class);
        expandJobRepository = mock(ExpandJobRepository.class);
        artistImportRepository = mock(ArtistImportRepository.class);
        artistRepository = mock(ArtistRepository.class);
        service = new AdminQueueService(scanJobRepository, expandJobRepository, artistImportRepository, artistRepository);

        // Empty-queue defaults so each test below only has to stub what it actually cares about.
        when(scanJobRepository.countGroupedByStatus()).thenReturn(List.of());
        when(scanJobRepository.countByNextDueAtLessThanEqual(any(Instant.class))).thenReturn(0L);
        when(scanJobRepository.findFirstByOrderByNextDueAtAsc()).thenReturn(Optional.empty());
        when(scanJobRepository.findByStatusOrderByNextDueAtAsc(JobStatus.FAILED)).thenReturn(List.of());
        when(expandJobRepository.countGroupedByStatus()).thenReturn(List.of());
        when(expandJobRepository.countByNextDueAtLessThanEqual(any(Instant.class))).thenReturn(0L);
        when(expandJobRepository.findFirstByOrderByNextDueAtAsc()).thenReturn(Optional.empty());
        when(expandJobRepository.findByStatusOrderByNextDueAtAsc(JobStatus.FAILED)).thenReturn(List.of());
        when(artistImportRepository.countGroupedByOwnerAndStatus()).thenReturn(List.of());
        when(artistImportRepository.findByStatusOrderByOwnerAscNameAsc(ArtistImportStatus.FAILED)).thenReturn(List.of());
        when(artistRepository.findAllById(any())).thenReturn(List.of());
    }

    private static JobStatusCount statusCount(JobStatus status, long count) {
        JobStatusCount row = mock(JobStatusCount.class);
        when(row.getStatus()).thenReturn(status);
        when(row.getCount()).thenReturn(count);
        return row;
    }

    private static ImportOwnerStatusCount importCount(String owner, ArtistImportStatus status, long count) {
        ImportOwnerStatusCount row = mock(ImportOwnerStatusCount.class);
        when(row.getOwner()).thenReturn(owner);
        when(row.getStatus()).thenReturn(status);
        when(row.getCount()).thenReturn(count);
        return row;
    }

    @Test
    @DisplayName("snapshot composes a queue's status counts, due-now, and oldest next_due_at from the aggregate projections")
    void snapshotComposesQueueCountsFromAggregateProjections() {
        Instant oldestScan = Instant.now().minus(Duration.ofDays(2));
        // Built as separate statements, BEFORE the when(...) below: statusCount() does its own
        // mock()+when()+thenReturn() internally, and nesting that inside this when(...)'s still-open
        // argument list interleaves Mockito's "pending stub" tracking with the outer call's, which
        // throws UnfinishedStubbingException. Same reasoning for every other statusCount/importCount
        // call site in this file.
        List<JobStatusCount> scanStatusCounts = List.of(
                statusCount(JobStatus.SCHEDULED, 10L),
                statusCount(JobStatus.RUNNING, 2L),
                statusCount(JobStatus.FAILED, 1L));
        when(scanJobRepository.countGroupedByStatus()).thenReturn(scanStatusCounts);
        when(scanJobRepository.countByNextDueAtLessThanEqual(any(Instant.class))).thenReturn(4L);
        ScanJob oldestJob = new ScanJob(1L, "ticketmaster", JobStatus.SCHEDULED, 0, oldestScan);
        when(scanJobRepository.findFirstByOrderByNextDueAtAsc()).thenReturn(Optional.of(oldestJob));

        QueueCounts scanCounts = service.snapshot().scanCounts();

        assertThat(scanCounts).isEqualTo(new QueueCounts(10L, 2L, 1L, 4L, oldestScan));
    }

    @Test
    @DisplayName("a status missing from the aggregate projection defaults to zero, not a lookup failure")
    void missingStatusDefaultsToZero() {
        List<JobStatusCount> onlyScheduled = List.of(statusCount(JobStatus.SCHEDULED, 5L));
        when(expandJobRepository.countGroupedByStatus()).thenReturn(onlyScheduled);

        QueueCounts expandCounts = service.snapshot().expandCounts();

        assertThat(expandCounts.scheduled()).isEqualTo(5L);
        assertThat(expandCounts.running()).isZero();
        assertThat(expandCounts.failed()).isZero();
    }

    @Test
    @DisplayName("an empty queue reports oldestNextDueAt as null rather than throwing")
    void emptyQueueReportsNullOldestDueDate() {
        QueueCounts scanCounts = service.snapshot().scanCounts();

        assertThat(scanCounts.oldestNextDueAt()).isNull();
    }

    @Test
    @DisplayName("#201: snapshot never loads whole-table entity lists to compute a count -- "
            + "pins the aggregate-query requirement so a future findAll().size() regression fails here")
    void neverLoadsEntityListsToComputeCounts() {
        service.snapshot();

        verify(scanJobRepository, never()).findAll();
        verify(expandJobRepository, never()).findAll();
        verify(artistImportRepository, never()).findAll();
        // Confirms this is a real pin, not a vacuous "nothing happened" pass: the actual
        // aggregate-query calls DID happen.
        verify(scanJobRepository).countGroupedByStatus();
        verify(expandJobRepository).countGroupedByStatus();
        verify(artistImportRepository).countGroupedByOwnerAndStatus();
    }

    @Test
    @DisplayName("importCounts groups pending/done/failed per owner, sorted by owner")
    void importCountsGroupsPerOwner() {
        List<ImportOwnerStatusCount> ownerStatusCounts = List.of(
                importCount("zeta@example.com", ArtistImportStatus.PENDING, 3L),
                importCount("alpha@example.com", ArtistImportStatus.DONE, 7L),
                importCount("alpha@example.com", ArtistImportStatus.FAILED, 1L));
        when(artistImportRepository.countGroupedByOwnerAndStatus()).thenReturn(ownerStatusCounts);

        List<ImportOwnerCounts> counts = service.snapshot().importCounts();

        assertThat(counts).containsExactly(
                new ImportOwnerCounts("alpha@example.com", 0L, 7L, 1L),
                new ImportOwnerCounts("zeta@example.com", 3L, 0L, 0L));
    }

    @Test
    @DisplayName("failed scan/expand work resolves the artist's name and pairs it with the source")
    void failedWorkResolvesArtistNameAndSource() {
        ScanJob failedScan = new ScanJob(5L, "ticketmaster", JobStatus.FAILED, 3, Instant.now());
        failedScan.setOwner("rob@example.com");
        failedScan.setLastError("Ticketmaster 500");
        when(scanJobRepository.findByStatusOrderByNextDueAtAsc(JobStatus.FAILED)).thenReturn(List.of(failedScan));

        Artist radiohead = mock(Artist.class);
        when(radiohead.getId()).thenReturn(5L);
        when(radiohead.getName()).thenReturn("Radiohead");
        when(artistRepository.findAllById(any())).thenReturn(List.of(radiohead));

        List<FailedWorkRow> failedWork = service.snapshot().failedWork();

        assertThat(failedWork).containsExactly(
                new FailedWorkRow("Scan", "rob@example.com", "Radiohead (ticketmaster)", 3, "Ticketmaster 500"));
    }

    @Test
    @DisplayName("a failed job whose artist can no longer be resolved falls back to its artist id, not a crash")
    void failedWorkFallsBackToArtistIdWhenArtistNotFound() {
        ExpandJob failedExpand = new ExpandJob(99L, "lastfm", JobStatus.FAILED, 6, Instant.now());
        failedExpand.setOwner("shared:aaaa-bbbb");
        failedExpand.setLastError("gone");
        when(expandJobRepository.findByStatusOrderByNextDueAtAsc(JobStatus.FAILED)).thenReturn(List.of(failedExpand));

        List<FailedWorkRow> failedWork = service.snapshot().failedWork();

        assertThat(failedWork).containsExactly(
                new FailedWorkRow("Expand", "shared:aaaa-bbbb", "artist #99 (lastfm)", 6, "gone"));
    }

    @Test
    @DisplayName("failed imports use the queued name directly -- no artist id exists yet to resolve")
    void failedImportsUseTheQueuedNameDirectly() {
        ArtistImport failedImport = mock(ArtistImport.class);
        when(failedImport.getOwner()).thenReturn("rob@example.com");
        when(failedImport.getName()).thenReturn("Some Band");
        when(failedImport.getAttempts()).thenReturn(3);
        when(failedImport.getLastError()).thenReturn("normalize failed");
        when(artistImportRepository.findByStatusOrderByOwnerAscNameAsc(ArtistImportStatus.FAILED))
                .thenReturn(List.of(failedImport));

        List<FailedWorkRow> failedWork = service.snapshot().failedWork();

        assertThat(failedWork).containsExactly(
                new FailedWorkRow("Import", "rob@example.com", "Some Band", 3, "normalize failed"));
    }

    @Test
    @DisplayName("failedWork orders scan rows before expand rows before import rows")
    void failedWorkOrdersByQueue() {
        ScanJob scanJob = new ScanJob(1L, "ticketmaster", JobStatus.FAILED, 1, Instant.now());
        scanJob.setOwner("rob@example.com");
        when(scanJobRepository.findByStatusOrderByNextDueAtAsc(JobStatus.FAILED)).thenReturn(List.of(scanJob));

        ExpandJob expandJob = new ExpandJob(2L, "lastfm", JobStatus.FAILED, 1, Instant.now());
        expandJob.setOwner("rob@example.com");
        when(expandJobRepository.findByStatusOrderByNextDueAtAsc(JobStatus.FAILED)).thenReturn(List.of(expandJob));

        ArtistImport importRow = mock(ArtistImport.class);
        when(importRow.getOwner()).thenReturn("rob@example.com");
        when(importRow.getName()).thenReturn("Some Band");
        when(artistImportRepository.findByStatusOrderByOwnerAscNameAsc(ArtistImportStatus.FAILED))
                .thenReturn(List.of(importRow));

        List<FailedWorkRow> failedWork = service.snapshot().failedWork();

        assertThat(failedWork).extracting(FailedWorkRow::queue).containsExactly("Scan", "Expand", "Import");
    }
}
