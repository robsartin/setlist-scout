package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
}
