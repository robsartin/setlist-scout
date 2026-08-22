package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.settings.GeocodingService;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Which shared scans a user may see, and their contents (#163).
 * <p>
 * Access is participant-based, not admin-based: a shared scan is shows that two people would both
 * want, so both of them can see it. Creating one is no longer admin-gated either (#229) -- any
 * signed-in, allow-listed user may create a pairing against any other allow-listed address, with
 * no consent step. {@link #validateNewPairing} still rejects a duplicate (either direction), a
 * self-pairing, and a non-allow-listed {@code ownerB}.
 */
@Service
public class SharedScanService {

    private final SharedScanRepository sharedScanRepository;
    private final ShowRepository showRepository;
    private final ArtistRepository artistRepository;
    private final SettingsService settingsService;
    private final SharedScanReconciler reconciler;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;

    public SharedScanService(SharedScanRepository sharedScanRepository,
                              ShowRepository showRepository,
                              ArtistRepository artistRepository,
                              SettingsService settingsService,
                              SharedScanReconciler reconciler,
                              AppProperties appProperties,
                              TransactionTemplate transactionTemplate) {
        this.sharedScanRepository = sharedScanRepository;
        this.showRepository = showRepository;
        this.artistRepository = artistRepository;
        this.settingsService = settingsService;
        this.reconciler = reconciler;
        this.appProperties = appProperties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Every shared scan {@code email} participates in, oldest first and stable across calls
     * (#187). Empty for an unauthenticated caller.
     */
    public List<SharedScan> visibleTo(String email) {
        if (email == null || email.isBlank()) {
            return List.of();
        }
        return sharedScanRepository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCaseOrderByCreatedAtAscIdAsc(email, email);
    }

    /**
     * @throws ResponseStatusException 404 if no such shared scan exists OR the caller is not a
     * participant. Deliberately 404 rather than 403 for a non-participant: a 403 would confirm the
     * id exists, which is information a non-participant has no business receiving.
     */
    public SharedScan requireVisible(String email, Long id) {
        return sharedScanRepository.findById(id)
                .filter(scan -> scan.includes(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The shared scan's location/window settings, creating defaults on first access. */
    public SearchSettings settingsFor(SharedScan scan) {
        return settingsService.getOrCreateSettings(scan.getOwnerKey());
    }

    /**
     * How many artists are currently shared. The page needs this to tell "you two have nothing in
     * common" apart from "you share artists, none of them are playing there" -- collapsing those
     * into one empty state is the failure mode the spec calls out.
     */
    public int sharedArtistCount(SharedScan scan) {
        return artistRepository.findByOwnerAndStatusIn(scan.getOwnerKey(),
                List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)).size();
    }

    /** The shared scan's upcoming shows, in date order, within its configured window. */
    public List<Show> showsFor(SharedScan scan) {
        SearchSettings settings = settingsFor(scan);
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());
        return showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                scan.getOwnerKey(), start, end);
    }

    /**
     * Provision a new shared scan: allocate its synthetic owner key, create its settings row, and
     * populate its artists from the participants' current intersection.
     * <p>
     * Deliberately NOT {@code @Transactional} at this level (ADR-0024). A brand-new owner key's
     * settings row always needs geocoding -- it can never already exist -- and that geocode is a
     * slow external HTTP call to Zippopotam.us that must not run while holding a DB connection
     * open. It is resolved here first, before any transaction starts; the actual writes -- the
     * {@link SharedScan} row, the settings row (built from that already-resolved geocode, via
     * {@code SettingsService#getOrCreateSettings(String, Optional)}), and {@link
     * SharedScanReconciler#reconcile}'s artist activations -- still all commit together in one
     * {@link TransactionTemplate}-managed transaction, so {@code ArtistActivated}/{@code
     * ArtistDeactivated} still only ever publish from a committing transaction.
     *
     * @throws ResponseStatusException 400 -- see {@link #validateNewPairing}.
     */
    public SharedScan create(String label, String ownerA, String ownerB) {
        validateNewPairing(ownerA, ownerB);
        Optional<GeocodingService.GeoResult> geocode = settingsService.geocodeDefault();
        return transactionTemplate.execute(status -> {
            SharedScan scan = sharedScanRepository.save(
                    new SharedScan(SharedScanOwner.newKey(), ownerA, ownerB, label));
            settingsService.getOrCreateSettings(scan.getOwnerKey(), geocode);
            reconciler.reconcile(scan);
            return scan;
        });
    }

    /**
     * Rejects a would-be pairing before any row exists for it. None of these are visible on any
     * page today -- {@code SharedScanController#page} renders only {@code scans.get(0)} of an
     * unordered query, and there is no delete endpoint -- so without this check, each is a mistake
     * nobody could ever spot afterwards, not just one nobody should make in the first place:
     * <ul>
     *   <li><b>Duplicate pairing, either direction.</b> The app supports exactly one shared scan
     *   per pair ({@link SharedScan}'s own class doc). A second one for the same two people is not
     *   a visibly broken page -- it is an identical, silently doubled Ticketmaster/Bandsintown scan
     *   cadence for as long as both rows exist, which with no delete endpoint is forever.</li>
     *   <li><b>Self-pairing.</b> {@code ownerA} is always the creating user ({@code
     *   SharedScanController#create}, open to anyone allow-listed since #229) -- so pairing them
     *   with themselves intersects their own active artists with themselves -- for the admin
     *   account specifically, on the order of a thousand artists -- and enqueues scan jobs for
     *   every one of them under a synthetic key, with no page that would ever show this happened.</li>
     *   <li><b>Non-allow-listed {@code ownerB}.</b> The create form's dropdown is populated from
     *   {@code appProperties.auth().allowedEmails()} ({@code NavModelAdvice#otherOwnerEmails}), so
     *   a value outside that list can only reach here via a stale or tampered request. A shared
     *   scan pairs two real users; there is no meaningful pairing with an address that isn't one.</li>
     * </ul>
     */
    private void validateNewPairing(String ownerA, String ownerB) {
        if (ownerA.equalsIgnoreCase(ownerB)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot share a scan with yourself");
        }
        boolean ownerBAllowed = appProperties.auth().allowedEmails().stream()
                .anyMatch(email -> email.equalsIgnoreCase(ownerB));
        if (!ownerBAllowed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ownerB is not an allowed user");
        }
        boolean duplicate = sharedScanRepository
                .existsByOwnerAIgnoreCaseAndOwnerBIgnoreCaseOrOwnerAIgnoreCaseAndOwnerBIgnoreCase(
                        ownerA, ownerB, ownerB, ownerA);
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a shared scan for these two already exists");
        }
    }

    /** Update the shared scan's search location/window. Publishes SettingsChanged, which re-dues its scan jobs. */
    public SearchSettings updateSettings(SharedScan scan, String postalCode, int radiusMiles, int monthsAhead) {
        return settingsService.updateSettings(scan.getOwnerKey(), postalCode, radiusMiles, monthsAhead);
    }

    /**
     * Rename the shared scan's display label (#238) -- the only thing distinguishing multiple
     * pairings (#229) in the picker, and previously fixed forever at creation (SharedScan had no
     * setters at all). Either participant may call this; callers resolve {@code scan} through
     * {@link #requireVisible} first, exactly like {@link #updateSettings}. Trims surrounding
     * whitespace and REJECTS -- rather than silently truncates -- a blank/whitespace-only value or
     * one over the {@code varchar(255)} column cap (V18__create_shared_scan.sql). Deliberately no
     * uniqueness check: two pairings sharing a label is the caller's business, not this method's,
     * and the picker already shows the other participant alongside it.
     * <p>
     * Whatever the caller passes is stored verbatim after trimming -- no HTML-escaping here.
     * Escaping happens exactly once, at render time, via the template's own th:text/th:value; a
     * label containing '&amp;' (the exact case #238 exists for -- "R&amp;D", "R&amp;S") must
     * round-trip through that single escaping pass, not be pre-escaped here and escaped again on
     * the way out.
     *
     * @throws ResponseStatusException 400 if the trimmed label is blank or longer than 255 characters.
     */
    public SharedScan rename(SharedScan scan, String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label must not be blank");
        }
        if (trimmed.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label must be 255 characters or fewer");
        }
        scan.setLabel(trimmed);
        return sharedScanRepository.save(scan);
    }
}
