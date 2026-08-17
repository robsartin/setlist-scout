package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.AdminGuard;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/** Supplies the top-nav's pending-candidates badge, plus the admin-only UI flags (#136), to every page. */
@ControllerAdvice
public class NavModelAdvice {
    private final ArtistRepository artistRepository;
    private final CurrentUser currentUser;
    private final AppProperties appProperties;
    private final AdminGuard adminGuard;

    public NavModelAdvice(ArtistRepository artistRepository, CurrentUser currentUser, AppProperties appProperties,
                           AdminGuard adminGuard) {
        this.artistRepository = artistRepository;
        this.currentUser = currentUser;
        this.appProperties = appProperties;
        this.adminGuard = adminGuard;
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

    /**
     * True only for the configured admin (#136) -- this alone drives whether the cross-account
     * "Scan now" / "Run expansion now" controls render at all. This is a UI convenience only, not
     * the security boundary: the admin endpoints re-check this same config themselves
     * ({@code AdminGuard#require}), since a hidden button is not an access control.
     */
    @ModelAttribute("isAdmin")
    public boolean isAdmin() {
        return adminGuard.isAdmin();
    }

    /**
     * The other allow-listed emails (excluding the admin's own), for the admin's target-owner
     * dropdown (#136). Empty for everyone, including the admin, if no other user is configured yet.
     */
    @ModelAttribute("otherOwnerEmails")
    public List<String> otherOwnerEmails() {
        String admin = appProperties.auth().adminEmail();
        return appProperties.auth().allowedEmails().stream()
                .filter(email -> !email.equalsIgnoreCase(admin))
                .toList();
    }
}
