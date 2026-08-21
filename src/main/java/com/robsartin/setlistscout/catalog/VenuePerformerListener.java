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
 * <b>Issue #219, CLOSED by V27 (previously an open, deliberately-not-fixed-here exposure; see this
 * class's git history for the earlier wording):</b> {@code artist} used to also carry a second,
 * narrower {@code UNIQUE (owner, name)} constraint ({@code artist_owner_name_key}) that this
 * listener's {@code ON CONFLICT (owner, normalized_name)} did not name as arbiter. Because Postgres
 * {@code ON CONFLICT} uses SPECULATIVE insertion (pre-check the arbiter, insert speculatively if
 * clear, then insert into EVERY index), two genuinely concurrent {@link VenuePerformerSeen}
 * deliveries for the same owner and exact performer name -- e.g. a touring comedian appearing on
 * two different followed venues' calendars, scanned close enough together that their {@code @Async}
 * dispatches overlap -- could both pass the arbiter's pre-check, both insert speculatively, and
 * then collide on that OTHER constraint during index insertion instead, throwing rather than being
 * absorbed. Narrow (both inserts had to be genuinely in flight at once, neither committed yet), but
 * real: see {@code VenuePerformerListenerTest}/{@code VenuePerformerSeenFlowTest} class docs and the
 * task-4 report for that scenario in full, and issue #219's own comment history for how a CI
 * failure under real concurrency confirmed the mechanism after two earlier probes missed it.
 * <p>
 * {@code V27__drop_artist_owner_name_constraint} removed {@code artist_owner_name_key} entirely, as
 * redundant with the stronger arbiter (see {@code ArtistRepository#insertIfAbsent}'s javadoc, "Why
 * (owner, name) uniqueness was DROPPED -- #219, reversing #179", for the full mechanism and why
 * dropping it loses no real protection). With only the named arbiter left on {@code artist}, this
 * listener's {@code ON CONFLICT} now covers every unique constraint on the table, so this exposure
 * is closed outright, not merely narrowed -- nothing further to fix here.
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
