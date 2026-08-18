package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class SharedScanServiceTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";
    private static final String DAVID = "david@example.com";
    private static final String STRANGER = "stranger@example.com";
    /** Deliberately NOT in {@link #authProperties} below -- the fixture for the not-allow-listed rejection. */
    private static final String NOT_ALLOWED = "nobody@example.com";

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("setlistscout.auth.allowed-emails", () -> ROB + "," + DAVID + "," + STRANGER);
    }

    @Autowired private SharedScanService service;
    @Autowired private SharedScanRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("both participants see it; a third party sees nothing")
    void visibilityIsParticipantBased() {
        service.create("Rob & David", ROB, DAVID);

        assertThat(service.visibleTo(ROB)).hasSize(1);
        assertThat(service.visibleTo(DAVID)).hasSize(1);
        assertThat(service.visibleTo(STRANGER)).isEmpty();
    }

    /**
     * #187: a user in two pairings must see BOTH, not just one -- {@code SharedScanController} used
     * to render only {@code scans.get(0)}, making the second one unreachable. Also pins the default
     * order (oldest first) the page's default selection relies on for stability across page loads.
     */
    @Test
    @DisplayName("a user in two pairings sees both, oldest first")
    void visibleToReturnsEveryPairingAParticipantIsIn() {
        SharedScan first = service.create("Rob & David", ROB, DAVID);
        SharedScan second = service.create("Rob & Stranger", ROB, STRANGER);

        assertThat(service.visibleTo(ROB)).extracting(SharedScan::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("visibility ignores address case")
    void visibilityIgnoresCase() {
        service.create("Rob & David", ROB, DAVID);

        assertThat(service.visibleTo("DAVID@EXAMPLE.COM")).hasSize(1);
    }

    @Test
    @DisplayName("a non-participant requesting one by id gets 404, not the scan")
    void nonParticipantCannotFetchById() {
        SharedScan scan = service.create("Rob & David", ROB, DAVID);

        assertThat(service.requireVisible(ROB, scan.getId())).isNotNull();
        assertThatThrownBy(() -> service.requireVisible(STRANGER, scan.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("an unauthenticated caller sees nothing and cannot fetch")
    void nullEmailSeesNothing() {
        SharedScan scan = service.create("Rob & David", ROB, DAVID);

        assertThat(service.visibleTo(null)).isEmpty();
        assertThatThrownBy(() -> service.requireVisible(null, scan.getId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("creating one gives it a synthetic owner key and its own settings row")
    void createProvisionsOwnerKeyAndSettings() {
        SharedScan scan = service.create("Rob & David", ROB, DAVID);

        assertThat(scan.getOwnerKey()).startsWith("shared:");
        assertThat(service.settingsFor(scan)).isNotNull();
    }

    // ---- Finding 2 of the 2026-08-16 whole-branch review: create() had no duplicate check, no
    // self-pairing check, and no allow-list check on ownerB. None of these are visible on any
    // page (the page renders only scans.get(0) of an unordered query, and there is no delete
    // endpoint), which is exactly why each is worth rejecting up front rather than leaving as a
    // silent, permanently-doubled scan.

    @Test
    @DisplayName("creating the same pairing twice is rejected, in either direction")
    void duplicatePairingIsRejectedInEitherDirection() {
        service.create("Rob & David", ROB, DAVID);

        assertThatThrownBy(() -> service.create("Rob & David Again", ROB, DAVID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.create("David & Rob", DAVID, ROB))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("pairing an address with itself is rejected, case-insensitively")
    void selfPairingIsRejected() {
        assertThatThrownBy(() -> service.create("Solo", ROB, ROB))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.create("Solo", ROB, "ROB@EXAMPLE.COM"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("pairing with an address that isn't allow-listed is rejected")
    void nonAllowListedOwnerBIsRejected() {
        assertThatThrownBy(() -> service.create("Rob & Nobody", ROB, NOT_ALLOWED))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }
}
