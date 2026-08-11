package com.robsartin.setlistscout.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Resolves the current owner (the signed-in user's email) from the security context.
 * Every user-owned query and mutation is scoped by this value. Injected rather than read
 * inline so controllers stay unit-testable with a stubbed owner.
 */
@Component
public class CurrentUser {

    /** The signed-in user's email, or null if there is no authenticated OIDC user. */
    public String email() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser) {
            return oidcUser.getEmail();
        }
        return null;
    }
}
