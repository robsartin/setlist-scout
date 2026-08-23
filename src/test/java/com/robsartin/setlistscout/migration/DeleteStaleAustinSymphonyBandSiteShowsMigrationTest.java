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
 * Issue #248: Austin Symphony Orchestra's {@code official_site_url} moved from the homepage (host
 * {@code www.austinsymphony.org}) to the season page (host {@code austinsymphony.org}, no
 * {@code www}) in #218/#245. {@code show_event}'s unique key is {@code (owner, artist_name,
 * event_date_time, venue_name)}, so the re-scrape's corrected {@code band-site:austinsymphony.org}
 * rows inserted ALONGSIDE the stale {@code band-site:www.austinsymphony.org} ones instead of
 * replacing them -- {@code V31} deletes exactly the stale-source rows for that one artist.
 *
 * <p>Profiled read-only against production 2026-08-23 (issue #248's acceptance criteria):
 * {@code show_event} totalled 257 rows; exactly 8 matched {@code owner='rob.sartin@gmail.com' AND
 * artist_id=37957 AND source='band-site:www.austinsymphony.org'}; exactly 15 matched the corrected
 * {@code source='band-site:austinsymphony.org'} (no {@code www}) for the same owner/artist; a
 * {@code LIKE '%austinsymphony%'} predicate would have caught all 23 (8 + 15) instead of just the 8
 * stale ones. This test seeds a small representative fixture (not a literal 8/15/257 replica) that
 * exercises each condition in {@code V31}'s {@code WHERE} clause separately: two stale rows for the
 * real target (owner + artist_id 37957, exact stale source) that must BOTH be deleted; a same-
 * owner/same-artist row with the corrected (no-{@code www}) source that must survive (the exact-
 * match-not-LIKE proof); a same-owner row for a DIFFERENT artist carrying the exact same stale
 * source string that must survive (the {@code artist_id} scoping proof); and a different owner's
 * row carrying the exact same stale source string that must survive (the owner scoping proof).
 * Runs in CI (needs Docker).
 */
@Testcontainers
class DeleteStaleAustinSymphonyBandSiteShowsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final long TARGET_ARTIST_ID = 37957L;
    private static final String TARGET_OWNER = "rob.sartin@gmail.com";
    private static final String OTHER_OWNER = "other-owner-248@example.com";
    private static final String STALE_SOURCE = "band-site:www.austinsymphony.org";
    private static final String CORRECT_SOURCE = "band-site:austinsymphony.org";

    @Test
    void deletesOnlyTheExactStaleSourceRowsForTheTargetOwnerAndArtist() throws Exception {
        // 1. Migrate up to V30 (the true pre-V31 baseline): every column V31's DELETE and this
        // test's own seed inserts rely on (artist.normalized_name, show_event.kind, show_event.
        // artist_id) already exists, and the stale/correct duplicate rows this issue describes do
        // not exist yet.
        Flyway toV30 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .target("30")
                .load();
        assertThat(toV30.migrate().success).isTrue();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        long staleRow1;
        long staleRow2;
        long survivorRow;
        long otherArtistRow;
        long otherOwnerRow;

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            insertArtist(s, TARGET_ARTIST_ID, TARGET_OWNER, "Austin Symphony Orchestra", t0);
            long otherArtistId = insertArtistAutoId(s, TARGET_OWNER, "Some Other Artist", t0.plusSeconds(1));
            long otherOwnerArtistId = insertArtistAutoId(s, OTHER_OWNER, "Austin Symphony Orchestra", t0.plusSeconds(2));

            // Two stale rows for the real target -- different venue/date, same source -- proving
            // the DELETE removes every matching row, not just one.
            staleRow1 = insertShow(s, TARGET_OWNER, TARGET_ARTIST_ID, STALE_SOURCE,
                    "1113 Red River St", "2026-10-23 20:00:00");
            staleRow2 = insertShow(s, TARGET_OWNER, TARGET_ARTIST_ID, STALE_SOURCE,
                    "Austin Symphony Orchestra", "2026-09-11 20:00:00");
            // Corrected source, same owner + artist -- must survive. The two source strings differ
            // by "www." only; this is the proof a LIKE predicate would have destroyed.
            survivorRow = insertShow(s, TARGET_OWNER, TARGET_ARTIST_ID, CORRECT_SOURCE,
                    "Long Center", "2026-10-23 20:00:00");
            // Same owner, exact same stale source string, but a DIFFERENT artist -- must survive
            // (proves artist_id is really part of the predicate, not just owner + source).
            otherArtistRow = insertShow(s, TARGET_OWNER, otherArtistId, STALE_SOURCE,
                    "Some Venue", "2026-11-01 20:00:00");
            // Different owner, exact same stale source string and even the same artist name --
            // must survive (owner scoping).
            otherOwnerRow = insertShow(s, OTHER_OWNER, otherOwnerArtistId, STALE_SOURCE,
                    "Some Other Venue", "2026-11-02 20:00:00");
        }

        // 2. Pre-migration baseline: all 5 seeded rows present.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(count(s, "show_event")).as("seeded show_event count before V31").isEqualTo(5);
        }

        // 3. Migrate to latest (V31 runs the targeted delete).
        Flyway toLatest = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult latest = toLatest.migrate();
        assertThat(latest.success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 4. Both stale rows for the real target are gone.
            assertThat(exists(s, staleRow1)).as("stale row 1 (exact target) deleted").isFalse();
            assertThat(exists(s, staleRow2)).as("stale row 2 (exact target) deleted").isFalse();

            // 5. Everything else survives -- asserted explicitly, not inferred from a row count,
            // per #248's warning that "the bad rows are gone" alone passes even when EVERYTHING
            // is gone.
            assertThat(exists(s, survivorRow))
                    .as("corrected (no-www) source for the same owner+artist survives").isTrue();
            assertThat(exists(s, otherArtistRow))
                    .as("same owner, different artist, identical stale source string -- survives")
                    .isTrue();
            assertThat(exists(s, otherOwnerRow))
                    .as("different owner, identical stale source string -- survives").isTrue();

            // 6. Total count: exactly 2 deleted, 3 remain.
            assertThat(count(s, "show_event")).as("show_event total after V31").isEqualTo(3);

            // 7. Nothing about the survivor row itself was altered.
            ResultSet survivor = s.executeQuery(
                    "SELECT owner, artist_id, source, venue_name FROM show_event WHERE id = " + survivorRow);
            survivor.next();
            assertThat(survivor.getString("owner")).isEqualTo(TARGET_OWNER);
            assertThat(survivor.getLong("artist_id")).isEqualTo(TARGET_ARTIST_ID);
            assertThat(survivor.getString("source")).isEqualTo(CORRECT_SOURCE);
            assertThat(survivor.getString("venue_name")).isEqualTo("Long Center");
        }
    }

    /** Inserts an artist row with an EXPLICIT id -- the target ASO row must be id 37957 for V31's
     * hardcoded {@code artist_id = 37957} to match it at all (the column is {@code GENERATED BY
     * DEFAULT AS IDENTITY}, which permits an explicit override on INSERT). */
    private static void insertArtist(Statement s, long id, String owner, String name, Instant createdAt)
            throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist (id, owner, name, normalized_name, source, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setString(2, owner);
            ps.setString(3, name);
            ps.setString(4, com.robsartin.setlistscout.catalog.ArtistNameNormalizer.normalize(name));
            ps.setString(5, "SEED_LIST");
            ps.setString(6, "SEED");
            ps.setTimestamp(7, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
    }

    private static long insertArtistAutoId(Statement s, String owner, String name, Instant createdAt)
            throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist (owner, name, normalized_name, source, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.setString(3, com.robsartin.setlistscout.catalog.ArtistNameNormalizer.normalize(name));
            ps.setString(4, "SEED_LIST");
            ps.setString(5, "SEED");
            ps.setTimestamp(6, Timestamp.from(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertShow(Statement s, String owner, long artistId, String source,
                                     String venueName, String eventDateTime) throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO show_event (owner, artist_name, event_date_time, venue_name, source, "
                        + "discovered_at, kind, artist_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner);
            ps.setString(2, "Austin Symphony Orchestra");
            ps.setTimestamp(3, Timestamp.valueOf(eventDateTime));
            ps.setString(4, venueName);
            ps.setString(5, source);
            ps.setTimestamp(6, Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")));
            ps.setString(7, "MUSIC");
            ps.setLong(8, artistId);
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

    private static boolean exists(Statement s, long showId) throws Exception {
        ResultSet rs = s.executeQuery("SELECT count(*) FROM show_event WHERE id = " + showId);
        rs.next();
        return rs.getInt(1) == 1;
    }
}
