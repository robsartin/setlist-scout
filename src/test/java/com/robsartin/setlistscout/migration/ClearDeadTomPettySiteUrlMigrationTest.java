package com.robsartin.setlistscout.migration;

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
 * Issue #147: production's cached {@code artist.official_site_url} for "Tom petty" is a dead 1996
 * Wayback Machine snapshot ({@code https://web.archive.org/web/19961222160524/http://www.wbr.com/tompetty/})
 * of Warner Bros' old tompetty page -- MusicBrainz's "official homepage" relation apparently still
 * points at it, and {@code ScanUnitRunner#resolveSiteUrl} caches the official-site URL once and
 * reuses it forever, so every band-site scan for this artist has been timing out
 * ({@code SocketTimeoutException}), contributing zero shows from that source.
 *
 * <p>Scoped to the immediate data fix only -- per #147's own text, a general staleness-detection
 * mechanism ("N consecutive failures clears the cache") is a separate, deferred design question.
 * {@code V16} is a narrow, targeted {@code UPDATE}, not that general mechanism: it NULLs the cached
 * URL only where BOTH the artist name matches "Tom petty" (case-insensitive) AND the cached URL is
 * that exact dead value, so {@code ScanUnitRunner#resolveSiteUrl}
 * ({@code if (url == null) { ...musicBrainz.findOfficialHomepage(...)... } }) re-resolves it via
 * MusicBrainz on the artist's next scan.
 *
 * <p>Seeds six rows covering: the real target (name + exact dead URL), a case-insensitive name
 * variant of the target (proves the match is case-insensitive as specified), a different artist with
 * a different valid-looking URL (proves the clear isn't a blanket wipe), a "Tom petty" row with a
 * DIFFERENT cached URL (proves the URL must match too, not just the name -- exactly the "don't clear
 * a URL that happens to be fine for some other reason" risk the issue calls out), a differently-named
 * row that happens to carry the dead URL (proves the name must match too, not just the URL), and a
 * "Tom petty" row with no cached URL at all (proves the WHERE clause's {@code = 'dead url'} comparison
 * is NULL-safe and doesn't error or misbehave). Runs in CI (needs Docker).
 */
@Testcontainers
class ClearDeadTomPettySiteUrlMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String DEAD_URL =
            "https://web.archive.org/web/19961222160524/http://www.wbr.com/tompetty/";

    private static final String OWNER_TARGET = "owner-a@example.com";
    private static final String OWNER_CASE_VARIANT = "owner-b@example.com";
    private static final String OWNER_OTHER_ARTIST = "owner-c@example.com";
    private static final String OWNER_WRONG_URL = "owner-d@example.com";
    private static final String OWNER_WRONG_NAME = "owner-e@example.com";
    private static final String OWNER_NO_URL = "owner-f@example.com";

    @Test
    void clearsOnlyTheExactDeadUrlOnAMatchingArtistName() throws Exception {
        // 1. Migrate up to V15 (pre-V16 state) so the artist table exists.
        Flyway toV15 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .target("15")
                .load();
        MigrateResult v15Result = toV15.migrate();
        assertThat(v15Result.success).isTrue();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        long target;
        long caseVariant;
        long otherArtist;
        long wrongUrl;
        long wrongName;
        long noUrl;

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            target = insertArtist(s, OWNER_TARGET, "Tom petty", "SEED_LIST", "APPROVED",
                    "Traveling Wilburys", "original artist deceased 2017", DEAD_URL, t0);
            caseVariant = insertArtist(s, OWNER_CASE_VARIANT, "TOM PETTY", "SEED_LIST", "PENDING_REVIEW",
                    null, null, DEAD_URL, t0);
            otherArtist = insertArtist(s, OWNER_OTHER_ARTIST, "Radiohead", "SEED_LIST", "APPROVED",
                    null, null, "https://www.radiohead.com", t0);
            wrongUrl = insertArtist(s, OWNER_WRONG_URL, "Tom petty", "SEED_LIST", "APPROVED",
                    null, null, "https://www.tompetty.com", t0);
            wrongName = insertArtist(s, OWNER_WRONG_NAME, "Warner Bros Artist", "SEED_LIST", "APPROVED",
                    null, null, DEAD_URL, t0);
            noUrl = insertArtist(s, OWNER_NO_URL, "Tom petty", "SEED_LIST", "APPROVED",
                    null, null, null, t0);
        }

        // 2. Pre-migration baseline: 6 seeded rows.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(count(s, "artist")).as("seeded artist count before V16").isEqualTo(6);
        }

        // 3. Migrate to latest (V16 runs the targeted clear).
        Flyway toLatest = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult latestResult = toLatest.migrate();
        assertThat(latestResult.success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 4. No row deleted -- only the column is nulled, never the row.
            assertThat(count(s, "artist")).as("no rows deleted by V16").isEqualTo(6);

            // 5. The target row: URL cleared, everything else about the row untouched.
            assertOfficialSiteUrl(s, target, null);
            assertRowUnchangedExceptUrl(s, target, OWNER_TARGET, "Tom petty", "SEED_LIST", "APPROVED",
                    "Traveling Wilburys", "original artist deceased 2017", t0);

            // 6. Case-insensitive name variant is also cleared.
            assertOfficialSiteUrl(s, caseVariant, null);

            // 7. A different artist with a different, valid-looking URL is left untouched.
            assertOfficialSiteUrl(s, otherArtist, "https://www.radiohead.com");

            // 8. Same name, but a DIFFERENT cached URL -- name alone must not be enough to match.
            assertOfficialSiteUrl(s, wrongUrl, "https://www.tompetty.com");

            // 9. The exact dead URL, but a DIFFERENT artist name -- URL alone must not be enough to match.
            assertOfficialSiteUrl(s, wrongName, DEAD_URL);

            // 10. No cached URL at all -- the NULL-safe no-op case.
            assertOfficialSiteUrl(s, noUrl, null);
        }
    }

    private static long insertArtist(Statement s, String owner, String name, String source, String status,
                                       String discoveredVia, String note, String officialSiteUrl,
                                       Instant createdAt) throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist (owner, name, source, status, discovered_via, note, "
                        + "official_site_url, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.setString(3, source);
            ps.setString(4, status);
            ps.setString(5, discoveredVia);
            ps.setString(6, note);
            ps.setString(7, officialSiteUrl);
            ps.setTimestamp(8, Timestamp.from(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static int count(Statement s, String table) throws Exception {
        ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table);
        rs.next();
        return rs.getInt(1);
    }

    private static void assertOfficialSiteUrl(Statement s, long id, String expected) throws Exception {
        ResultSet rs = s.executeQuery("SELECT official_site_url FROM artist WHERE id = " + id);
        rs.next();
        assertThat(rs.getString("official_site_url")).as("official_site_url for artist " + id).isEqualTo(expected);
    }

    private static void assertRowUnchangedExceptUrl(Statement s, long id, String owner, String name,
                                                       String source, String status, String discoveredVia,
                                                       String note, Instant createdAt) throws Exception {
        ResultSet rs = s.executeQuery(
                "SELECT owner, name, source, status, discovered_via, note, created_at FROM artist WHERE id = " + id);
        rs.next();
        assertThat(rs.getString("owner")).as("owner unchanged").isEqualTo(owner);
        assertThat(rs.getString("name")).as("name unchanged").isEqualTo(name);
        assertThat(rs.getString("source")).as("source unchanged").isEqualTo(source);
        assertThat(rs.getString("status")).as("status unchanged").isEqualTo(status);
        assertThat(rs.getString("discovered_via")).as("discovered_via unchanged").isEqualTo(discoveredVia);
        assertThat(rs.getString("note")).as("note unchanged").isEqualTo(note);
        assertThat(rs.getTimestamp("created_at").toInstant()).as("created_at unchanged").isEqualTo(createdAt);
    }
}
