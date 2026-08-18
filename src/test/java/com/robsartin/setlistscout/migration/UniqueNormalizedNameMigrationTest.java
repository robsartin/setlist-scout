package com.robsartin.setlistscout.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import db.migration.V21__unique_artist_normalized_name;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #179: {@code V21__unique_artist_normalized_name} merges the {@code artist} rows that still
 * collide on {@code (owner, normalized_name)} and then promotes V19's plain index to a UNIQUE
 * constraint.
 *
 * <p>The collisions it targets are ones V13 could NOT have caught, so this test has to create them
 * the same way production did: AFTER V13 ran. V13 runs from scratch in every Testcontainers run
 * using TODAY's {@code ArtistNameNormalizer} -- including #157's hyphen-spacing fold -- so it
 * catches the hyphen-spacing pair itself and leaves nothing behind. Only the live database, where
 * V13 ran on 2026-08-14 under the pre-#157 rules, still carries one. Seeding these rows against a
 * database migrated to V20 reproduces exactly that: post-V13, pre-V21.
 *
 * <p>Covers, in one run: the real production group (a hyphen-spacing pair, both REJECTED, with
 * edges on both sides that must be repointed rather than dropped); the REMOVED-vs-SEED survivor
 * rank that only V21 can encounter (V14 introduces REMOVED after V13); edge duplicate-collapse,
 * self-loop removal and {@code scan_job}/{@code expand_job} repointing driven through V21 rather
 * than V13, proving the extracted merge is really wired in; owner isolation; and finally that the
 * constraint actually TOOK -- a duplicate insert must now be rejected by the database. Runs in CI
 * (needs Docker).
 */
