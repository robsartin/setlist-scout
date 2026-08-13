package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Supplies the top-nav's pending-candidates badge to every page. */
@ControllerAdvice
public class NavModelAdvice {
    private final ArtistRepository artistRepository;
    private final CurrentUser currentUser;

    public NavModelAdvice(ArtistRepository artistRepository, CurrentUser currentUser) {
        this.artistRepository = artistRepository;
        this.currentUser = currentUser;
    }

    /** 0 when there's no authenticated principal (e.g. an error view) rather than a lookup with a null owner. */
    @ModelAttribute("pendingCount")
    public long pendingCount() {
        String owner = currentUser.email();
        if (owner == null) {
            return 0;
        }
        return artistRepository.countByOwnerAndStatus(owner, ArtistStatus.PENDING_REVIEW);
    }
}
