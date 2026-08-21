package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.VenuePerformerSeen;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Turns a {@link VenuePerformerSeen} event into a PENDING_REVIEW {@link Artist} candidate sourced
 * {@link ArtistSource#VENUE_EXPANSION} (#206 Task 4) -- unless a row already exists for this
 * owner under this (normalized) name, in ANY status.
 * <p>
 * <b>Must never resurrect a REJECTED artist.</b> A venue calendar re-lists the same performer
 * every scan cycle, so if a rejection could be undone by the next scan, it would reappear in the
 * review queue forever. This falls out for free from {@link ArtistRepository#insertIfAbsent}'s
 * {@code ON CONFLICT (owner, normalized_name) DO NOTHING}: the write is attempted unconditionally,
 * with no read-then-branch on the existing row's status, so "already APPROVED", "already
 * REJECTED", and "seen twice" are all the same single no-op -- the row simply is not inserted.
 * <p>
 * Uses the DB-level upsert rather than {@code existsBy} + {@code save} + catch, per ADR-0024: this
 * listener's whole body runs in one {@code @ApplicationModuleListener} transaction, and on an
 * IDENTITY-keyed table an uncaught constraint violation from a real race poisons that transaction
 * (Postgres "current transaction is aborted"), which then fails Modulith's own AFTER_COMMIT
 * completion write and leaves the event stuck for redelivery.
 * <p>
 * <b>Known exposure to issue #219 (not fixed here, per that issue's own scope):</b> {@code
 * insertIfAbsent}'s {@code ON CONFLICT} names only the {@code (owner, normalized_name)} arbiter.
 * {@code artist} also carries a second live {@code UNIQUE (owner, name)} constraint (confirmed
 * still present as of {@code V22__drop_redundant_artist_owner_name_constraint.sql}, which drops
 * only a Hibernate ddl-auto-era duplicate of it, not the constraint itself). A genuine race between
 * two concurrent inserts of the exact same performer name for the same owner is still caught by
 * the normalized-name arbiter and absorbed -- but Postgres checks unique indexes independently of
 * which one is named as the ON CONFLICT target, so if the (owner, name) index's own conflict check
 * is reached before the arbiter's during that same concurrent insert, the byte-identical-name race
 * can still throw instead of being suppressed. See {@code VenuePerformerListenerTest}/{@code
 * VenuePerformerSeenFlowTest} class docs and the task-4 report for why this path is realistically
 * reachable (not just theoretical) for a followed-venue calendar.
 */
@Component
public class VenuePerformerListener {

    private final ArtistRepository artistRepository;

    public VenuePerformerListener(ArtistRepository artistRepository) {
        this.artistRepository = artistRepository;
    }

    @ApplicationModuleListener
    void on(VenuePerformerSeen e) {
        String normalizedName = ArtistNameNormalizer.normalize(e.performerName());
        artistRepository.insertIfAbsent(e.owner(), e.performerName(), normalizedName,
                ArtistSource.VENUE_EXPANSION.name(), ArtistStatus.PENDING_REVIEW.name(),
                null, null, Instant.now());
    }
}
