package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Atomic health bookkeeping for {@code source_health} (issue #265).
 *
 * <p>Every mutation is a single {@code INSERT ... ON CONFLICT DO UPDATE} that computes the new
 * counter from the row's own current value in SQL. Never load-mutate-save: {@code ScanPoller} claims
 * jobs with {@code FOR UPDATE SKIP LOCKED} and runs them outside any transaction, so two runs of the
 * same source can overlap. A read-modify-write on one shared counter row loses increments under
 * exactly that, and an undercounted streak means a dead source stays undetected -- the bug this
 * table exists to catch.
 */
public interface SourceHealthRepository extends JpaRepository<SourceHealth, String> {

    List<SourceHealth> findByHealthyFalse();

    /**
     * Record one failed call. Extends the current streak when {@code signature} matches the one in
     * flight, otherwise starts a new streak at 1 -- a source producing a MIX of failures is having a
     * bad day, not dead, and must never accumulate its way to unhealthy.
     *
     * <p>{@code streak_artists} counts DISTINCT artists in the streak, incremented only when the
     * failing artist differs from the last one. That is what keeps a single unscrapeable band site
     * from flipping {@code band-site} unhealthy however many times it is retried.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO source_health (source, healthy, consecutive_failures, streak_signature,
                                       streak_artists, last_artist_id, last_failure_at, last_error)
            VALUES (:source, true, 1, :signature, 1, :artistId, :now, :error)
            ON CONFLICT (source) DO UPDATE SET
                consecutive_failures = CASE
                    WHEN source_health.streak_signature = :signature
                    THEN source_health.consecutive_failures + 1 ELSE 1 END,
                streak_artists = CASE
                    WHEN source_health.streak_signature IS DISTINCT FROM :signature THEN 1
                    WHEN source_health.last_artist_id IS DISTINCT FROM :artistId
                    THEN source_health.streak_artists + 1
                    ELSE source_health.streak_artists END,
                streak_signature = :signature,
                last_artist_id = :artistId,
                last_failure_at = :now,
                last_error = :error
            """, nativeQuery = true)
    void recordFailure(@Param("source") String source,
                        @Param("artistId") Long artistId,
                        @Param("signature") String signature,
                        @Param("error") String error,
                        @Param("now") Instant now);

    /**
     * Flip a source unhealthy once its streak is long enough AND spans enough artists.
     *
     * @return 1 when this call is the one that flipped it (so the caller logs the transition once),
     * 0 when it was already unhealthy or the thresholds are not met.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE source_health
               SET healthy = false, unhealthy_since = :now
             WHERE source = :source
               AND healthy
               AND consecutive_failures >= :threshold
               AND streak_artists >= :minArtists
            """, nativeQuery = true)
    int markUnhealthyIfPastThreshold(@Param("source") String source,
                                      @Param("threshold") int threshold,
                                      @Param("minArtists") int minArtists,
                                      @Param("now") Instant now);

    /**
     * Record one successful call: the streak is over, and the source is healthy by definition --
     * something answered. Recovery therefore needs no human and no separate detector, which is the
     * whole point: the outage this issue came from was prolonged by a manual step nobody knew to do.
     *
     * <p>Unconditional, so this is ONE write on the hot path. Whether the write was a RECOVERY
     * (worth a log line) is decided by {@code SourceHealthService} from the snapshot it already
     * holds, rather than by making this statement report it.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO source_health (source, healthy, consecutive_failures, streak_signature,
                                       streak_artists, last_artist_id, last_success_at)
            VALUES (:source, true, 0, NULL, 0, NULL, :now)
            ON CONFLICT (source) DO UPDATE SET
                healthy = true, consecutive_failures = 0, streak_signature = NULL,
                streak_artists = 0, last_artist_id = NULL, unhealthy_since = NULL,
                last_success_at = :now
            """, nativeQuery = true)
    void recordSuccess(@Param("source") String source, @Param("now") Instant now);
}
