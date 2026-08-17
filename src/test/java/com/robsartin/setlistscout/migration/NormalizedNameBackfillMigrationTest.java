package com.robsartin.setlistscout.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #176: {@code V19__add_artist_normalized_name} backfills {@code normalized_name} for every
 * row that already existed before it ran -- the riskiest part of the migration, since it runs once
 * against the full live catalog (13,236 rows for the main user) and can never be re-triggered for
 * a row it got wrong. {@link NormalizedNameColumnTest} only proves LIVE writes made after V19 get
 * this right; it never seeds a row before V19 runs, so it cannot see the backfill at all.
 *
 * <p>Builds a populated pre-V19 DB (migrated through V18) with one hand-seeded legacy row per fold
 * rule {@link ArtistNameNormalizer}'s javadoc documents -- an en dash, a curly apostrophe,
 * whitespace touching a hyphen (#157), and a non-Latin name -- migrates to latest (V19 runs the
 * backfill), and asserts each row's stored {@code normalized_name} equals
 * {@link ArtistNameNormalizer#normalize(String)} of its ORIGINAL name, computed fresh rather than
 * hardcoded. That specific shape matters: a backfill secretly swapped for a cheaper, hand-rolled
 * SQL equivalent (e.g. bare {@code lower(name)}) would still lowercase every row and so would pass
 * a hardcoded-lowercase check, but would leave the en dash, curly quote, and hyphen-spacing
 * un-folded for every pre-existing row -- exactly the silent drift V19's own javadoc warns about.
 * Runs in CI (needs Docker).
 */
@Testcontainers
class NormalizedNameBackfillMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rob@example.com";

    @Test
    void backfillsNormalizedNameForEveryPreExistingFoldRule() throws Exception {
        // 1. Migrate up to V18 (pre-V19 state): normalized_name does not exist yet.
        Flyway toV18 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .target("18")
                .load();
        MigrateResult v18Result = toV18.migrate();
        assertThat(v18Result.success).isTrue();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        // 2. Seed legacy rows -- pre-existing catalog entries the real backfill has to get right,
        // one per fold rule the normalizer documents.
        String enDashName = "Emerson, Lake – Palmer";
        String curlyApostropheName = "Guns N’ Roses";
        String hyphenSpacingName = "Emerson Lake - Palmer Live";
        String nonLatinName = "ゆず";

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            insertLegacyArtist(s, enDashName, t0);
            insertLegacyArtist(s, curlyApostropheName, t0.plusSeconds(1));
            insertLegacyArtist(s, hyphenSpacingName, t0.plusSeconds(2));
            insertLegacyArtist(s, nonLatinName, t0.plusSeconds(3));
        }

        // 3. Pre-migration baseline: the column does not exist at all yet, confirming these rows
        // are genuinely pre-V19 and not accidentally exercising a live write path instead.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            ResultSet columnExists = s.executeQuery(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_name = 'artist' AND column_name = 'normalized_name'");
            columnExists.next();
            assertThat(columnExists.getInt(1)).as("normalized_name column absent before V19").isZero();
        }

        // 4. Migrate to latest (V19 runs the backfill).
        Flyway toLatest = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult latestResult = toLatest.migrate();
        assertThat(latestResult.success).isTrue();

        // 5. Every pre-existing row now carries the REAL normalizer's output.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            String enDashStored = storedNormalizedName(s, enDashName);
            assertThat(enDashStored).as("en dash folded to hyphen, whitespace around it collapsed")
                    .isEqualTo(ArtistNameNormalizer.normalize(enDashName));
            assertThat(enDashStored).as("not merely lowercased").doesNotContain("–").contains("lake-palmer");

            String curlyStored = storedNormalizedName(s, curlyApostropheName);
            assertThat(curlyStored).as("curly apostrophe folded to straight")
                    .isEqualTo(ArtistNameNormalizer.normalize(curlyApostropheName));
            assertThat(curlyStored).as("not merely lowercased").doesNotContain("’").contains("n' roses");

            String hyphenSpacingStored = storedNormalizedName(s, hyphenSpacingName);
            assertThat(hyphenSpacingStored).as("whitespace touching a hyphen collapsed (#157)")
                    .isEqualTo(ArtistNameNormalizer.normalize(hyphenSpacingName));
            assertThat(hyphenSpacingStored).as("not merely lowercased")
                    .doesNotContain(" - ").contains("lake-palmer");

            String nonLatinStored = storedNormalizedName(s, nonLatinName);
            assertThat(nonLatinStored)
                    .as("non-Latin name preserved -- not ASCII-stripped to an empty/collapsed key")
                    .isEqualTo(ArtistNameNormalizer.normalize(nonLatinName))
                    .isEqualTo(nonLatinName)
                    .isNotBlank();
        }
    }

    private static void insertLegacyArtist(Statement s, String name, Instant createdAt) throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist (owner, name, source, status, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, OWNER);
            ps.setString(2, name);
            ps.setString(3, "SEED_LIST");
            ps.setString(4, "SEED");
            ps.setTimestamp(5, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
    }

    private static String storedNormalizedName(Statement s, String name) throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "SELECT normalized_name FROM artist WHERE owner = ? AND name = ?")) {
            ps.setString(1, OWNER);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("seeded row exists for " + name).isTrue();
                return rs.getString("normalized_name");
            }
        }
    }
}
