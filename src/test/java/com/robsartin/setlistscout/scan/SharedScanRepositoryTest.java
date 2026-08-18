package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private SharedScan save(String a, String b) {
        return repository.save(new SharedScan(SharedScanOwner.newKey(), a, b, "Test pairing"));
    }

    /** Backdates a row's {@code created_at} straight through JDBC -- the entity has no setter for it on purpose. */
    private void setCreatedAt(Long id, Instant createdAt) {
        jdbcTemplate.update("UPDATE shared_scan SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
    }

    @Test
    @DisplayName("both participants find the shared scan; a third party does not")
    void findsByEitherParticipant() {
        save(ROB, DAVID);

        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCaseOrderByCreatedAtAscIdAsc(ROB, ROB)).hasSize(1);
        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCaseOrderByCreatedAtAscIdAsc(DAVID, DAVID)).hasSize(1);
        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCaseOrderByCreatedAtAscIdAsc(STRANGER, STRANGER)).isEmpty();
    }

    @Test
    @DisplayName("participant lookup ignores case -- OIDC casing must not decide access")
    void participantLookupIgnoresCase() {
        save(ROB, DAVID);

        assertThat(repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCaseOrderByCreatedAtAscIdAsc(
                "ROB@EXAMPLE.COM", "ROB@EXAMPLE.COM")).hasSize(1);
    }

    // ---- #187: the page rendered whatever scans.get(0) of this UNORDERED query happened to
    // return -- stable only by accident of Postgres's incidental scan order. These two pin the
    // now-explicit "created_at then id, oldest first" order the page's default relies on.

    @Test
    @DisplayName("orders by createdAt ascending, oldest first -- independent of insertion/id order")
    void ordersByCreatedAtAscendingRegardlessOfIdOrder() {
        SharedScan insertedFirst = save(ROB, DAVID);
        SharedScan insertedSecond = save(ROB, STRANGER);
        // Backdate the row inserted SECOND (higher id) so it is actually the OLDER pairing by
        // created_at. Only a real ORDER BY -- not a coincidental match with insertion/id order --
        // can put it first.
        setCreatedAt(insertedSecond.getId(), Instant.now().minusSeconds(3600));

        List<SharedScan> found = repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCaseOrderByCreatedAtAscIdAsc(ROB, ROB);

        assertThat(found).extracting(SharedScan::getId)
                .containsExactly(insertedSecond.getId(), insertedFirst.getId());
    }

    @Test
    @DisplayName("ties on createdAt break by id ascending")
    void tiesOnCreatedAtBreakByIdAscending() {
        SharedScan insertedFirst = save(ROB, DAVID);
        SharedScan insertedSecond = save(ROB, STRANGER);
        Instant sameInstant = Instant.now();
        setCreatedAt(insertedFirst.getId(), sameInstant);
        setCreatedAt(insertedSecond.getId(), sameInstant);

        List<SharedScan> found = repository.findByOwnerAIgnoreCaseOrOwnerBIgnoreCaseOrderByCreatedAtAscIdAsc(ROB, ROB);

        assertThat(found).extracting(SharedScan::getId)
                .containsExactly(insertedFirst.getId(), insertedSecond.getId());
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
