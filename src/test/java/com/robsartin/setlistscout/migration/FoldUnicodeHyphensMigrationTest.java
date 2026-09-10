package com.robsartin.setlistscout.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import org.flywaydb.core.Flyway;
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
 * Issue #261: V33 folds U+2010/U+2011 into the normalizer and merges the duplicate pairs that
 * existed only because it did not.
 *
 * <p>Rows are seeded BETWEEN V32 and V33, so they exist under the old folding -- the only state
 * from which the merge is observable. Migrating an empty database proves nothing.
 *
 * <p>The normalized_name values asserted below carry SPACES where this test originally used
 * hyphens: #266 (V34) later folds a hyphen to a space, and "latest" runs it. What V33 does is
 * unchanged -- only the spelling of the key it lands on moved.
 *
 * <p>All four live status shapes are covered, because the survivor policy is the decision this
 * migration turns on: APPROVED+REJECTED, SEED+REJECTED, SEED+APPROVED, and same-status.
 */
@Testcontainers
class FoldUnicodeHyphensMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "hyphen-merge@example.com";
    private static final String OTHER = "someone-else@example.com";
    private static final char HYPHEN = '\u2010';
    private static final char NB_HYPHEN = '\u2011';
    /** Stands in for the new characters while reproducing the OLD normalizer -- see oldNormalize. */
    private static final char SENTINEL = '\u00a7';

    @Test
    @DisplayName("merges U+2010/U+2011 duplicate pairs keeping the ACTIVE row, and re-normalizes (#261)")
    void mergesUnicodeHyphenDuplicates() throws Exception {
        migrateTo("32");

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // APPROVED + REJECTED -> the APPROVED row survives. This is the policy inversion.
            insert(s, OWNER, "Blue Note All-Stars", "APPROVED");
            insert(s, OWNER, "Blue Note All" + HYPHEN + "Stars", "REJECTED");
            // SEED + REJECTED -> the SEED row survives.
            insert(s, OWNER, "Yo" + NB_HYPHEN + "Yo Ma & Kathryn Stott", "REJECTED");
            insert(s, OWNER, "Yo-Yo Ma & Kathryn Stott", "SEED");
            // SEED + APPROVED -> SEED outranks; unchanged by the policy either way.
            insert(s, OWNER, "Pousette-Dart Band", "SEED");
            insert(s, OWNER, "Pousette" + HYPHEN + "Dart Band", "APPROVED");
            // Same status -> policy irrelevant; oldest/lowest id survives.
            insert(s, OWNER, "Drive-By Truckers", "APPROVED");
            insert(s, OWNER, "Drive" + HYPHEN + "By Truckers", "APPROVED");
            // No duplicate, but carries U+2011: its stored normalized_name is stale and the
            // backfill must rewrite it even though nothing merged.
            insert(s, OWNER, "Lone" + NB_HYPHEN + "Wolf", "APPROVED");
            // Another owner's identical pair merges independently, never across owners.
            insert(s, OTHER, "Blue Note All-Stars", "APPROVED");
            insert(s, OTHER, "Blue Note All" + HYPHEN + "Stars", "REJECTED");
            // A genuinely different name that merely shares a prefix must not be touched.
            insert(s, OWNER, "Blue Note All-Stars Revisited", "APPROVED");
        }

        assertThat(migrateTo("latest").success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(namesFor(s, OWNER, "blue note all stars")).containsExactly("Blue Note All-Stars");
            assertThat(statusOf(s, OWNER, "Blue Note All-Stars")).isEqualTo("APPROVED");

            assertThat(namesFor(s, OWNER, "yo yo ma & kathryn stott"))
                    .containsExactly("Yo-Yo Ma & Kathryn Stott");
            assertThat(statusOf(s, OWNER, "Yo-Yo Ma & Kathryn Stott")).isEqualTo("SEED");

            assertThat(namesFor(s, OWNER, "pousette dart band")).containsExactly("Pousette-Dart Band");
            assertThat(statusOf(s, OWNER, "Pousette-Dart Band")).isEqualTo("SEED");

            assertThat(namesFor(s, OWNER, "drive by truckers")).hasSize(1);

            assertThat(namesFor(s, OWNER, "lone wolf")).containsExactly("Lone" + NB_HYPHEN + "Wolf");
            assertThat(namesFor(s, OWNER, "blue note all stars revisited")).hasSize(1);
            assertThat(namesFor(s, OTHER, "blue note all stars")).containsExactly("Blue Note All-Stars");

            ResultSet rs = s.executeQuery("SELECT name, normalized_name FROM artist");
            while (rs.next()) {
                assertThat(rs.getString("normalized_name"))
                        .as("stored normalized_name for %s", rs.getString("name"))
                        .isEqualTo(ArtistNameNormalizer.normalize(rs.getString("name")));
            }

            // 12 rows seeded -> 7. This owner: 4 pairs collapse to 4, plus Lone-Wolf and
            // "Blue Note All-Stars Revisited" untouched = 6. The other owner's own pair
            // collapses to 1 of its own, never merged across the owner boundary.
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
            config = config.target(org.flywaydb.core.api.MigrationVersion.fromVersion(version));
        }
        return config.load().migrate();
    }

    private static void insert(Statement s, String owner, String name, String status) throws Exception {
        s.execute("INSERT INTO artist (owner, name, normalized_name, source, status, created_at)"
                + " VALUES (" + q(owner) + ", " + q(name) + ", " + q(oldNormalize(name))
                + ", 'SEED_LIST', " + q(status) + ", now())");
    }

    /**
     * The normalizer as it behaved BEFORE #261: identical except that U+2010/U+2011 pass through
     * unfolded. Seeding with the CURRENT normalizer would collapse each pair on the way in and the
     * UNIQUE (owner, normalized_name) constraint would reject the second row -- the duplicates have
     * to be created exactly as production created them.
     *
     * <p>Swaps the two characters for a sentinel the normalizer does not touch, normalizes, then
     * swaps back, rather than re-implementing the folding rules a second time.
     */
    private static String oldNormalize(String name) {
        String masked = name.replace(HYPHEN, SENTINEL).replace(NB_HYPHEN, SENTINEL);
        String normalized = ArtistNameNormalizer.normalize(masked);
        StringBuilder out = new StringBuilder();
        int seen = 0;
        for (char ch : normalized.toCharArray()) {
            if (ch == SENTINEL) {
                out.append(name.indexOf(NB_HYPHEN) >= 0 && seen == 0 ? NB_HYPHEN : HYPHEN);
                seen++;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
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
