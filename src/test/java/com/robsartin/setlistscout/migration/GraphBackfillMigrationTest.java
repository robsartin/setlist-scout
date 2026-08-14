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
 * Issue #110: {@code V11__backfill_artist_edge.sql} backfills {@code artist_edge} from the
 * pre-graph flat provenance columns on {@code artist} ({@code discovered_via}/{@code source}/
 * {@code note}). Builds a populated pre-backfill DB (migrated through V10, the graph table's
 * introduction in #109), inserts legacy-shaped {@code artist} rows covering every source-id
 * derivation branch documented in V11's header comment and
 * {@code docs/explorations/2026-08-14-artist-graph-model.md} §4, then migrates to V11 and asserts
 * the resulting edges. Runs in CI (needs Docker).
 */
@Testcontainers
class GraphBackfillMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER_A = "owner-a@example.com";
    private static final String OWNER_B = "owner-b@example.com";

    @Test
    void backfillsEdgesFromLegacyProvenanceColumns() throws Exception {
        // 1. Migrate up to V10 (artist_edge table exists, but empty) -- the pre-backfill state.
        Flyway toV10 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .target("10")
                .load();
        MigrateResult v10Result = toV10.migrate();
        assertThat(v10Result.success).isTrue();

        // 2. Seed legacy-shaped artist rows covering every backfill branch.
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // Owner A: base artist (SEED_LIST, no discovered_via -- a root, not a discovery).
            insertArtist(s, OWNER_A, "Radiohead", "SEED_LIST", "SEED", null, null, t0);

            // MEMBER_EXPANSION candidate -> MusicBrainz/Discogs note collision -> legacy-unknown.
            insertArtist(s, OWNER_A, "Thom Yorke", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    "Radiohead", "member/lineup relation of Radiohead", t0.plus(1, ChronoUnit.MINUTES));

            // TRIBUTE_EXPANSION candidate -> only one tribute adapter -> tribute-llm.
            insertArtist(s, OWNER_A, "Radiohead Tribute Band", "TRIBUTE_EXPANSION", "PENDING_REVIEW",
                    "Radiohead", "tribute act for Radiohead", t0.plus(2, ChronoUnit.MINUTES));

            // SIMILAR_EXPANSION (via Last.fm) -> lastfm.
            insertArtist(s, OWNER_A, "Portishead", "SIMILAR_EXPANSION", "PENDING_REVIEW",
                    "Radiohead", "similar artist (via Last.fm)", t0.plus(3, ChronoUnit.MINUTES));

            // SIMILAR_EXPANSION (via LLM) -> similar-llm.
            insertArtist(s, OWNER_A, "Sigur Ros", "SIMILAR_EXPANSION", "PENDING_REVIEW",
                    "Radiohead", "similar artist (via LLM)", t0.plus(4, ChronoUnit.MINUTES));

            // SIMILAR_EXPANSION legacy wording (neither Last.fm nor LLM marker) -> legacy-unknown.
            insertArtist(s, OWNER_A, "Muse", "SIMILAR_EXPANSION", "PENDING_REVIEW",
                    "Radiohead", "similar artist (single-source match)", t0.plus(5, ChronoUnit.MINUTES));

            // discovered_via names an artist that doesn't exist (for this owner) -> unresolvable, skipped.
            insertArtist(s, OWNER_A, "Ghost Band", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    "Nonexistent Artist", "member/lineup relation of Nonexistent Artist",
                    t0.plus(6, ChronoUnit.MINUTES));

            // discovered_via names itself -> self-loop, defensively skipped.
            insertArtist(s, OWNER_A, "Weird Loop", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    "Weird Loop", "member/lineup relation of Weird Loop", t0.plus(7, ChronoUnit.MINUTES));

            // Owner B: same base-artist name as owner A ("Radiohead") -- must not cross-link.
            insertArtist(s, OWNER_B, "Radiohead", "SEED_LIST", "SEED", null, null, t0);
            insertArtist(s, OWNER_B, "Jonny Greenwood", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    "Radiohead", "member/lineup relation of Radiohead", t0.plus(1, ChronoUnit.MINUTES));
        }

        // 3. Migrate to latest (V11 runs the backfill).
        Flyway toLatest = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult latestResult = toLatest.migrate();
        assertThat(latestResult.success).isTrue();

        // 4. Assert the backfilled edges: 5 for owner A (Ghost Band and Weird Loop excluded), 1 for owner B.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            ResultSet total = s.executeQuery("SELECT count(*) FROM artist_edge");
            total.next();
            assertThat(total.getInt(1)).as("total backfilled edges").isEqualTo(6);

            assertEdge(s, OWNER_A, "Thom Yorke", "Radiohead", "MEMBER_EXPANSION", "legacy-unknown");
            assertEdge(s, OWNER_A, "Radiohead Tribute Band", "Radiohead", "TRIBUTE_EXPANSION", "tribute-llm");
            assertEdge(s, OWNER_A, "Portishead", "Radiohead", "SIMILAR_EXPANSION", "lastfm");
            assertEdge(s, OWNER_A, "Sigur Ros", "Radiohead", "SIMILAR_EXPANSION", "similar-llm");
            assertEdge(s, OWNER_A, "Muse", "Radiohead", "SIMILAR_EXPANSION", "legacy-unknown");
            assertEdge(s, OWNER_B, "Jonny Greenwood", "Radiohead", "MEMBER_EXPANSION", "legacy-unknown");

            // Unresolvable and self-loop candidates produced no edge.
            ResultSet ghost = s.executeQuery(
                    "SELECT count(*) FROM artist_edge e JOIN artist a ON a.id = e.to_artist_id "
                    + "WHERE a.name = 'Ghost Band'");
            ghost.next();
            assertThat(ghost.getInt(1)).as("unresolvable discovered_via produced no edge").isEqualTo(0);

            ResultSet loop = s.executeQuery(
                    "SELECT count(*) FROM artist_edge e JOIN artist a ON a.id = e.to_artist_id "
                    + "WHERE a.name = 'Weird Loop'");
            loop.next();
            assertThat(loop.getInt(1)).as("self-loop candidate produced no edge").isEqualTo(0);

            // SEED_LIST roots (discovered_via IS NULL) never appear as a discovered ("to") node.
            ResultSet seedAsTo = s.executeQuery(
                    "SELECT count(*) FROM artist_edge e JOIN artist a ON a.id = e.to_artist_id "
                    + "WHERE a.name = 'Radiohead'");
            seedAsTo.next();
            assertThat(seedAsTo.getInt(1)).as("SEED_LIST root is never a discovered node").isEqualTo(0);

            // No cross-owner edge: every edge's owner matches both endpoints' owner.
            ResultSet crossOwner = s.executeQuery(
                    "SELECT count(*) FROM artist_edge e "
                    + "JOIN artist f ON f.id = e.from_artist_id "
                    + "JOIN artist t ON t.id = e.to_artist_id "
                    + "WHERE e.owner <> f.owner OR e.owner <> t.owner");
            crossOwner.next();
            assertThat(crossOwner.getInt(1)).as("no cross-owner edge").isEqualTo(0);
        }

        // 5. Idempotency: re-running the backfill INSERT must not create duplicate rows.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            String backfillSql = new String(new org.springframework.core.io.ClassPathResource(
                    "db/migration/V11__backfill_artist_edge.sql").getInputStream().readAllBytes());
            s.execute(backfillSql);

            ResultSet total = s.executeQuery("SELECT count(*) FROM artist_edge");
            total.next();
            assertThat(total.getInt(1)).as("re-running the backfill creates no new rows").isEqualTo(6);
        }
    }

    private static void insertArtist(Statement s, String owner, String name, String source, String status,
                                       String discoveredVia, String note, Instant createdAt) throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist (owner, name, source, status, discovered_via, note, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.setString(3, source);
            ps.setString(4, status);
            ps.setString(5, discoveredVia);
            ps.setString(6, note);
            ps.setTimestamp(7, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
    }

    private static void assertEdge(Statement s, String owner, String toName, String fromName,
                                     String expectedType, String expectedSource) throws Exception {
        ResultSet rs = s.executeQuery(
                "SELECT e.type, e.source, e.note, e.weight, e.created_at, e.owner "
                        + "FROM artist_edge e "
                        + "JOIN artist f ON f.id = e.from_artist_id "
                        + "JOIN artist t ON t.id = e.to_artist_id "
                        + "WHERE e.owner = '" + owner + "' AND t.name = '" + toName + "' AND f.name = '" + fromName + "'");
        assertThat(rs.next()).as("edge exists for " + owner + "/" + toName + " <- " + fromName).isTrue();
        assertThat(rs.getString("type")).as("type = candidate's source enum").isEqualTo(expectedType);
        assertThat(rs.getString("source")).as("derived source id").isEqualTo(expectedSource);
        rs.getDouble("weight");
        assertThat(rs.wasNull()).as("weight is NULL").isTrue();
        Timestamp edgeCreatedAt = rs.getTimestamp("created_at");

        // created_at on the edge equals the candidate ("to") artist's own created_at. A fresh
        // Statement is used here since re-using `s` for another query would close the ResultSet
        // still being read above.
        try (Statement s2 = s.getConnection().createStatement()) {
            ResultSet candidate = s2.executeQuery(
                    "SELECT created_at FROM artist WHERE owner = '" + owner + "' AND name = '" + toName + "'");
            candidate.next();
            assertThat(edgeCreatedAt).as("edge created_at = candidate created_at")
                    .isEqualTo(candidate.getTimestamp("created_at"));
        }
    }
}
