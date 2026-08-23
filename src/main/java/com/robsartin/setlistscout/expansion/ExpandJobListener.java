package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import com.robsartin.setlistscout.shared.events.ArtistShowsFound;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Keeps {@code expand_job} rows in sync with the catalog domain events: enqueues one job per
 * {@link RelationSource} on activation and cancels all of an artist's jobs on deactivation.
 * Expansion isn't location-sensitive, so unlike {@code scan.ScanJobListener} there's no
 * settings/fingerprint concern here.
 */
@Component
public class ExpandJobListener {

    private final ExpandJobRepository expandJobRepository;
    private final List<RelationSource> relationSources;

    public ExpandJobListener(ExpandJobRepository expandJobRepository, List<RelationSource> relationSources) {
        this.expandJobRepository = expandJobRepository;
        this.relationSources = relationSources;
    }

    @ApplicationModuleListener
    void onArtistActivated(ArtistActivated e) {
        // #163 guard: a shared scan is a scan context, not a person. Expanding it would discover
        // member/similar/tribute candidates for an owner who has no Candidates page, no reviewer,
        // and no way to see them -- while billing an LLM call per artist. Invisible if wrong,
        // which is why SharedScanGuardsTest pins it.
        if (SharedScanOwner.isSharedScanKey(e.owner())) {
            return;
        }

        // Source eligibility (e.g. tribute expansion is SEED-only) is a property of the source
        // itself -- see RelationSource#appliesTo. The status rides on the event itself (#102) so
        // this listener no longer needs to query catalog.ArtistRepository back for it.
        ArtistStatus artistStatus = parseStatus(e.status());

        // #254: only a SEED expands on activation. A SEED is an artist the owner named
        // themselves, so it is evidence by construction. Everything else arrived from an earlier
        // expansion and has proven nothing yet -- it waits for ArtistShowsFound. Without this,
        // one wrong name match multiplies without limit: "Bill Bryson" the bluegrass bassist
        // resolved on the next hop to "Bill Bryson" the travel author, and the cluster below that
        // reached ~40 approved authors, none of which has ever had a show.
        if (artistStatus != ArtistStatus.SEED) {
            return;
        }

        enqueueFor(e.owner(), e.artistId(), artistStatus);
    }

    /**
     * #254: an expansion-discovered artist earns the right to be expanded from by actually
     * turning up a show. {@code scan} publishes {@link ArtistShowsFound} when a scan run finds
     * one; until then the artist sits in the catalog without spreading.
     * <p>
     * Idempotent for free: this fires on every scan cycle that finds shows for the artist, and
     * {@code insertIfAbsent} is an {@code ON CONFLICT DO NOTHING}, so repeats cost one no-op
     * insert per source rather than duplicate jobs.
     */
    @ApplicationModuleListener
    void onArtistShowsFound(ArtistShowsFound e) {
        if (SharedScanOwner.isSharedScanKey(e.owner())) {
            return;
        }
        enqueueFor(e.owner(), e.artistId(), parseStatus(e.status()));
    }

    private void enqueueFor(String owner, Long artistId, ArtistStatus artistStatus) {
        for (RelationSource source : relationSources) {
            if (!source.appliesTo(artistStatus)) {
                continue;
            }
            // DB-level idempotent insert (ON CONFLICT DO NOTHING) rather than existsBy+save+catch:
            // @ApplicationModuleListener runs this whole loop in one transaction, and on an
            // IDENTITY-keyed table an uncaught DataIntegrityViolationException from a real unique-
            // constraint race aborts the ENTIRE transaction for every later iteration too (Postgres
            // "current transaction is aborted"). insertIfAbsent never throws on a duplicate, so the
            // loop always completes in a single pass.
            expandJobRepository.insertIfAbsent(owner, artistId, source.id(), Instant.now());
        }
    }

    /**
     * Null-safe {@code ArtistStatus} parse: falls back to {@code null} (rather than throwing) on a
     * blank or unrecognized name. This matters for the durable {@code event_publication} registry,
     * not just live dispatch -- rows written before #102 added the {@code status} field deserialize
     * with {@code status = null}. Live async dispatch always hands the listener the in-memory event
     * (so this path is dead today, since replay-on-restart is off), but if
     * {@code spring.modulith.events.republish-outstanding-events-on-restart} is ever turned on, a
     * pre-#102 row would otherwise throw inside this {@code REQUIRES_NEW} listener transaction and
     * leave the event stuck retrying forever. Falling back to {@code null} instead reproduces the
     * exact pre-#102 "artist not found" semantics: {@code source.appliesTo(null)} skips the
     * SEED-only tribute source (its override treats non-SEED, including {@code null}, as
     * ineligible) but still enqueues every other source via the default {@code appliesTo} (always
     * {@code true}).
     */
    private static ArtistStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return ArtistStatus.valueOf(status);
        } catch (IllegalArgumentException notARealStatus) {
            return null;
        }
    }

    @ApplicationModuleListener
    void onArtistDeactivated(ArtistDeactivated e) {
        expandJobRepository.deleteByOwnerAndArtistId(e.owner(), e.artistId());
    }
}
