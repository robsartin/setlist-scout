package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when a followed venue's calendar lists a performer (#206) -- one event
 * per distinct performer name found in a single scan, not one per show (a performer with two
 * dates at the same venue in one scan window publishes once).
 * <p>
 * Published by: {@code scan.VenueScanRunner}, after persisting the venue's shows, inside a short
 * committing {@code TransactionTemplate} block per event (ADR-0024) -- {@code
 * @ApplicationModuleListener} is {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so a
 * publish with no active, committing transaction around it is silently dropped and the listener
 * never fires.
 * <p>
 * Consumed by: {@code catalog.VenuePerformerListener}, which creates a PENDING_REVIEW {@link
 * com.robsartin.setlistscout.catalog.Artist} candidate sourced {@code VENUE_EXPANSION} unless one
 * already exists for this owner under this name, in ANY status -- in particular, this must never
 * resurrect a REJECTED artist, since a venue calendar re-lists the same performer every scan cycle.
 */
public record VenuePerformerSeen(
        String owner,
        String performerName
) {
}
