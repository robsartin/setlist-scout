package com.robsartin.setlistscout.shared;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.service.TestAppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminGuardTest {

    /** TestAppProperties#withKeys sets adminEmail to "owner@example.com". */
    private static final String ADMIN = "owner@example.com";

    private AdminGuard guardFor(String signedInEmail) {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(signedInEmail);
        AppProperties properties = TestAppProperties.withKeys();
        return new AdminGuard(currentUser, properties);
    }

    @Test
    @DisplayName("the configured admin is admin, and require() lets them through")
    void adminPasses() {
        AdminGuard guard = guardFor(ADMIN);
        assertThat(guard.isAdmin()).isTrue();
        guard.require();
    }

    @Test
    @DisplayName("admin match is case-insensitive -- OIDC casing must not decide access")
    void adminMatchIgnoresCase() {
        assertThat(guardFor("OWNER@EXAMPLE.COM").isAdmin()).isTrue();
    }

    @Test
    @DisplayName("a non-admin user is refused with 403")
    void nonAdminIsForbidden() {
        AdminGuard guard = guardFor("someone-else@example.com");
        assertThat(guard.isAdmin()).isFalse();
        assertThatThrownBy(guard::require)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("no authenticated principal is refused, not treated as admin")
    void nullEmailIsForbidden() {
        AdminGuard guard = guardFor(null);
        assertThat(guard.isAdmin()).isFalse();
        assertThatThrownBy(guard::require).isInstanceOf(ResponseStatusException.class);
    }
}
