package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed round-trip of {@link Venue} through the real Postgres schema (Flyway
 * V24), not just the entity mapping -- proves the {@code (owner, normalized_name)} unique index
 * is actually enforced by the database. Mirrors {@code catalog.ArtistImportRepositoryTest}'s
 * shape (#177) and {@code ArtistImport}'s own {@code (owner, normalized_name)} uniqueness (#179).
 */
@SpringBootTest
@Testcontainers
class VenueRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private VenueRepository venueRepository;

    @BeforeEach
    void clean() {
        venueRepository.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("two venues with case-variant names for one owner collide on the unique index")
    void rejectsCaseVariantDuplicateForSameOwner() {
        venueRepository.insertIfAbsent("rob@example.com", "Cap City Comedy Club",
                ArtistNameNormalizer.normalize("Cap City Comedy Club"),
                "https://www.capcitycomedy.com/events", Instant.now());
        int second = venueRepository.insertIfAbsent("rob@example.com", "cap city COMEDY club",
                ArtistNameNormalizer.normalize("cap city COMEDY club"),
                "https://example.com/other", Instant.now());
        assertThat(second).isZero();
        assertThat(venueRepository.findByOwnerOrderByNameAsc("rob@example.com")).hasSize(1);
    }

    @Test
    @Transactional
    @DisplayName("the same venue name under two different owners is allowed")
    void allowsSameNameForDifferentOwners() {
        venueRepository.insertIfAbsent("a@example.com", "Cap City Comedy Club",
                ArtistNameNormalizer.normalize("Cap City Comedy Club"), "https://x/events", Instant.now());
        venueRepository.insertIfAbsent("b@example.com", "Cap City Comedy Club",
                ArtistNameNormalizer.normalize("Cap City Comedy Club"), "https://x/events", Instant.now());
        assertThat(venueRepository.findByOwnerOrderByNameAsc("a@example.com")).hasSize(1);
        assertThat(venueRepository.findByOwnerOrderByNameAsc("b@example.com")).hasSize(1);
    }
}
