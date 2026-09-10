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
 * Issue #268: V36 folds a Latin diacritic into its base letter and merges the duplicate groups that
 * existed only because it did not.
 *
 * <p>Rows are seeded BETWEEN V35 and V36 so they exist under the old folding -- the only state from
 * which the merge is observable. Migrating an empty database proves nothing.
 *
 * <p>Each row is seeded with its stored {@code normalized_name} written out literally, rather than
 * derived by a helper. The pre-#268 key is just "the current key with its accents still on", and
 * spelling it out shows the reader exactly what production held. Deriving it would mean
 * re-implementing the folding rules a second time, in the test, to prove the first one.
 */
@Testcontainers
class FoldLatinDiacriticsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "diacritics@example.com";
    private static final String OTHER = "someone-else@example.com";

    @Test
    @DisplayName("merges accent duplicate groups keeping the ACTIVE row, and leaves non-Latin names byte-identical (#268)")
    void mergesAccentDuplicates() throws Exception {
        migrateTo("35");

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // Both active -- the shape that was being scanned twice. 8 such groups in production.
            insert(s, OWNER, "C\u00e9line Dion", "c\u00e9line dion", "APPROVED");
            insert(s, OWNER, "Celine Dion", "celine dion", "APPROVED");
            // Mixed, and a STROKE letter: the rejection is about the duplicate, so the hand-added
            // SEED must survive. NFD alone leaves the o-slash whole, so this also proves the
            // stroke-letter map runs inside the migration.
            insert(s, OWNER, "\u00d8ystein Sev\u00e5g", "\u00f8ystein sev\u00e5g", "SEED");
            insert(s, OWNER, "Oystein Sev\u00e2g", "oystein sev\u00e2g", "REJECTED");
            // A second stroke pair, both inactive.
            insert(s, OWNER, "Stanis\u0142aw Lem", "stanis\u0142aw lem", "REJECTED");
            insert(s, OWNER, "Stanislaw Lem", "stanislaw lem", "REJECTED");
            // THE rows this issue turns on. Japanese katakana encodes a voiced sound as base +
            // U+3099, a NON_SPACING_MARK: an unconditional mark-drop rewrites the word rather than
            // removing an accent, and the backfill below would write that to disk.
            insert(s, OWNER, "\u30df\u30c9\u30ea", "\u30df\u30c9\u30ea", "APPROVED");
            insert(s, OWNER, "\u30ad\u30d0\u30aa\u30d6\u30a2\u30ad\u30d0",
                    "\u30ad\u30d0\u30aa\u30d6\u30a2\u30ad\u30d0", "APPROVED");
            // Hangul decomposes to jamo, which are NOT marks and survive the filter -- but the key
            // would silently change from 3 characters to 8, and no Korean keyboard produces that.
            insert(s, OWNER, "\uae40\uc9c0\ud604", "\uae40\uc9c0\ud604", "APPROVED");
            // Hebrew and CJK: nothing here decomposes at all.
            insert(s, OWNER, "\u05d0\u05e1\u05e3 \u05e8\u05d9\u05d9\u05d6",
                    "\u05d0\u05e1\u05e3 \u05e8\u05d9\u05d9\u05d6", "APPROVED");
            insert(s, OWNER, "J.S. \u5df4\u8d6b", "j.s. \u5df4\u8d6b", "APPROVED");
            // Not folded by NFD and deliberately not folded by hand either.
            insert(s, OWNER, "Andreas Ihleb\u00e6k", "andreas ihleb\u00e6k", "APPROVED");
            // Another owner's identical pair merges independently, on its own statuses.
            insert(s, OTHER, "C\u00e9line Dion", "c\u00e9line dion", "REJECTED");
            insert(s, OTHER, "Celine Dion", "celine dion", "APPROVED");
        }

        assertThat(migrateTo("latest").success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // Two active rows collapse to one; APPROVED ties with APPROVED, so the older survives.
            assertThat(namesFor(s, OWNER, "celine dion")).containsExactly("C\u00e9line Dion");

            // PREFER_ACTIVE: the hand-added seed survives, the rejection does not resurrect.
            assertThat(namesFor(s, OWNER, "oystein sevag")).containsExactly("\u00d8ystein Sev\u00e5g");
            assertThat(statusOf(s, OWNER, "\u00d8ystein Sev\u00e5g")).isEqualTo("SEED");

            assertThat(namesFor(s, OWNER, "stanislaw lem")).hasSize(1);

            // THE assertions that pin the script-aware rule. An unconditional mark-drop would have
            // rewritten these to \u30df\u30c8\u30ea and \u30ad\u30cf\u30aa\u30d5\u30a2\u30ad\u30cf.
            assertThat(namesFor(s, OWNER, "\u30df\u30c9\u30ea")).containsExactly("\u30df\u30c9\u30ea");
            assertThat(namesFor(s, OWNER, "\u30ad\u30d0\u30aa\u30d6\u30a2\u30ad\u30d0"))
                    .containsExactly("\u30ad\u30d0\u30aa\u30d6\u30a2\u30ad\u30d0");
            assertThat(namesFor(s, OWNER, "\uae40\uc9c0\ud604")).containsExactly("\uae40\uc9c0\ud604");
            assertThat(namesFor(s, OWNER, "\u05d0\u05e1\u05e3 \u05e8\u05d9\u05d9\u05d6"))
                    .containsExactly("\u05d0\u05e1\u05e3 \u05e8\u05d9\u05d9\u05d6");
            assertThat(namesFor(s, OWNER, "j.s. \u5df4\u8d6b")).containsExactly("J.S. \u5df4\u8d6b");

            // Not folded: no twin, and the key keeps its ligature.
            assertThat(namesFor(s, OWNER, "andreas ihleb\u00e6k")).containsExactly("Andreas Ihleb\u00e6k");

            // The other owner's group merges on ITS statuses, not this owner's.
            assertThat(namesFor(s, OTHER, "celine dion")).containsExactly("Celine Dion");
            assertThat(statusOf(s, OTHER, "Celine Dion")).isEqualTo("APPROVED");

            // Nothing was emptied -- #118's ASCII strip collapsed every non-Latin name to one key.
            assertThat(count(s, "SELECT count(*) FROM artist WHERE normalized_name = ''")).isZero();

            ResultSet rs = s.executeQuery("SELECT name, normalized_name FROM artist");
            while (rs.next()) {
                assertThat(rs.getString("normalized_name"))
                        .as("stored normalized_name for %s", rs.getString("name"))
                        .isEqualTo(ArtistNameNormalizer.normalize(rs.getString("name")));
            }

            // 14 seeded -> 10. This owner: 3 pairs collapse to 3, plus the five non-Latin rows
            // and the ligature untouched = 9. The other owner's pair collapses to 1 of its own.
            assertThat(count(s, "SELECT count(*) FROM artist")).isEqualTo(10);
            assertThat(count(s, "SELECT count(*) FROM artist WHERE owner = " + q(OWNER))).isEqualTo(9);
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

    private static void insert(Statement s, String owner, String name, String storedKey, String status)
            throws Exception {
        s.execute("INSERT INTO artist (owner, name, normalized_name, source, status, created_at)"
                + " VALUES (" + q(owner) + ", " + q(name) + ", " + q(storedKey)
                + ", 'SEED_LIST', " + q(status) + ", now())");
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
