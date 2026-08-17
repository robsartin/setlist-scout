package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSeedService;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.catalog.SharedArtistFinder;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the artists under a shared scan's owner key equal to the normalized intersection of its
 * two participants' active artists (#163).
 * <p>
 * Both transitions go through {@code catalog}'s services rather than the repository, per CLAUDE.md.
 * That is not ceremony here: it is what publishes {@code ArtistActivated}/{@code ArtistDeactivated},
 * which is what makes {@code ScanJobListener} enqueue and cancel this shared scan's scan jobs. The
 * entire job lifecycle is inherited from that, not written here.
 */
@Service
public class SharedScanReconciler {

    private static final Logger log = LoggerFactory.getLogger(SharedScanReconciler.class);

    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    private final SharedScanRepository sharedScanRepository;
    private final SharedArtistFinder sharedArtistFinder;
    private final ArtistRepository artistRepository;
    private final ArtistSeedService seedService;
    private final ArtistActivationService activationService;

    public SharedScanReconciler(SharedScanRepository sharedScanRepository,
                                 SharedArtistFinder sharedArtistFinder,
                                 ArtistRepository artistRepository,
                                 ArtistSeedService seedService,
                                 ArtistActivationService activationService) {
        this.sharedScanRepository = sharedScanRepository;
        this.sharedArtistFinder = sharedArtistFinder;
        this.artistRepository = artistRepository;
        this.seedService = seedService;
        this.activationService = activationService;
    }

    /**
     * Brings {@code scan}'s artists into line with its participants' current intersection.
     *
     * @return how many artists are active under the shared key afterwards
     */
    public int reconcile(SharedScan scan) {
        String ownerKey = scan.getOwnerKey();
        List<String> shared = sharedArtistFinder.findSharedArtistNames(scan.getOwnerA(), scan.getOwnerB());

        Set<String> wanted = new HashSet<>();
        for (String name : shared) {
            wanted.add(ArtistNameNormalizer.normalize(name));
            // addSeedIfNew is idempotent and publishes ArtistActivated only when it actually
            // creates the row, so re-reconciling does not re-enqueue jobs.
            seedService.addSeedIfNew(ownerKey, name);
        }

        for (Artist existing : artistRepository.findByOwnerAndStatusIn(ownerKey, ACTIVE)) {
            if (!wanted.contains(ArtistNameNormalizer.normalize(existing.getName()))) {
                // REMOVED, not REJECTED: this artist was never a reviewed candidate, and REJECTED
                // would put it in a review queue that a shared scan has no reviewer for.
                activationService.changeStatus(existing.getId(), ownerKey, ArtistStatus.REMOVED);
            }
        }

        log.atInfo().addKeyValue("sharedScan", scan.getLabel()).addKeyValue("ownerKey", ownerKey)
                .addKeyValue("sharedArtists", shared.size()).log("shared scan reconciled");
        return shared.size();
    }

    @ApplicationModuleListener
    void onArtistActivated(ArtistActivated e) {
        onParticipantArtistChanged(e.owner(), e.artistId());
    }

    @ApplicationModuleListener
    void onArtistDeactivated(ArtistDeactivated e) {
        onParticipantArtistChanged(e.owner(), e.artistId());
    }

    /**
     * Re-reconciles every shared scan the given owner participates in.
     * <p>
     * <b>The early return states an invariant, not a workaround for today's query shape.</b> A
     * shared scan is never a participant in another shared scan -- its {@code ownerA}/{@code
     * ownerB} are always the two real participant addresses, never another scan's synthetic key --
     * so there is nothing to reconcile for one in any case. That happens to also mean {@link
     * #reconcile} creating artists under a shared key, which publishes {@code ArtistActivated} for
     * that key and arrives back here, cannot form a loop today: {@link SharedScanRepository
     * #findByOwnerAIgnoreCaseOrOwnerBIgnoreCase} matches only the participant columns, so it comes
     * back empty for a shared key regardless of this guard (confirmed empirically -- Task 4 fix
     * round, #163: removing this early return did not reproduce a loop, hang, or any test
     * failure). This guard is what would still hold if that incidental fact ever stopped being
     * true -- the lookup broadening, or some other publisher emitting {@code ArtistActivated} for
     * a shared key -- so it stays, but it is defense-in-depth for the stated invariant, not the
     * thing load-bearing against recursion today.
     * <p>
     * Package-private rather than private so the reconcile-on-participant-change path can be driven
     * directly in tests without publishing an event and waiting on async delivery.
     */
    void onParticipantArtistChanged(String owner, Long artistId) {
        if (SharedScanOwner.isSharedScanKey(owner)) {
            return;
        }
        for (SharedScan scan : sharedScanRepository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(owner, owner)) {
            reconcile(scan);
        }
    }
}
