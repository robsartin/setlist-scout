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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #255. V32 retires the 14 comma-split name fragments the 2026-08-18 SEED_LIST upload put in
 * the catalog, and deletes their scan jobs.
 *
 * <p>A data migration is written against production rows nobody can see from a test, so what is
 * actually worth pinning is the <em>predicate</em>: that it catches the fragments and nothing that
 * merely resembles them. Every assertion below is a row the migration must NOT touch --
 * {@code Peter, Paul and Mary} and {@code Peter Gabriel} (names the fragment is a prefix of, which
 * a {@code LIKE 'Peter%'} predicate would have destroyed), another owner's identical fragment,
 * an already-APPROVED row of the same name, and the scan jobs of a neighbouring artist.
 */
@Testcontainers
class RetireSeedListFragmentsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rob.sartin@gmail.com";

    @Test
    @DisplayName("retires only this owner's SEED_LIST fragments and only their scan jobs (#255)")
    void retiresOnlyTheFragments() throws Exception {
        // Migrate to V31 first: scan_job arrives in V6, so the rows this migration acts on cannot
        // exist until the schema that holds them does. Seeding between V31 and V32 is what makes
        // this a test of the migration rather than of an empty database.
        migrateTo("31");

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // The fragments themselves.
            insertArtist(s, OWNER, "Peter", "SEED_LIST", "SEED");
            insertArtist(s, OWNER, "Jr.", "SEED_LIST", "SEED");
            insertArtist(s, OWNER, "Lake & Palmer", "SEED_LIST", "SEED");

            // Must survive: the real artist the fragment is a prefix of. A LIKE predicate kills these.
            insertArtist(s, OWNER, "Peter, Paul and Mary", "MEMBER_EXPANSION", "APPROVED");
            insertArtist(s, OWNER, "Peter Gabriel", "SEED_LIST", "SEED");
            // Must survive: a legitimate one-word seed, proving this is not a short-name purge.
            insertArtist(s, OWNER, "Berlin", "SEED_LIST", "SEED");
            // Must survive: another owner's identical fragment -- the migration is owner-scoped.
            insertArtist(s, "someone.else@example.com", "Peter", "SEED_LIST", "SEED");
            // Must survive: same name, but already APPROVED rather than SEED.
            insertArtist(s, OWNER, "Guitar", "SEED_LIST", "APPROVED");

            // Scan jobs: two for a fragment, one for an artist that must keep its job.
            insertScanJob(s, OWNER, artistId(s, OWNER, "Peter", "SEED"), "ticketmaster");
            insertScanJob(s, OWNER, artistId(s, OWNER, "Peter", "SEED"), "band-site");
            insertScanJob(s, OWNER, artistId(s, OWNER, "Peter Gabriel", "SEED"), "ticketmaster");
        }

        assertThat(migrateTo("latest").success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(status(s, OWNER, "Peter")).isEqualTo("REMOVED");
            assertThat(status(s, OWNER, "Jr.")).isEqualTo("REMOVED");
            assertThat(status(s, OWNER, "Lake & Palmer")).isEqualTo("REMOVED");

            assertThat(status(s, OWNER, "Peter, Paul and Mary")).isEqualTo("APPROVED");
            assertThat(status(s, OWNER, "Peter Gabriel")).isEqualTo("SEED");
            assertThat(status(s, OWNER, "Berlin")).isEqualTo("SEED");
            assertThat(status(s, "someone.else@example.com", "Peter")).isEqualTo("SEED");
            assertThat(status(s, OWNER, "Guitar")).isEqualTo("APPROVED");

            // Nothing is deleted -- the 247 provenance edges in production depend on these rows.
            assertThat(count(s, "SELECT count(*) FROM artist")).isEqualTo(8);

            assertThat(count(s, "SELECT count(*) FROM scan_job")).isEqualTo(1);
            assertThat(count(s, "SELECT count(*) FROM scan_job j JOIN artist a ON a.id = j.artist_id"
                    + " WHERE a.name = 'Peter Gabriel'")).isEqualTo(1);
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

    private static void insertArtist(Statement s, String owner, String name, String source,
            String status) throws Exception {
        s.execute("INSERT INTO artist (owner, name, normalized_name, source, status, created_at)"
                + " VALUES (" + q(owner) + ", " + q(name) + ", "
                + q(ArtistNameNormalizer.normalize(name)) + ", " + q(source) + ", "
                + q(status) + ", now())");
    }

    private static void insertScanJob(Statement s, String owner, long artistId, String source)
            throws Exception {
        s.execute("INSERT INTO scan_job (owner, artist_id, source, status, next_due_at, attempts,"
                + " version) VALUES (" + q(owner) + ", " + artistId + ", " + q(source)
                + ", 'SCHEDULED', now(), 0, 0)");
    }

    private static long artistId(Statement s, String owner, String name, String status)
            throws Exception {
        ResultSet rs = s.executeQuery("SELECT id FROM artist WHERE owner = " + q(owner)
                + " AND name = " + q(name) + " AND status = " + q(status));
        rs.next();
        return rs.getLong(1);
    }

    private static String status(Statement s, String owner, String name) throws Exception {
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
