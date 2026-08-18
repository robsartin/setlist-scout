package com.robsartin.setlistscout.shared;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Derived-finder base shared by scan.ScanJobRepository and expansion.ExpandJobRepository.
 * The native {@code @Query} methods (insertIfAbsent, claimDue, redueAll) stay on the concrete
 * repos -- their SQL hardcodes the table name (scan_job / expand_job), so they can't move here.
 */
@NoRepositoryBean
public interface JobRepository<T extends AbstractJob> extends JpaRepository<T, Long> {
    Optional<T> findByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);
    List<T> findByOwnerAndArtistId(String owner, Long artistId);
    List<T> findByOwner(String owner);
    void deleteByOwnerAndArtistId(String owner, Long artistId);

    /**
     * Every FAILED row across every owner, most-overdue first -- the admin queues page's
     * failed-work section (#201), the only genuinely invisible information today. A plain
     * derived query, same as the finders above: it loads exactly the (small) FAILED set, never
     * the whole table.
     */
    List<T> findByStatusOrderByNextDueAtAsc(JobStatus status);

    /**
     * How many rows are due right now, across every owner (#201) -- the literal {@code
     * next_due_at <= now()} the admin page reports, answering "is it stuck or just busy?".
     * {@code COUNT(*) ... WHERE}, not a loaded list.
     */
    long countByNextDueAtLessThanEqual(Instant now);

    /**
     * The single earliest {@code next_due_at} in the whole queue (#201) -- a better "how far
     * behind" signal than a raw backlog count: in the past means overdue by that much, in the
     * future (with nothing overdue) means "next thing due in X". Empty table -&gt; empty Optional.
     */
    Optional<T> findFirstByOrderByNextDueAtAsc();

    /**
     * Aggregate status counts for the whole queue, every owner (#201): {@code COUNT(*) ... GROUP
     * BY}, never {@code findAll().size()} -- there are ~18,000 job rows between scan_job and
     * expand_job, and loading them to count them would repeat exactly the mistake #176 existed to
     * fix. JPQL, not native: {@code #{#entityName}} resolves to whichever concrete entity
     * (ScanJob/ExpandJob) this interface is bound to per repository -- a native query would have
     * to hardcode the table name and, per the class doc above, couldn't live here.
     */
    @Query("SELECT j.status AS status, COUNT(j) AS count FROM #{#entityName} j GROUP BY j.status")
    List<JobStatusCount> countGroupedByStatus();
}
