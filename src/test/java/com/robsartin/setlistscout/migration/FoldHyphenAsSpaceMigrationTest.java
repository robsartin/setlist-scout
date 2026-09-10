package com.robsartin.setlistscout.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #266: V34 folds a hyphen to a space in the normalizer and merges the duplicate pairs that
 * existed only because it did not.
 *
 * <p>Rows are seeded BETWEEN V33 and V34 so they exist under the old folding -- the only state from
 * which the merge is observable. Migrating an empty database proves nothing.
 */
@Testcontainers
class FoldHyphenAsSpaceMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "hyphen-space@example.com";
    private static final String OTHER = "someone-else@example.com";

    @Test
    @DisplayName("merges hyphen/space duplicate pairs keeping the ACTIVE row, and re-normalizes (#266)")
    void mergesHyphenSpaceDuplicates() throws Exception {
        migrateTo("33");

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // SEED + REJECTED -> the SEED row survives. One of the two live mixed-status pairs.
            insert(s, OWNER, "Jean-Michel Jarre", "SEED");
            insert(s, OWNER, "Jean Michel Jarre", "REJECTED");
            // The other live mixed pair, hyphen on the rejected side this time.
            insert(s, OWNER, "Jasmine Cephas Jones", "SEED");
            insert(s, OWNER, "Jasmine Cephas-Jones", "REJECTED");
            // Both active -- the shape that was being scanned twice.
            insert(s, OWNER, "Yo-Yo Ma", "APPROVED");
            insert(s, OWNER, "Yo Yo Ma", "APPROVED");
            // Must survive untouched: hyphenated, but no space-spelled twin. Its stored
            // normalized_name is still stale and the backfill must rewrite it.
            insert(s, OWNER, "T-Bone Walker", "APPROVED");
            // Must survive: deleting a separator is NOT what this does.
            insert(s, OWNER, "AC/DC", "APPROVED");
            insert(s, OWNER, "ACDC", "REJECTED");
            // Must survive: another owner's identical pair merges independently.
            insert(s, OTHER, "Yo-Yo Ma", "APPROVED");
            insert(s, OTHER, "Yo Yo Ma", "REJECTED");
        }

        assertThat(migrateTo("latest").success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(namesFor(s, OWNER, "jean michel jarre")).containsExactly("Jean-Michel Jarre");
            assertThat(statusOf(s, OWNER, "Jean-Michel Jarre")).isEqualTo("SEED");

            assertThat(namesFor(s, OWNER, "jasmine cephas jones")).containsExactly("Jasmine Cephas Jones");
            assertThat(statusOf(s, OWNER, "Jasmine Cephas Jones")).isEqualTo("SEED");

            assertThat(namesFor(s, OWNER, "yo yo ma")).hasSize(1);
            assertThat(namesFor(s, OWNER, "t bone walker")).containsExactly("T-Bone Walker");

            // A separator was substituted, not deleted: these two stay two artists.
            assertThat(namesFor(s, OWNER, "ac/dc")).containsExactly("AC/DC");
            assertThat(namesFor(s, OWNER, "acdc")).containsExactly("ACDC");

            assertThat(namesFor(s, OTHER, "yo yo ma")).containsExactly("Yo-Yo Ma");

            ResultSet rs = s.executeQuery("SELECT name, normalized_name FROM artist");
            while (rs.next()) {
                assertThat(rs.getString("normalized_name"))
                        .as("stored normalized_name for %s", rs.getString("name"))
                        .isEqualTo(ArtistNameNormalizer.normalize(rs.getString("name")));
            }

            // 11 seeded -> 7. This owner: 3 pairs collapse to 3, plus T-Bone Walker, AC/DC and
            // ACDC untouched = 6. The other owner's pair collapses to 1 of its own.
            assertThat(count(s, "SELECT count(*) FROM artist")).isEqualTo(7);
            assertThat(count(s, "SELECT count(*) FROM artist WHERE owner = " + q(OWNER))).isEqualTo(6);
            assertThat(count(s, "SELECT count(*) FROM artist WHERE owner = " + q(OTHER))).isEqualTo(1);
        }
    }

    private static MigrateResult migrateTo(String version) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (!"latest".equals(version)) {
            config = config.target(MigrationVersion.fromVersion(version));
        }
        return config.load().migrate();
    }

    private static void insert(Statement s, String owner, String name, String status) throws Exception {
        s.execute("INSERT INTO artist (owner, name, normalized_name, source, status, created_at)"
                + " VALUES (" + q(owner) + ", " + q(name) + ", " + q(oldNormalize(name))
                + ", 'SEED_LIST', " + q(status) + ", now())");
    }

    /**
     * The normalizer as it behaved BEFORE #266: identical except a hyphen stays a hyphen. Seeding
     * with the CURRENT normalizer would collapse each pair on the way in and the UNIQUE constraint
     * would reject the second row -- the duplicates must be created as production created them.
     *
     * <p>Masks the hyphen behind a sentinel the normalizer does not touch, normalizes, then restores
     * it, rather than re-implementing the folding rules a second time. The pre-#266 whitespace rule
     * (#157: whitespace touching a hyphen collapses away) is reapplied around the sentinel so the
     * seeded value matches what production actually stored.
     */
    private static String oldNormalize(String name) {
        String masked = name.replace('-', '\u00a7');
        String normalized = ArtistNameNormalizer.normalize(masked)
                .replaceAll("\\s*\u00a7\\s*", "\u00a7");
        return normalized.replace('\u00a7', '-');
    }

    private static List<String> namesFor(Statement s, String owner, String normalized) throws Exception {
        ResultSet rs = s.executeQuery("SELECT name FROM artist WHERE owner = " + q(owner)
                + " AND normalized_name = " + q(normalized) + " ORDER BY name");
        List<String> names = new ArrayList<>();
        while (rs.next()) {
            names.add(rs.getString(1));
        }
        return names;
    }

    private static String statusOf(Statement s, String owner, String name) throws Exception {
        ResultSet rs = s.executeQuery("SELECT status FROM artist WHERE owner = " + q(owner)
                + " AND name = " + q(name));
        rs.next();
        return rs.getString(1);
    }

    private static int count(Statement s, String sql) throws Exception {
        ResultSet rs = s.executeQuery(sql);
        rs.next();
        return rs.getInt(1);
    }

    private static String q(String v) {
        return "'" + v.replace("'", "''") + "'";
    }
}
