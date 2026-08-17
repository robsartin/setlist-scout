package com.robsartin.setlistscout.shared;

import com.robsartin.setlistscout.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single definition of "is the current user the configured admin" (#136).
 * <p>
 * Extracted at #163, when a third endpoint needed it. Before this it lived as byte-identical
 * {@code private void requireAdmin()} copies in {@code ShowController} and {@code ReviewController}
 * plus the same predicate inline in {@code NavModelAdvice#isAdmin} -- four hand-maintained copies of
 * one security rule, which is how such rules drift apart.
 * <p>
 * Still a config check rather than a roles system, exactly as #136 decided: a Role enum, table, and
 * admin-toggle UI would be real infrastructure for an app with two allowed users. Revisit if it
 * grows past that. {@link #isAdmin()} drives UI visibility only -- a hidden button is not access
 * control, so every admin endpoint calls {@link #require()} itself.
 */
@Component
public class AdminGuard {

    private final CurrentUser currentUser;
    private final AppProperties appProperties;

    public AdminGuard(CurrentUser currentUser, AppProperties appProperties) {
        this.currentUser = currentUser;
        this.appProperties = appProperties;
    }

    /** True only for the configured admin. False when nobody is signed in. */
    public boolean isAdmin() {
        String owner = currentUser.email();
        return owner != null && owner.equalsIgnoreCase(appProperties.auth().adminEmail());
    }

    /** Throws {@code 403 FORBIDDEN} unless the current user is the configured admin. */
    public void require() {
        if (!isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
