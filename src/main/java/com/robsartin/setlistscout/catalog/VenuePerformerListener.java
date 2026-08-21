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
 * <b>Known exposure to issue #219 (not fixed here, per that issue's own scope; comment corrected
 * in #206 fix round 1 to stop overstating it):</b> {@code insertIfAbsent}'s {@code ON CONFLICT}
 * names only the {@code (owner, normalized_name)} arbiter. {@code artist} also carries a second
 * live {@code UNIQUE (owner, name)} constraint (confirmed still present as of {@code
 * V22__drop_redundant_artist_owner_name_constraint.sql}, which drops only a Hibernate ddl-auto-era
 * duplicate of it, not the constraint itself) -- but that second constraint is strictly IMPLIED by
 * the arbiter: {@code normalized_name} is a deterministic function of {@code name}, so any two rows
 * sharing {@code (owner, name)} necessarily also share {@code (owner, normalized_name)} (same
 * reasoning as {@code ArtistRepository#insertIfAbsent}'s own javadoc, "Why (owner, name) uniqueness
 * is kept anyway"). That means the ROUTINE path -- a venue calendar re-listing the same performer
 * every scan cycle, or the same performer later turning up on a second, different venue's calendar
 * -- never hits this: by the time either insert runs, any earlier row for that name is already
 * committed, so the arbiter alone finds it and absorbs the conflict cleanly, every time, with no
 * error and no race.
 * <p>
 * The real exposure is narrower: a genuine SPECULATIVE-INSERTION race, where two inserts of the
 * exact same performer name for the same owner are both still in flight, NEITHER committed yet.
 * Only then does it become possible for Postgres to reach the (owner, name) index's own conflict
 * check before the named arbiter's during that same concurrent insert, throwing instead of being
 * suppressed. {@code @ApplicationModuleListener} is {@code @Async}, so two {@code
 * VenuePerformerSeen} events for the same owner and exact name CAN have their listener invocations
 * genuinely overlap this way -- e.g. a touring comedian appearing on two different followed venues'
 * calendars, scanned close enough together that their async dispatches overlap -- see {@code
 * VenuePerformerListenerTest}/{@code VenuePerformerSeenFlowTest} class docs and the task-4 report
 * for that scenario in full. Narrow, not theoretical: worth the same "not fixed here" treatment,
 * just not the routine, every-scan-cycle exposure the previous wording implied.
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
