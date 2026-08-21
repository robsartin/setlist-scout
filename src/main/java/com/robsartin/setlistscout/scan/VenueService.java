package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;

/**
 * Adds a followed venue AND its {@code venue_scan_job} together (#206 Task 6). This is the only
 * production path that creates a venue -- Tasks 1-3 built the entity, the job's claim query, and
 * the runner/poller, but nothing before this task ever populated {@code venue_scan_job} outside a
 * test's own fixtures (Task 3's report flags this explicitly). A venue without a job is inert: it
 * sits in the list forever and nothing ever scans it.
 * <p>
 * {@code Venue} and {@code VenueScanJob} are both owned by THIS module ({@code scan}), unlike
 * {@code catalog.ArtistSeedService} creating an {@code Artist} whose {@code scan_job} rows are
 * enqueued by a separate module's listener off a published {@code ArtistActivated} event
 * (CLAUDE.md: "a module writes its own data; events cross boundaries"). There is no boundary to
 * cross here, so a plain synchronous two-step write in one transaction is the right shape, not an
 * event: it is also what keeps this testable without an {@code awaitUntil}/{@code
 * awaitQuiescence()} poll -- {@code @ApplicationModuleListener} is {@code @Async} AFTER_COMMIT
 * (CLAUDE.md), so an event-sourced job-creation path could not guarantee the job exists the
 * instant this method returns, which is exactly what the brief's own {@code
 * addingVenueCreatesScanJob} test asserts with no wait at all.
 */
@Service
public class VenueService {

    private final VenueRepository venueRepository;
    private final VenueScanJobRepository venueScanJobRepository;

    public VenueService(VenueRepository venueRepository, VenueScanJobRepository venueScanJobRepository) {
        this.venueRepository = venueRepository;
        this.venueScanJobRepository = venueScanJobRepository;
    }

    /**
     * What {@link #addVenue} actually did, so {@code VenueController} can decide whether the owner
     * needs to be told something (#206 fix round 1, Important 1). {@code BLANK} stays silent --
     * mirrors {@code ArtistSeedService#addSeedIfNew}'s existing blank-guard convention, an empty
     * submission is its own obvious feedback. {@code INVALID_URL} is NOT silent: a stored venue
     * whose calendar URL resolves to a null host can only ever fail to scan, forever, with no
     * signal that anything is wrong -- the concrete failure the review called out
     * ("capcitycomedy.com/events" with no scheme -&gt; {@code URI.create(...).getHost()} is null
     * -&gt; {@code source} becomes the literal "venue:null").
     */
    public enum AddVenueOutcome { ADDED, BLANK, INVALID_URL }

    /**
     * Adds {@code rawName}/{@code rawUrl} as a new venue for {@code owner}, unless either is
     * blank after trimming (mirrors {@code catalog.ArtistSeedService#addSeedIfNew}'s blank guard)
     * or {@code rawUrl}'s host cannot be resolved (#206 fix round 1, Important 1 -- see {@link
     * #hasResolvableHost}). Idempotent by name, matching every other seed path in this app: a
     * duplicate name (by {@link ArtistNameNormalizer#normalize}) is silently absorbed by {@link
     * VenueRepository#insertIfAbsent} -- no error, no second row.
     * <p>
     * Both writes happen in this ONE transaction: a venue that exists without a job is scanned by
     * nothing (see the class javadoc), so the two must succeed or fail together rather than risk
     * landing the venue with no job if something went wrong in between. The job is created
     * SCHEDULED and due immediately ({@code next_due_at = now}), so {@code
     * VenueScanJobRepository#claimDue}'s poller can pick it up on its very next tick.
     * <p>
     * Runs the job-ensure step even when the venue already existed (an absorbed insert): {@code
     * venueScanJobRepository#insertIfAbsent} is itself idempotent, so re-submitting the same venue
     * name is a harmless no-op in the common case, and quietly self-heals a venue that somehow
     * ended up without a job in the past, rather than leaving it stuck inert forever.
     */
    @Transactional
    public AddVenueOutcome addVenue(String owner, String rawName, String rawUrl) {
        String name = rawName == null ? "" : rawName.trim();
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (name.isEmpty() || url.isEmpty()) {
            return AddVenueOutcome.BLANK;
        }
        if (!hasResolvableHost(url)) {
            return AddVenueOutcome.INVALID_URL;
        }
        String normalizedName = ArtistNameNormalizer.normalize(name);
        Instant now = Instant.now();
        venueRepository.insertIfAbsent(owner, name, normalizedName, url, now);
        venueRepository.findByOwnerAndNormalizedName(owner, normalizedName)
                .ifPresent(venue -> venueScanJobRepository.insertIfAbsent(owner, venue.getId(), now));
        return AddVenueOutcome.ADDED;
    }

    /**
     * Removes {@code id}'s venue for {@code owner}, if it exists and belongs to them; a no-op,
     * not a leak, for a foreign or unknown id (#206 fix round 1, Important 1) -- matches this
     * codebase's established owner-scoped-mutation shape (e.g. {@code
     * ArtistController#setSiteUrl}'s {@code findByIdAndOwner(...).ifPresent(...)}). V25's {@code
     * venue_scan_job.venue_id REFERENCES venue (id) ON DELETE CASCADE} means this single delete is
     * enough to remove the venue's scan job too -- confirmed by a real Postgres-backed test
     * ({@code VenuePageRenderTest.removingAVenueDeletesItAndItsScanJob}) rather than assumed from
     * the migration's own comment.
     */
    @Transactional
    public void removeVenue(String owner, Long id) {
        venueRepository.findByIdAndOwner(id, owner).ifPresent(venueRepository::delete);
    }

    /**
     * Whether {@code url} has a resolvable host component -- the same check, and the same
     * "unknown host" failure mode, {@code VenueController#sourceFor} already has to defend against
     * when computing a venue's {@code "venue:" + host} source key. A scheme-less URL like
     * "capcitycomedy.com/events" parses as a relative reference (no authority component), so
     * {@link URI#getHost()} returns null rather than throwing -- the exact case this method exists
     * to catch before the row is ever stored.
     */
    private static boolean hasResolvableHost(String url) {
        try {
            return URI.create(url).getHost() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
