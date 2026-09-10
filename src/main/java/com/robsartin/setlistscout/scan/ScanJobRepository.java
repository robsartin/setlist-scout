package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.JobRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ScanJobRepository extends JobRepository<ScanJob> {

    /**
     * DB-level idempotent enqueue: relies on the {@code scan_job_owner_artist_id_source_key}
     * unique constraint via {@code ON CONFLICT ... DO NOTHING} so a racing redelivery (or
     * concurrent activation) for the same (owner, artist_id, source) is a silent no-op instead of
     * a {@code DataIntegrityViolationException}. That matters because
     * {@code @ApplicationModuleListener} runs the whole per-source loop in one transaction on an
     * IDENTITY-keyed table: an uncaught constraint violation from one iteration would abort the
     * transaction for every subsequent statement (Postgres "current transaction is aborted"),
     * losing jobs for the other sources too. See scan.ScanJobListener#onArtistActivated.
     */
    @Modifying
    @Query(value = """
            INSERT INTO scan_job (owner, artist_id, source, status, attempts, next_due_at)
            VALUES (:owner, :artistId, :source, 'SCHEDULED', 0, :nextDueAt)
            ON CONFLICT (owner, artist_id, source) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("owner") String owner,
                         @Param("artistId") Long artistId,
                         @Param("source") String source,
                         @Param("nextDueAt") Instant nextDueAt);

    /**
     * Atomically claims up to {@code batch} due, unclaimed-or-stale-leased rows for the poller
     * (PR4): {@code FOR UPDATE SKIP LOCKED} on the inner selection means two pollers racing this
     * query concurrently never claim the same row -- each just skips whatever the other already
     * has locked and picks the next candidate instead of blocking on it. A row is a candidate if
     * it's due ({@code next_due_at <= :now}) and either never claimed or its lease has expired
     * ({@code claimed_at < :leaseCutoff}), oldest-due first. {@code RETURNING *} maps straight
     * back onto the entity via this native query -- verified against real Postgres in
     * ScanJobRepositoryTest, since Spring Data's native-query-to-entity mapping for a RETURNING
     * clause isn't guaranteed by the framework docs the way a plain SELECT is.
     */
    @Modifying
    @Query(value = """
            UPDATE scan_job SET claimed_at = :now, status = 'RUNNING'
            WHERE id IN (
                SELECT id FROM scan_job
                WHERE next_due_at <= :now AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
                ORDER BY next_due_at
                LIMIT :batch
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<ScanJob> claimDue(@Param("now") Instant now,
                            @Param("leaseCutoff") Instant leaseCutoff,
                            @Param("batch") int batch);

    /**
     * {@link #claimDue}, but skipping every source in {@code excluded} (issue #265).
     *
     * <p>A source detected dead would otherwise fill every batch with jobs that cannot succeed and
     * starve the sources that still work -- 3,252 Bandsintown jobs against a batch size in the tens.
     * Its jobs are instead reached only by {@link #claimProbe}, one at a time.
     *
     * <p>{@code excluded} is never empty when this is called; {@code ScanPoller} uses the plain
     * {@link #claimDue} when every source is healthy, because {@code NOT IN ()} is a syntax error in
     * Postgres rather than a no-op.
     */
    @Modifying
    @Query(value = """
            UPDATE scan_job SET claimed_at = :now, status = 'RUNNING'
            WHERE id IN (
                SELECT id FROM scan_job
                WHERE next_due_at <= :now AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
                  AND source NOT IN (:excluded)
                ORDER BY next_due_at
                LIMIT :batch
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<ScanJob> claimDueExcludingSources(@Param("now") Instant now,
                                            @Param("leaseCutoff") Instant leaseCutoff,
                                            @Param("batch") int batch,
                                            @Param("excluded") Collection<String> excluded);

    /**
     * Claim up to {@code batch} due jobs for ONE source -- the probe that lets a source detected
     * dead prove it is alive again (issue #265).
     *
     * <p>Deliberately tiny (one job per tick). The point is to notice recovery, not to drain the
     * queue: while the source is down, {@code ScanPoller} re-dues its jobs at a short probe interval
     * rather than the full 14 days, so there is always something due to probe and the fleet drains
     * on its own the moment health clears.
     */
    @Modifying
    @Query(value = """
            UPDATE scan_job SET claimed_at = :now, status = 'RUNNING'
            WHERE id IN (
                SELECT id FROM scan_job
                WHERE next_due_at <= :now AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
                  AND source = :source
                ORDER BY next_due_at
                LIMIT :batch
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<ScanJob> claimProbe(@Param("now") Instant now,
                              @Param("leaseCutoff") Instant leaseCutoff,
                              @Param("batch") int batch,
                              @Param("source") String source);

    /**
     * Version-safe bulk re-due of every one of an owner's scan jobs: make them due-now and cleanly
     * claimable (SCHEDULED, attempts reset, lease cleared), and bump {@code version} so any poller
     * holding one of these rows in-flight conflicts on its next {@code save()} (ScanPoller catches
     * that and skips its stale reschedule) instead of silently overwriting this re-due. Used by
     * ScanJobListener#onSettingsChanged and the manual "Scan now" button (ShowController#scanNow).
     * {@code @Transactional} makes this self-transactional regardless of caller: ShowController#scanNow
     * is a plain {@code @PostMapping} handler with no ambient transaction, and this
     * {@code @Modifying} bulk query needs one to execute (Spring Data honors {@code @Transactional}
     * on repository interface methods).
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE scan_job
               SET next_due_at = :now, status = 'SCHEDULED', attempts = 0, claimed_at = NULL,
                   version = version + 1
             WHERE owner = :owner
            """, nativeQuery = true)
    int redueAll(@Param("owner") String owner,
                  @Param("now") Instant now);

    /**
     * Version-safe redue of a single artist's scan jobs (#246): the same contract as {@link
     * #redueAll} above -- {@code SCHEDULED}/{@code attempts = 0}/{@code claimed_at = NULL} so a
     * {@code FAILED} job becomes claimable again ({@code claimDue} hard-filters on {@code status =
     * 'SCHEDULED'}), and {@code version = version + 1} so a poller holding one of these rows
     * in-flight conflicts on its next {@code save()} ({@code ScanPoller} catches that
     * {@code OptimisticLockingFailureException} and skips its stale reschedule) instead of
     * silently overwriting this redue -- issued while a scan is running is exactly when someone
     * would click this button. Scoped to {@code (owner, artist_id)} instead of the whole owner, so
     * re-scanning one artist after a configuration change (site URL, default venue, a name fix)
     * doesn't touch the other ~6,400 jobs an owner may have. A narrow sibling to {@code redueAll}
     * rather than an optional {@code artistId} parameter on it -- two explicit queries read better
     * than one with a conditional predicate, and it keeps {@code redueAll}'s own SQL untouched.
     * <p>
     * {@code @Transactional} for the same reason as {@code redueAll}: the caller
     * ({@code ShowController#scanNowForArtist}) is a plain {@code @PostMapping} handler with no
     * ambient transaction, and this {@code @Modifying} bulk query needs one to execute.
     * <p>
     * Verified against real Postgres in {@code ScanJobRepositoryTest} to actually scope by BOTH
     * columns, not just one: a query missing the {@code artist_id} predicate would re-due every
     * job the owner has and still satisfy a single-artist "it worked" assertion (caught by
     * {@code redueForArtistDoesNotTouchAnotherArtistsJob}, which asserts a second artist's job is
     * untouched); a query missing the {@code owner} predicate would leak across accounts (caught
     * by {@code redueForArtistDoesNotTouchAnotherOwnersJobForTheSameArtistId}).
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE scan_job
               SET next_due_at = :now, status = 'SCHEDULED', attempts = 0, claimed_at = NULL,
                   version = version + 1
             WHERE owner = :owner AND artist_id = :artistId
            """, nativeQuery = true)
    int redueForArtist(@Param("owner") String owner,
                        @Param("artistId") Long artistId,
                        @Param("now") Instant now);
}