@Testcontainers
class UniqueNormalizedNameMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER_A = "owner-a@example.com";
    private static final String OWNER_B = "owner-b@example.com";

    /** The real production pair (ids 5601/5942), read-only profiled 2026-08-18. */
    private static final String PROD_SURVIVOR_NAME = "Paul Quinichette-John Coltrane Quintet";
    private static final String PROD_LOSER_NAME = "Paul Quinichette - John Coltrane Quintet";

    @Test
    void mergesPostV13CollisionsThenEnforcesUniqueNormalizedName() throws Exception {
        // 1. Migrate to V20 -- V13 has already run and found nothing left to do, V19 has added and
        // backfilled normalized_name, and the constraint under test does not exist yet.
        assertThat(migrate("20").success).isTrue();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        long prodSurvivor;
        long prodLoser;
        long prodEdgePartnerOwn;   // already points at the survivor
        long prodEdgePartner1;     // points at the loser
        long prodEdgePartner2;     // points at the loser
        long removedRow;
        long seedRow;
        long jobSurvivor;
        long jobLoser;
        long jobThird;
        long isoA;
        long isoB;

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // Group 1 -- the production group. Both REJECTED, so status rank ties and the OLDER
            // created_at wins. Names differ only by whitespace around the hyphen (#157).
            prodSurvivor = insertArtist(s, OWNER_A, PROD_SURVIVOR_NAME, "REJECTED", t0);
            prodLoser = insertArtist(s, OWNER_A, PROD_LOSER_NAME, "REJECTED", t0.plus(1, ChronoUnit.DAYS));
            prodEdgePartnerOwn = insertArtist(s, OWNER_A, "Discogs Partner", "APPROVED", t0);
            prodEdgePartner1 = insertArtist(s, OWNER_A, "Lastfm Partner One", "APPROVED", t0);
            prodEdgePartner2 = insertArtist(s, OWNER_A, "Lastfm Partner Two", "APPROVED", t0);
            // Mirrors prod edge ids 1369 / 6628 / 15685: one already on the survivor, two on the
            // loser, all with distinct from_artist_ids -- so all three must survive the repoint.
            insertEdge(s, OWNER_A, prodEdgePartnerOwn, prodSurvivor, "MEMBER_EXPANSION", "discogs", t0);
            insertEdge(s, OWNER_A, prodEdgePartner1, prodLoser, "SIMILAR_EXPANSION", "lastfm",
                    t0.plus(1, ChronoUnit.DAYS));
            insertEdge(s, OWNER_A, prodEdgePartner2, prodLoser, "SIMILAR_EXPANSION", "lastfm",
                    t0.plus(2, ChronoUnit.DAYS));

            // Group 2 -- REMOVED vs SEED. Only V21 can meet this: V14 adds REMOVED after V13.
            // REMOVED outranks the active statuses, so the artist the owner took off their list is
            // NOT silently resurrected by the merge, even though SEED is the older row.
            seedRow = insertArtist(s, OWNER_A, "Taken Off The List", "SEED", t0);
            removedRow = insertArtist(s, OWNER_A, "taken off the list", "REMOVED", t0.plus(1, ChronoUnit.DAYS));

            // Group 3 -- exercises the extracted merge's harder paths THROUGH V21: duplicate-edge
            // collapse, self-loop removal, and job repointing.
            jobSurvivor = insertArtist(s, OWNER_A, "Job Band", "APPROVED", t0);
            jobLoser = insertArtist(s, OWNER_A, "job  band", "PENDING_REVIEW", t0.plus(1, ChronoUnit.DAYS));
            jobThird = insertArtist(s, OWNER_A, "Job Third", "SEED", t0);
            // Keeper, then a row that becomes its exact duplicate after repointing -> collapsed.
            insertEdge(s, OWNER_A, jobSurvivor, jobThird, "SIMILAR_EXPANSION", "lastfm",
                    t0.plus(1, ChronoUnit.MINUTES));
            insertEdge(s, OWNER_A, jobLoser, jobThird, "SIMILAR_EXPANSION", "lastfm",
                    t0.plus(5, ChronoUnit.MINUTES));
            // Both endpoints collapse onto the survivor -> a would-be self-loop, must be deleted.
            insertEdge(s, OWNER_A, jobLoser, jobSurvivor, "MEMBER_EXPANSION", "musicbrainz", t0);
            // Survivor already owns the ticketmaster job -> the loser's is deleted, not repointed.
            insertJob(s, "scan_job", OWNER_A, jobSurvivor, "ticketmaster", t0.plus(10, ChronoUnit.MINUTES));
            insertJob(s, "scan_job", OWNER_A, jobLoser, "ticketmaster", t0.plus(5, ChronoUnit.MINUTES));
            // No survivor row for musicbrainz -> the loser's row is repointed and kept.
            insertJob(s, "expand_job", OWNER_A, jobLoser, "musicbrainz", t0.plus(3, ChronoUnit.MINUTES));

            // Owner isolation -- the SAME normalized key under two owners, one row each. Neither is
            // a collision, and the constraint must still permit both after it is added.
            isoA = insertArtist(s, OWNER_A, "Shared Name Band", "SEED", t0);
            isoB = insertArtist(s, OWNER_B, "shared name band", "SEED", t0);
        }

        // 2. The loud-failure path, proven directly: before the merge these groups DO collide, and
        // the guard names every offending row (owner, key, id, name, status) rather than leaving
        // the ALTER TABLE to report one anonymous duplicate key value.
        try (Connection c = postgres.createConnection("")) {
            List<String> collisions = V21__unique_artist_normalized_name.findCollisions(c);
            assertThat(collisions).as("three seeded groups collide before the merge").hasSize(3);
            assertThatThrownBy(() -> V21__unique_artist_normalized_name.verifyNoCollisions(c))
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("owner=" + OWNER_A)
                    .hasMessageContaining(ArtistNameNormalizer.normalize(PROD_SURVIVOR_NAME))
                    .hasMessageContaining(prodSurvivor + " \"" + PROD_SURVIVOR_NAME + "\" REJECTED")
                    .hasMessageContaining(prodLoser + " \"" + PROD_LOSER_NAME + "\" REJECTED");
        }

        // 3. Pre-migration baseline.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(count(s, "artist")).as("seeded artist count before V21").isEqualTo(12);
            assertThat(count(s, "artist_edge")).as("seeded edge count before V21").isEqualTo(6);
            assertThat(count(s, "scan_job")).as("seeded scan_job count before V21").isEqualTo(2);
            assertThat(count(s, "expand_job")).as("seeded expand_job count before V21").isEqualTo(1);
            assertThat(uniqueIndexDef(s)).as("no unique (owner, normalized_name) index before V21").isNull();
        }

        // 4. Migrate to latest -- V21 merges, verifies, and adds the constraint.
        assertThat(migrate(null).success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 5. Exactly one row per group deleted; every survivor is the right row.
            assertThat(count(s, "artist")).as("artist count after V21").isEqualTo(9);

            assertArtistAbsent(s, prodLoser);
            assertArtistStatus(s, prodSurvivor, "REJECTED");
            assertArtistAbsent(s, seedRow);
            assertArtistStatus(s, removedRow, "REMOVED");
            assertArtistAbsent(s, jobLoser);
            assertArtistStatus(s, jobSurvivor, "APPROVED");
            assertArtistStatus(s, isoA, "SEED");
            assertArtistStatus(s, isoB, "SEED");

            // 6. Production group's edges: all three repointed, none lost -- the loser's last
            // referencing rows moved to the survivor BEFORE it was deleted.
            assertEdge(s, OWNER_A, prodEdgePartnerOwn, prodSurvivor, "MEMBER_EXPANSION", "discogs");
            assertEdge(s, OWNER_A, prodEdgePartner1, prodSurvivor, "SIMILAR_EXPANSION", "lastfm");
            assertEdge(s, OWNER_A, prodEdgePartner2, prodSurvivor, "SIMILAR_EXPANSION", "lastfm");
            assertNoEdgeReferencing(s, prodLoser);

            // 7. Group 3: duplicate edge collapsed, self-loop deleted, jobs collapsed/repointed.
            assertThat(count(s, "artist_edge")).as("edge count after V21").isEqualTo(4);
            assertEdge(s, OWNER_A, jobSurvivor, jobThird, "SIMILAR_EXPANSION", "lastfm");
            assertNoEdgeReferencing(s, jobLoser);
            assertThat(count(s, "scan_job")).as("loser's colliding scan_job deleted").isEqualTo(1);
            assertJob(s, "scan_job", OWNER_A, jobSurvivor, "ticketmaster");
            assertThat(count(s, "expand_job")).as("loser's expand_job repointed, not deleted").isEqualTo(1);
            assertJob(s, "expand_job", OWNER_A, jobSurvivor, "musicbrainz");

            // 8. No orphan references left behind by any delete.
            assertThat(orphanCount(s, "artist_edge", "from_artist_id")).as("no orphan from_artist_id").isZero();
            assertThat(orphanCount(s, "artist_edge", "to_artist_id")).as("no orphan to_artist_id").isZero();
            assertThat(orphanCount(s, "scan_job", "artist_id")).as("no orphan scan_job.artist_id").isZero();
            assertThat(orphanCount(s, "expand_job", "artist_id")).as("no orphan expand_job.artist_id").isZero();

            // 9. The constraint is really there, on the right columns in the right order, and V19's
            // now-redundant plain index is gone rather than left duplicating it.
            assertThat(uniqueIndexDef(s))
                    .as("UNIQUE (owner, normalized_name) exists, owner first")
                    .isNotNull()
                    .containsIgnoringCase("unique")
                    .containsIgnoringCase("(owner, normalized_name)");
            assertThat(indexDef(s, "idx_artist_owner_normalized_name"))
                    .as("V19's superseded non-unique index dropped").isNull();
            assertThat(indexDef(s, "artist_owner_name_key"))
                    .as("(owner, name) uniqueness deliberately kept -- see ArtistRepository#insertIfAbsent")
                    .isNotNull();

            // 10. The constraint TOOK: a spelling variant of an existing row is now rejected by the
            // database, not merely discouraged by an application pre-check.
            assertThatThrownBy(() -> insertArtist(s, OWNER_A, "Paul Quinichette  -  John Coltrane Quintet",
                    "PENDING_REVIEW", t0))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("artist_owner_normalized_name_key");
        }

        // 11. Owner isolation survives the constraint: the same normalized key under a different
        // owner is still insertable.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            insertArtist(s, "owner-c@example.com", "Shared Name Band", "SEED", t0);
            assertThat(count(s, "artist")).as("a third owner may hold the same normalized name").isEqualTo(10);
        }
    }

    private static MigrateResult migrate(String target) {
        var configure = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration");
        if (target != null) {
            configure = configure.target(target);
        }
        return configure.load().migrate();
    }

    /**
     * Seeds a row the way production writes one: {@code normalized_name} carries the REAL
     * normalizer's output, since every live write path supplies it (JPA {@code @PrePersist} or an
     * explicit parameter on the native insert -- see {@code NormalizedNameColumnTest}).
     */
    private static long insertArtist(Statement s, String owner, String name, String status, Instant createdAt)
            throws SQLException {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist (owner, name, normalized_name, source, status, created_at) "
                        + "VALUES (?, ?, ?, 'SEED_LIST', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.setString(3, ArtistNameNormalizer.normalize(name));
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
                                     String source, Instant createdAt) throws SQLException {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist_edge (owner, from_artist_id, to_artist_id, type, source, note, weight, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?)")) {
            ps.setString(1, owner);
            ps.setLong(2, fromArtistId);
            ps.setLong(3, toArtistId);
            ps.setString(4, type);
            ps.setString(5, source);
            ps.setString(6, "note for " + source);
            ps.setTimestamp(7, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
    }

    private static void insertJob(Statement s, String table, String owner, long artistId, String source,
                                    Instant nextDueAt) throws SQLException {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO " + table + " (owner, artist_id, source, status, attempts, next_due_at) "
                        + "VALUES (?, ?, ?, 'SCHEDULED', 0, ?)")) {
            ps.setString(1, owner);
            ps.setLong(2, artistId);
            ps.setString(3, source);
            ps.setTimestamp(4, Timestamp.from(nextDueAt));
            ps.executeUpdate();
        }
    }

    private static int count(Statement s, String table) throws SQLException {
        ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table);
        rs.next();
        return rs.getInt(1);
    }

    /** The definition of whichever index enforces uniqueness over (owner, normalized_name), or null. */
    private static String uniqueIndexDef(Statement s) throws SQLException {
        ResultSet rs = s.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'artist' "
                        + "AND indexdef ILIKE '%UNIQUE%' AND indexdef ILIKE '%(owner, normalized_name)%'");
        return rs.next() ? rs.getString(1) : null;
    }

    private static String indexDef(Statement s, String indexName) throws SQLException {
        ResultSet rs = s.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'artist' AND indexname = '" + indexName + "'");
        return rs.next() ? rs.getString(1) : null;
    }

    private static void assertArtistAbsent(Statement s, long id) throws SQLException {
        ResultSet rs = s.executeQuery("SELECT count(*) FROM artist WHERE id = " + id);
        rs.next();
        assertThat(rs.getInt(1)).as("artist id " + id + " deleted").isEqualTo(0);
    }

    private static void assertArtistStatus(Statement s, long id, String expectedStatus) throws SQLException {
        ResultSet rs = s.executeQuery("SELECT status FROM artist WHERE id = " + id);
        assertThat(rs.next()).as("artist id " + id + " survives").isTrue();
        assertThat(rs.getString("status")).as("artist id " + id + " status").isEqualTo(expectedStatus);
    }

    private static void assertEdge(Statement s, String owner, long fromId, long toId, String type, String source)
            throws SQLException {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM artist_edge WHERE owner = '" + owner + "' AND from_artist_id = " + fromId
                        + " AND to_artist_id = " + toId + " AND type = '" + type + "' AND source = '" + source + "'");
        rs.next();
        assertThat(rs.getInt(1)).as("edge " + fromId + "->" + toId + "/" + type + "/" + source + " present")
                .isEqualTo(1);
    }

    private static void assertNoEdgeReferencing(Statement s, long artistId) throws SQLException {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM artist_edge WHERE from_artist_id = " + artistId + " OR to_artist_id = "
                        + artistId);
        rs.next();
        assertThat(rs.getInt(1)).as("no edge references deleted artist id " + artistId).isEqualTo(0);
    }

    private static void assertJob(Statement s, String table, String owner, long artistId, String source)
            throws SQLException {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM " + table + " WHERE owner = '" + owner + "' AND artist_id = " + artistId
                        + " AND source = '" + source + "'");
        rs.next();
        assertThat(rs.getInt(1)).as(table + " row for artist " + artistId + "/" + source + " present").isEqualTo(1);
    }

    private static int orphanCount(Statement s, String table, String fkColumn) throws SQLException {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM " + table + " t LEFT JOIN artist a ON a.id = t." + fkColumn
                        + " WHERE a.id IS NULL");
        rs.next();
        return rs.getInt(1);
    }
}
