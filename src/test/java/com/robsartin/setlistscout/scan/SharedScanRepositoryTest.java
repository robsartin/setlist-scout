package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SharedScanRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";
    private static final String DAVID = "david@example.com";
    private static final String STRANGER = "stranger@example.com";

    @Autowired
    private SharedScanRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private SharedScan save(String a, String b) {
        return repository.save(new SharedScan(SharedScanOwner.newKey(), a, b, "Test pairing"));
    }

    @Test
    @DisplayName("both participants find the shared scan; a third party does not")
    void findsByEitherParticipant() {
        save(ROB, DAVID);

        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(ROB, ROB)).hasSize(1);
        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(DAVID, DAVID)).hasSize(1);
        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase(STRANGER, STRANGER)).isEmpty();
    }

    @Test
    @DisplayName("participant lookup ignores case -- OIDC casing must not decide access")
    void participantLookupIgnoresCase() {
        save(ROB, DAVID);

        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCase("ROB@EXAMPLE.COM", "ROB@EXAMPLE.COM"))
                .hasSize(1);
    }

    @Test
    @DisplayName("owner keys are unique and round-trip")
    void ownerKeyRoundTrips() {
        SharedScan saved = save(ROB, DAVID);

        assertThat(repository.findByOwnerKey(saved.getOwnerKey())).isPresent();
        assertThat(repository.findByOwnerKey("shared:nope")).isEmpty();
    }

    @Test
    @DisplayName("existsBy...AAndBOrAAndB finds a pairing in either direction, case-insensitively, "
            + "and not an unrelated pair -- the SharedScanService#create duplicate-pairing check")
    void existsByEitherDirectionFindsADuplicatePairing() {
        save(ROB, DAVID);

        assertThat(repository.existsByOwnerAIgnoreCaseAndOwnerBIgnoreCaseOrOwnerAIgnoreCaseAndOwnerBIgnoreCase(
                ROB, DAVID, DAVID, ROB)).as("same direction as stored").isTrue();
        assertThat(repository.existsByOwnerAIgnoreCaseAndOwnerBIgnoreCaseOrOwnerAIgnoreCaseAndOwnerBIgnoreCase(
                DAVID, ROB, ROB, DAVID)).as("reverse direction from stored").isTrue();
        assertThat(repository.existsByOwnerAIgnoreCaseAndOwnerBIgnoreCaseOrOwnerAIgnoreCaseAndOwnerBIgnoreCase(
                "ROB@EXAMPLE.COM", "DAVID@EXAMPLE.COM", "DAVID@EXAMPLE.COM", "ROB@EXAMPLE.COM"))
                .as("case-insensitive").isTrue();
        assertThat(repository.existsByOwnerAIgnoreCaseAndOwnerBIgnoreCaseOrOwnerAIgnoreCaseAndOwnerBIgnoreCase(
                ROB, STRANGER, STRANGER, ROB)).as("an unrelated pair is not a match").isFalse();
    }

    @Test
    @DisplayName("includes() and otherParticipant() are case-insensitive and reciprocal")
    void participantHelpers() {
        SharedScan scan = save(ROB, DAVID);

        assertThat(scan.includes("ROB@EXAMPLE.COM")).isTrue();
        assertThat(scan.includes(STRANGER)).isFalse();
        assertThat(scan.includes(null)).isFalse();
        assertThat(scan.otherParticipant(ROB)).isEqualTo(DAVID);
        assertThat(scan.otherParticipant(DAVID)).isEqualTo(ROB);
        assertThat(scan.otherParticipant(STRANGER)).isNull();
    }
}
