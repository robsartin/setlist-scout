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
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #121: the V11 backfill (#110) ran after the live edge-writing listener (#109) was already
 * in production, so the same {@code (owner, from, to, type)} relationship could get both a
 * {@code legacy-unknown} edge (backfill, derived from the flat note) and a real-adapter-source edge
 * (live listener) -- two distinct rows because {@code source} is part of {@code artist_edge}'s
 * unique key. That inflates apparent multi-source corroboration. {@code V12} deletes the
 * {@code legacy-unknown} row wherever a real-source sibling exists for the exact same relationship,
 * while preserving {@code legacy-unknown} edges that are a relationship's only record.
 *
 * Builds a populated pre-V12 DB (migrated through V11) with {@code artist} rows and hand-seeded
 * {@code artist_edge} rows covering the dedupe branch, the legacy-only safety case, genuine
 * multi-real-source corroboration, the two ways a sibling can fail to match (different type,
 * different to_artist), and owner isolation. Migrates to V12 (latest) and asserts exact survivors.
 * Runs in CI (needs Docker).
 */
@Testcontainers
class DedupeLegacyArtistEdgesMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER_A = "owner-a@example.com";
    private static final String OWNER_B = "owner-b@example.com";

    @Test
    void deletesLegacyUnknownEdgesThatDuplicateARealSourceEdge() throws Exception {
        // 1. Migrate up to V11 (artist_edge exists and is empty) -- the pre-dedupe state.
        Flyway toV11 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .target("11")
                .load();
        MigrateResult v11Result = toV11.migrate();
        assertThat(v11Result.success).isTrue();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // Owner A base artist and its candidates.
            long radiohead = insertArtist(s, OWNER_A, "Radiohead", "SEED_LIST", "SEED", t0);
            long thomYorke = insertArtist(s, OWNER_A, "Thom Yorke", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    t0.plus(1, ChronoUnit.MINUTES));
            long jonnyGreenwood = insertArtist(s, OWNER_A, "Jonny Greenwood", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    t0.plus(2, ChronoUnit.MINUTES));
            long portishead = insertArtist(s, OWNER_A, "Portishead", "SIMILAR_EXPANSION", "PENDING_REVIEW",
                    t0.plus(3, ChronoUnit.MINUTES));
            long muse = insertArtist(s, OWNER_A, "Muse", "SIMILAR_EXPANSION", "PENDING_REVIEW",
                    t0.plus(4, ChronoUnit.MINUTES));
            long sigurRos = insertArtist(s, OWNER_A, "Sigur Ros", "SIMILAR_EXPANSION", "PENDING_REVIEW",
                    t0.plus(5, ChronoUnit.MINUTES));

            // Case 1: same relationship, legacy-unknown + real source -> legacy edge must be deleted.
            insertEdge(s, OWNER_A, radiohead, thomYorke, "MEMBER_EXPANSION", "legacy-unknown", t0);
            insertEdge(s, OWNER_A, radiohead, thomYorke, "MEMBER_EXPANSION", "musicbrainz",
                    t0.plus(2, ChronoUnit.HOURS));

            // Case 2: legacy-unknown is the ONLY record of the relationship -> must survive.
            insertEdge(s, OWNER_A, radiohead, jonnyGreenwood, "MEMBER_EXPANSION", "legacy-unknown", t0);

            // Case 3: two real sources, no legacy -> genuine corroboration, both survive untouched.
            insertEdge(s, OWNER_A, radiohead, portishead, "SIMILAR_EXPANSION", "musicbrainz", t0);
            insertEdge(s, OWNER_A, radiohead, portishead, "SIMILAR_EXPANSION", "discogs", t0);

            // Case 4a: legacy edge's sibling exists for the same (owner, from, to) but a DIFFERENT
            // type -- not the same relationship, so the legacy edge must survive.
            insertEdge(s, OWNER_A, radiohead, muse, "SIMILAR_EXPANSION", "legacy-unknown", t0);
            insertEdge(s, OWNER_A, radiohead, muse, "MEMBER_EXPANSION", "musicbrainz", t0);

            // Case 4b: legacy edge whose (owner, from, type) matches another edge, but the TO
            // artist differs (Sigur Ros vs. Portishead) -- not the same relationship, survives.
            insertEdge(s, OWNER_A, radiohead, sigurRos, "SIMILAR_EXPANSION", "legacy-unknown", t0);

            // Owner B: same artist names as owner A, but a fully separate owner. Owner A has a
            // real-source sibling for Radiohead -> Thom Yorke (case 1); owner B's legacy-unknown
            // edge for its own Radiohead -> Thom Yorke pair must NOT be deleted by it.
            long radioheadB = insertArtist(s, OWNER_B, "Radiohead", "SEED_LIST", "SEED", t0);
            long thomYorkeB = insertArtist(s, OWNER_B, "Thom Yorke", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    t0.plus(1, ChronoUnit.MINUTES));
            insertEdge(s, OWNER_B, radioheadB, thomYorkeB, "MEMBER_EXPANSION", "legacy-unknown", t0);
        }

        // 2. Pre-migration baseline: 9 seeded edges.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            ResultSet total = s.executeQuery("SELECT count(*) FROM artist_edge");
            total.next();
            assertThat(total.getInt(1)).as("seeded edge count before V12").isEqualTo(9);
        }

        // 3. Migrate to latest (V12 runs the dedupe delete).
        Flyway toLatest = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult latestResult = toLatest.migrate();
        assertThat(latestResult.success).isTrue();

        // 4. Exactly one row deleted: the case-1 legacy-unknown duplicate. 8 remain.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            ResultSet total = s.executeQuery("SELECT count(*) FROM artist_edge");
            total.next();
            assertThat(total.getInt(1)).as("edge count after V12").isEqualTo(8);

            assertEdgeAbsent(s, OWNER_A, "Radiohead", "Thom Yorke", "MEMBER_EXPANSION", "legacy-unknown");
            assertEdgePresent(s, OWNER_A, "Radiohead", "Thom Yorke", "MEMBER_EXPANSION", "musicbrainz");
            assertEdgePresent(s, OWNER_A, "Radiohead", "Jonny Greenwood", "MEMBER_EXPANSION", "legacy-unknown");
            assertEdgePresent(s, OWNER_A, "Radiohead", "Portishead", "SIMILAR_EXPANSION", "musicbrainz");
            assertEdgePresent(s, OWNER_A, "Radiohead", "Portishead", "SIMILAR_EXPANSION", "discogs");
            assertEdgePresent(s, OWNER_A, "Radiohead", "Muse", "SIMILAR_EXPANSION", "legacy-unknown");
            assertEdgePresent(s, OWNER_A, "Radiohead", "Muse", "MEMBER_EXPANSION", "musicbrainz");
            assertEdgePresent(s, OWNER_A, "Radiohead", "Sigur Ros", "SIMILAR_EXPANSION", "legacy-unknown");
            assertEdgePresent(s, OWNER_B, "Radiohead", "Thom Yorke", "MEMBER_EXPANSION", "legacy-unknown");

            // Invariant: every distinct (owner, from, to, type) relationship seeded before V12
            // still has at least one surviving edge after.
            String[][] relationships = {
                {OWNER_A, "Radiohead", "Thom Yorke", "MEMBER_EXPANSION"},
                {OWNER_A, "Radiohead", "Jonny Greenwood", "MEMBER_EXPANSION"},
                {OWNER_A, "Radiohead", "Portishead", "SIMILAR_EXPANSION"},
                {OWNER_A, "Radiohead", "Muse", "SIMILAR_EXPANSION"},
                {OWNER_A, "Radiohead", "Muse", "MEMBER_EXPANSION"},
                {OWNER_A, "Radiohead", "Sigur Ros", "SIMILAR_EXPANSION"},
                {OWNER_B, "Radiohead", "Thom Yorke", "MEMBER_EXPANSION"},
            };
            for (String[] rel : relationships) {
                int count = edgeCount(s, rel[0], rel[1], rel[2], rel[3]);
                assertThat(count).as("relationship " + rel[0] + "/" + rel[1] + "->" + rel[2] + "/" + rel[3]
                        + " retains >=1 edge").isGreaterThanOrEqualTo(1);
            }
        }
    }

    private static long insertArtist(Statement s, String owner, String name, String source, String status,
                                        Instant createdAt) throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist (owner, name, source, status, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.setString(3, source);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertEdge(Statement s, String owner, long fromArtistId, long toArtistId, String type,
                                     String source, Instant createdAt) throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist_edge (owner, from_artist_id, to_artist_id, type, source, note, weight, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, owner);
            ps.setLong(2, fromArtistId);
            ps.setLong(3, toArtistId);
            ps.setString(4, type);
            ps.setString(5, source);
            ps.setString(6, "note for " + source);
            ps.setNull(7, java.sql.Types.DOUBLE);
            ps.setTimestamp(8, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
    }

    private static int edgeCount(Statement s, String owner, String fromName, String toName, String type)
            throws Exception {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM artist_edge e "
                        + "JOIN artist f ON f.id = e.from_artist_id "
                        + "JOIN artist t ON t.id = e.to_artist_id "
                        + "WHERE e.owner = '" + owner + "' AND f.name = '" + fromName + "' "
                        + "AND t.name = '" + toName + "' AND e.type = '" + type + "'");
        rs.next();
        return rs.getInt(1);
    }

    private static void assertEdgePresent(Statement s, String owner, String fromName, String toName, String type,
                                            String source) throws Exception {
        assertThat(edgeExists(s, owner, fromName, toName, type, source))
                .as("edge present: " + owner + "/" + fromName + "->" + toName + "/" + type + "/" + source)
                .isTrue();
    }

    private static void assertEdgeAbsent(Statement s, String owner, String fromName, String toName, String type,
                                           String source) throws Exception {
        assertThat(edgeExists(s, owner, fromName, toName, type, source))
                .as("edge absent: " + owner + "/" + fromName + "->" + toName + "/" + type + "/" + source)
                .isFalse();
    }

    private static boolean edgeExists(Statement s, String owner, String fromName, String toName, String type,
                                        String source) throws Exception {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM artist_edge e "
                        + "JOIN artist f ON f.id = e.from_artist_id "
                        + "JOIN artist t ON t.id = e.to_artist_id "
                        + "WHERE e.owner = '" + owner + "' AND f.name = '" + fromName + "' "
                        + "AND t.name = '" + toName + "' AND e.type = '" + type + "' AND e.source = '" + source
                        + "'");
        rs.next();
        return rs.getInt(1) == 1;
    }
}
