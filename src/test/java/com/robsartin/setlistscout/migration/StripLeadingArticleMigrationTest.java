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
 * Issue #267: V35 drops a leading definite article in the normalizer and merges the duplicate
 * groups that existed only because it did not.
 *
 * <p>Rows are seeded BETWEEN V34 and V35 so they exist under the old folding -- the only state from
 * which the merge is observable. Migrating an empty database proves nothing.
 */
@Testcontainers
class StripLeadingArticleMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "leading-article@example.com";
    private static final String OTHER = "someone-else@example.com";

    @Test
    @DisplayName("merges multi-word article duplicates keeping the ACTIVE row, and leaves single-word pairs alone (#267)")
    void mergesMultiWordArticleDuplicates() throws Exception {
        migrateTo("34");

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // Both active -- the shape that was being scanned twice. 33 such groups in production.
            insert(s, OWNER, "The Grateful Dead", "APPROVED");
            insert(s, OWNER, "Grateful Dead", "SEED");
            // Mixed: the rejection was about the DUPLICATE, so the approved row must survive.
            // This is the shape of all 36 mixed groups in production.
            insert(s, OWNER, "The Bill Evans Trio", "REJECTED");
            insert(s, OWNER, "Bill Evans Trio", "APPROVED");
            // Single-word: MUST NOT merge. The 2024 supergroup is not the English Beat, and the
            // normalizer deliberately refuses to decide. This assertion pins the narrowed scope.
            insert(s, OWNER, "The Beat", "APPROVED");
            insert(s, OWNER, "BEAT", "APPROVED");
            // Internal article: a different billing, not a duplicate.
            insert(s, OWNER, "Bruce Springsteen & The E Street Band", "APPROVED");
            insert(s, OWNER, "Bruce Springsteen", "APPROVED");
            // Begins with "the" and no separator: 56 such names in production. Truncating a
            // four-character prefix instead of the WORD would invent "e Oh Sees".
            insert(s, OWNER, "Thee Oh Sees", "APPROVED");
            // A name that is only the article. Must normalize to "the", never to empty -- an empty
            // key would collide every such row onto one.
            insert(s, OWNER, "The", "APPROVED");
            // No article twin at all: survives, but its stored normalized_name is stale and the
            // backfill must rewrite it.
            insert(s, OWNER, "The Allman Brothers Band", "APPROVED");
            // Another owner's identical pair merges independently, on its own statuses.
            insert(s, OTHER, "The Grateful Dead", "REJECTED");
            insert(s, OTHER, "Grateful Dead", "APPROVED");
        }

        assertThat(migrateTo("latest").success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // Two active rows collapse to one; SEED and APPROVED tie on rank, so the older row
            // (inserted first) survives.
            assertThat(namesFor(s, OWNER, "grateful dead")).containsExactly("The Grateful Dead");

            // PREFER_ACTIVE: the approved row survives and the rejection does not resurrect.
            assertThat(namesFor(s, OWNER, "bill evans trio")).containsExactly("Bill Evans Trio");
            assertThat(statusOf(s, OWNER, "Bill Evans Trio")).isEqualTo("APPROVED");

            // THE assertion that pins the narrowed scope: a single word keeps its article.
            assertThat(namesFor(s, OWNER, "the beat")).containsExactly("The Beat");
            assertThat(namesFor(s, OWNER, "beat")).containsExactly("BEAT");

            // Only a STRING-INITIAL article is dropped.
            assertThat(namesFor(s, OWNER, "bruce springsteen & the e street band"))
                    .containsExactly("Bruce Springsteen & The E Street Band");

            // The WORD "the" plus its separator, never a four-character prefix.
            assertThat(namesFor(s, OWNER, "thee oh sees")).containsExactly("Thee Oh Sees");

            // A name that is only the article survives as "the", not as "".
            assertThat(namesFor(s, OWNER, "the")).containsExactly("The");
            assertThat(count(s, "SELECT count(*) FROM artist WHERE normalized_name = ''")).isZero();

            assertThat(namesFor(s, OWNER, "allman brothers band"))
                    .containsExactly("The Allman Brothers Band");

            // The other owner's group merges on ITS statuses, not this owner's.
            assertThat(namesFor(s, OTHER, "grateful dead")).containsExactly("Grateful Dead");
            assertThat(statusOf(s, OTHER, "Grateful Dead")).isEqualTo("APPROVED");

            ResultSet rs = s.executeQuery("SELECT name, normalized_name FROM artist");
            while (rs.next()) {
                assertThat(rs.getString("normalized_name"))
                        .as("stored normalized_name for %s", rs.getString("name"))
                        .isEqualTo(ArtistNameNormalizer.normalize(rs.getString("name")));
            }

            // 13 seeded -> 10. This owner: 2 pairs collapse to 2, plus The Beat, BEAT, both
            // Springsteen billings, Thee Oh Sees, The and The Allman Brothers Band untouched = 9.
            // The other owner's pair collapses to 1 of its own.
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

    private static void insert(Statement s, String owner, String name, String status) throws Exception {
        s.execute("INSERT INTO artist (owner, name, normalized_name, source, status, created_at)"
                + " VALUES (" + q(owner) + ", " + q(name) + ", " + q(oldNormalize(name))
                + ", 'SEED_LIST', " + q(status) + ", now())");
    }

    /**
     * The normalizer as it behaved BEFORE #267: identical except a leading article stays. Seeding
     * with the CURRENT normalizer would collapse each pair on the way in and the UNIQUE constraint
     * would reject the second row -- the duplicates must be created as production created them.
     *
     * <p>Masks the article's separator behind a sentinel the normalizer does not touch, so the
     * leading-article rule cannot recognise it, then restores it -- rather than re-implementing the
     * folding rules a second time.
     */
    private static String oldNormalize(String name) {
        String masked = name.replaceFirst("(?i)^the\\s+", "The§");
        return ArtistNameNormalizer.normalize(masked).replace('§', ' ');
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
