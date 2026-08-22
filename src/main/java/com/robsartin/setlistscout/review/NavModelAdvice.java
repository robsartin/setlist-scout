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
     * The other allow-listed emails, excluding the CALLER's own address. Two consumers:
     * the admin's cross-account target-owner dropdown (#136, {@code shows.html}/{@code
     * candidates.html}), and the shared-scan create form's "Shared with" picker (#229, {@code
     * shared.html}).
     * <p>
     * Excluding the ADMIN's address unconditionally -- the pre-#229 behaviour -- was correct only
     * while the admin was the sole possible caller (creating a shared scan was admin-only). Once
     * any allow-listed user can create one, that would offer a non-admin "pair with yourself" in
     * the dropdown and never offer the admin at all. Excluding the CALLER's own address instead
     * is correct for both consumers: {@code shows.html}/{@code candidates.html} gate their
     * dropdown's container on {@code isAdmin}, so the caller there is always the admin, and for an
     * admin caller the two rules produce the identical list -- {@code NavModelAdviceTest} pins
     * that. Empty for everyone if no other user is configured yet.
     */
    @ModelAttribute("otherOwnerEmails")
    public List<String> otherOwnerEmails() {
        String self = currentUser.email();
        return appProperties.auth().allowedEmails().stream()
                .filter(email -> !email.equalsIgnoreCase(self))
                .toList();
    }
}
