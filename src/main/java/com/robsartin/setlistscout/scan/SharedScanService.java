package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Which shared scans a user may see, and their contents (#163).
 * <p>
 * Access is participant-based, not admin-based: a shared scan is shows that two people would both
 * want, so both of them can see it. {@code AdminGuard} still gates CREATING one -- that is the
 * admin action -- but viewing is not admin-only.
 */
@Service
public class SharedScanService {

    private final SharedScanRepository sharedScanRepository;
    private final ShowRepository showRepository;
    private final ArtistRepository artistRepository;
    private final SettingsService settingsService;
    private final SharedScanReconciler reconciler;

    public SharedScanService(SharedScanRepository sharedScanRepository,
                              ShowRepository showRepository,
                              ArtistRepository artistRepository,
                              SettingsService settingsService,
                              SharedScanReconciler reconciler) {
        this.sharedScanRepository = sharedScanRepository;
        this.showRepository = showRepository;
        this.artistRepository = artistRepository;
        this.settingsService = settingsService;
        this.reconciler = reconciler;
    }

    /** Every shared scan {@code email} participates in. Empty for an unauthenticated caller. */
    public List<SharedScan> visibleTo(String email) {
        if (email == null || email.isBlank()) {
            return List.of();
        }
        return sharedScanRepository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(email, email);
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
     */
    @Transactional
    public SharedScan create(String label, String ownerA, String ownerB) {
        SharedScan scan = sharedScanRepository.save(
                new SharedScan(SharedScanOwner.newKey(), ownerA, ownerB, label));
        settingsService.getOrCreateSettings(scan.getOwnerKey());
        reconciler.reconcile(scan);
        return scan;
    }

    /** Update the shared scan's search location/window. Publishes SettingsChanged, which re-dues its scan jobs. */
    public SearchSettings updateSettings(SharedScan scan, String postalCode, int radiusMiles, int monthsAhead) {
        return settingsService.updateSettings(scan.getOwnerKey(), postalCode, radiusMiles, monthsAhead);
    }
}
