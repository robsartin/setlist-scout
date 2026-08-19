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
import com.robsartin.setlistscout.shared.AbstractJob;
import com.robsartin.setlistscout.shared.JobRepository;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.JobStatusCount;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Assembles the admin queues page's whole model (#201): failed work with its error, counts by
 * queue/status, due-now backlog, oldest {@code next_due_at}, and import-queue state per owner.
 * Read-only observability -- see {@code admin.AdminQueueController}, gated by {@code
 * shared.AdminGuard}.
 * <p>
 * Every count here comes from an aggregate SQL query ({@code COUNT}/{@code MIN} ... {@code GROUP
 * BY}) via {@code shared.JobRepository} / {@code catalog.ArtistImportRepository} -- never a
 * loaded entity list tallied in Java. There are ~18,000 job rows in production; loading them to
 * count them would repeat exactly the mistake #176 existed to fix. The only entities this class
 * ever loads are the FAILED rows themselves (a handful, by definition the exceptional case) and
 * the small set of {@code Artist} rows needed to name them.
 */
@Service
public class AdminQueueService {

    private final ScanJobRepository scanJobRepository;
    private final ExpandJobRepository expandJobRepository;
    private final ArtistImportRepository artistImportRepository;
    private final ArtistRepository artistRepository;

    public AdminQueueService(ScanJobRepository scanJobRepository, ExpandJobRepository expandJobRepository,
                              ArtistImportRepository artistImportRepository, ArtistRepository artistRepository) {
        this.scanJobRepository = scanJobRepository;
        this.expandJobRepository = expandJobRepository;
        this.artistImportRepository = artistImportRepository;
        this.artistRepository = artistRepository;
    }

    public AdminQueueSnapshot snapshot() {
        Instant now = Instant.now();
        return new AdminQueueSnapshot(
                queueCounts(scanJobRepository, now),
                queueCounts(expandJobRepository, now),
                importCounts(),
                failedWork());
    }

    /**
     * One queue's counts (#201 items 2 and 4): status breakdown, due-now, and oldest {@code
     * next_due_at} -- three aggregate queries, generic over {@code T} so this runs once for both
     * {@code scan_job} and {@code expand_job} instead of being hand-duplicated per queue.
     */
    private <T extends AbstractJob> QueueCounts queueCounts(JobRepository<T> repository, Instant now) {
        Map<JobStatus, Long> byStatus = new EnumMap<>(JobStatus.class);
        for (JobStatusCount row : repository.countGroupedByStatus()) {
            byStatus.put(row.getStatus(), row.getCount());
        }
        long dueNow = repository.countByNextDueAtLessThanEqual(now);
        Instant oldest = repository.findFirstByOrderByNextDueAtAsc().map(AbstractJob::getNextDueAt).orElse(null);
        return new QueueCounts(
                byStatus.getOrDefault(JobStatus.SCHEDULED, 0L),
                byStatus.getOrDefault(JobStatus.RUNNING, 0L),
                byStatus.getOrDefault(JobStatus.FAILED, 0L),
                dueNow, oldest);
    }

    /** Import-queue state per owner (#201 item 3): pending/done/failed, owner-alphabetical. */
    private List<ImportOwnerCounts> importCounts() {
        Map<String, Map<ArtistImportStatus, Long>> byOwner = new LinkedHashMap<>();
        for (ImportOwnerStatusCount row : artistImportRepository.countGroupedByOwnerAndStatus()) {
            byOwner.computeIfAbsent(row.getOwner(), o -> new EnumMap<>(ArtistImportStatus.class))
                    .put(row.getStatus(), row.getCount());
        }
        return byOwner.entrySet().stream()
                .map(e -> new ImportOwnerCounts(e.getKey(),
                        e.getValue().getOrDefault(ArtistImportStatus.PENDING, 0L),
                        e.getValue().getOrDefault(ArtistImportStatus.DONE, 0L),
                        e.getValue().getOrDefault(ArtistImportStatus.FAILED, 0L)))
                .sorted(Comparator.comparing(ImportOwnerCounts::owner))
                .toList();
    }

    /**
     * Every FAILED row across scan, expand, and import (#201 item 1, the highest-priority part of
     * the page). Resolves artist names for the scan/expand rows in one batch lookup -- proportional
     * to the (small) FAILED set, not the whole catalog.
     */
    private List<FailedWorkRow> failedWork() {
        List<ScanJob> failedScans = scanJobRepository.findByStatusOrderByNextDueAtAsc(JobStatus.FAILED);
        List<ExpandJob> failedExpands = expandJobRepository.findByStatusOrderByNextDueAtAsc(JobStatus.FAILED);
        List<ArtistImport> failedImports =
                artistImportRepository.findByStatusOrderByOwnerAscNameAsc(ArtistImportStatus.FAILED);

        Set<Long> artistIds = Stream.concat(
                        failedScans.stream().map(AbstractJob::getArtistId),
                        failedExpands.stream().map(AbstractJob::getArtistId))
                .collect(Collectors.toSet());
        Map<Long, String> artistNames = artistIds.isEmpty() ? Map.of()
                : artistRepository.findAllById(artistIds).stream()
                        .collect(Collectors.toMap(Artist::getId, Artist::getName));

        List<FailedWorkRow> rows = new ArrayList<>();
        for (ScanJob job : failedScans) {
            rows.add(new FailedWorkRow("Scan", job.getOwner(), subject(job, artistNames),
                    job.getAttempts(), job.getLastError()));
        }
        for (ExpandJob job : failedExpands) {
            rows.add(new FailedWorkRow("Expand", job.getOwner(), subject(job, artistNames),
                    job.getAttempts(), job.getLastError()));
        }
        for (ArtistImport row : failedImports) {
            rows.add(new FailedWorkRow("Import", row.getOwner(), row.getName(),
                    row.getAttempts(), row.getLastError()));
        }
        return rows;
    }

    /** "What it was working on" for a scan/expand job: the artist name (if still resolvable) and source. */
    private static String subject(AbstractJob job, Map<Long, String> artistNames) {
        String name = artistNames.getOrDefault(job.getArtistId(), "artist #" + job.getArtistId());
        return name + " (" + job.getSource() + ")";
    }
}
