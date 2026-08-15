package com.robsartin.setlistscout.migration;

import db.migration.V13__merge_duplicate_variant_artists;
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
 * Issue #123: {@code V13__merge_duplicate_variant_artists} merges the pre-#118 duplicate-variant
 * {@code artist} groups already live in prod -- rows for the same owner whose name normalizes to
 * the same match form under {@code catalog.ArtistNameNormalizer} (case/whitespace/unicode
 * dash-quote folding), repointing {@code artist_edge}/{@code scan_job}/{@code expand_job} rows from
 * the losing ids to the survivor before deleting the losers.
 *
 * <p>Builds a populated pre-V13 DB (migrated through V12) with hand-seeded duplicate-variant
 * groups covering: a case-only pair, a punctuation pair (en-dash vs. hyphen), a punctuation pair
 * (curly vs. straight quote, tie-broken on created_at), the critical REJECTED-dominates case, a
 * richer group exercising edge repoint/duplicate-collapse/self-loop-removal and
 * scan_job/expand_job repoint/collision-collapse, owner isolation, and non-Latin names. Migrates to
 * V13 (latest) and asserts exact survivors, then re-invokes the merge logic directly to prove
 * idempotency. Runs in CI (needs Docker).
 */
@Testcontainers
class MergeDuplicateVariantArtistsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER_A = "owner-a@example.com";
    private static final String OWNER_B = "owner-b@example.com";

    @Test
    void mergesDuplicateVariantGroupsAndRepointsReferences() throws Exception {
        // 1. Migrate up to V12 (pre-V13 state).
        Flyway toV12 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .target("12")
                .load();
        MigrateResult v12Result = toV12.migrate();
        assertThat(v12Result.success).isTrue();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        long caseA;
        long caseB;
        long dashA;
        long dashB;
        long quoteA;
        long quoteB;
        long rejPending;
        long rejRejected;
        long eSurv;
        long eLoser;
        long eLoser2;
        long third;
        long isoA;
        long isoA2;
        long isoB;
        long isoB2;
        long hebrew;
        long japanese;

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // Group 1: case-only variant. Weaker-but-newer PENDING_REVIEW must NOT beat the
            // stronger-but-older... actually here the stronger status is also newer, proving rank
            // wins over recency in either direction.
            caseA = insertArtist(s, OWNER_A, "Foo Bar", "MEMBER_EXPANSION", "PENDING_REVIEW", t0);
            caseB = insertArtist(s, OWNER_A, "foo bar", "MEMBER_EXPANSION", "APPROVED", t0.plus(1, ChronoUnit.MINUTES));

            // Group 2: punctuation variant (en-dash vs. hyphen). SEED beats PENDING_REVIEW.
            dashA = insertArtist(s, OWNER_A, "Only Murders In The Building - Cast", "SEED_LIST", "SEED", t0);
            dashB = insertArtist(s, OWNER_A, "Only Murders in the Building – Cast", "MEMBER_EXPANSION",
                    "PENDING_REVIEW", t0.plus(1, ChronoUnit.MINUTES));

            // Group 3: punctuation variant (curly vs. straight quote), same rank -> oldest created_at wins.
            quoteA = insertArtist(s, OWNER_A, "\"Weird Al\" Yankovic", "MEMBER_EXPANSION", "PENDING_REVIEW", t0);
            quoteB = insertArtist(s, OWNER_A, "“Weird Al” Yankovic", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    t0.plus(5, ChronoUnit.MINUTES));

            // Group 4 (critical case): REJECTED must dominate even though it is the NEWER row --
            // proves status rank, not recency, decides. Getting this backwards would resurrect a
            // rejected artist, the exact bug #118 fixed.
            rejPending = insertArtist(s, OWNER_A, "Rejected Case", "MEMBER_EXPANSION", "PENDING_REVIEW", t0);
            rejRejected = insertArtist(s, OWNER_A, "rejected case", "MEMBER_EXPANSION", "REJECTED",
                    t0.plus(10, ChronoUnit.MINUTES));

            // Group 5: edge/job repoint exercise. eSurv survives; eLoser and eLoser2 are both
            // duplicates of it (case + double-internal-space variants).
            eSurv = insertArtist(s, OWNER_A, "Edge Band", "SEED_LIST", "APPROVED", t0);
            eLoser = insertArtist(s, OWNER_A, "edge band", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    t0.plus(1, ChronoUnit.MINUTES));
            eLoser2 = insertArtist(s, OWNER_A, "Edge  Band", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    t0.plus(3, ChronoUnit.MINUTES));
            third = insertArtist(s, OWNER_A, "Third Artist", "SEED_LIST", "SEED", t0);

            // Owner isolation: identical normalized name under two different owners, each with its
            // own internal duplicate pair. Must merge independently, never across owners.
            isoA = insertArtist(s, OWNER_A, "Owner Iso Band", "SEED_LIST", "SEED", t0);
            isoA2 = insertArtist(s, OWNER_A, "owner iso band", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    t0.plus(1, ChronoUnit.MINUTES));
            isoB = insertArtist(s, OWNER_B, "Owner Iso Band", "SEED_LIST", "SEED", t0);
            isoB2 = insertArtist(s, OWNER_B, "owner iso band", "MEMBER_EXPANSION", "PENDING_REVIEW",
                    t0.plus(1, ChronoUnit.MINUTES));

            // Non-Latin names: distinct singleton groups, must survive untouched and unmangled,
            // never collapsed into each other.
            hebrew = insertArtist(s, OWNER_A, "אבג", "SEED_LIST", "SEED", t0);
            japanese = insertArtist(s, OWNER_A, "あいう", "SEED_LIST", "SEED", t0);

            // Edges around the "Edge Band" group.
            // Pre-existing keeper: survivor -> third, SIMILAR_EXPANSION/lastfm.
            insertEdge(s, OWNER_A, eSurv, third, "SIMILAR_EXPANSION", "lastfm", t0.plus(1, ChronoUnit.MINUTES));
            // Becomes a duplicate of the above once repointed -- must be collapsed away.
            insertEdge(s, OWNER_A, eLoser, third, "SIMILAR_EXPANSION", "lastfm", t0.plus(5, ChronoUnit.MINUTES));
            // Different type/source -- repoints cleanly, no collision.
            insertEdge(s, OWNER_A, eLoser, third, "MEMBER_EXPANSION", "musicbrainz", t0.plus(2, ChronoUnit.MINUTES));
            // Both endpoints collapse onto the survivor -- a would-be self-loop, must be deleted.
            insertEdge(s, OWNER_A, eLoser, eLoser2, "SIMILAR_EXPANSION", "discogs", t0);

            // scan_job: survivor already has its own ticketmaster job -> loser's is simply deleted.
            insertScanJob(s, OWNER_A, eSurv, "ticketmaster", t0.plus(10, ChronoUnit.MINUTES));
            insertScanJob(s, OWNER_A, eLoser, "ticketmaster", t0.plus(5, ChronoUnit.MINUTES));
            // scan_job: no survivor row for bandsintown -- earliest next_due_at among losers wins
            // and is repointed; the later one is deleted.
            insertScanJob(s, OWNER_A, eLoser2, "bandsintown", t0.plus(20, ChronoUnit.MINUTES));
            insertScanJob(s, OWNER_A, eLoser, "bandsintown", t0.plus(8, ChronoUnit.MINUTES));
            // scan_job: same shape for setlistfm, to prove this isn't a one-off.
            insertScanJob(s, OWNER_A, eLoser, "setlistfm", t0.plus(30, ChronoUnit.MINUTES));
            insertScanJob(s, OWNER_A, eLoser2, "setlistfm", t0.plus(15, ChronoUnit.MINUTES));

            // expand_job: single loser row, no collision -- simple repoint.
            insertExpandJob(s, OWNER_A, eLoser, "musicbrainz", t0.plus(1, ChronoUnit.MINUTES));
        }

        // 2. Pre-migration baseline.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(count(s, "artist")).as("seeded artist count before V13").isEqualTo(18);
            assertThat(count(s, "artist_edge")).as("seeded edge count before V13").isEqualTo(4);
            assertThat(count(s, "scan_job")).as("seeded scan_job count before V13").isEqualTo(6);
            assertThat(count(s, "expand_job")).as("seeded expand_job count before V13").isEqualTo(1);
        }

        // 3. Migrate to latest (V13 runs the merge).
        Flyway toLatest = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult latestResult = toLatest.migrate();
        assertThat(latestResult.success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 4. Artist survivors: one per group, right status, right ids.
            assertThat(count(s, "artist")).as("artist count after V13").isEqualTo(10);

            assertArtistAbsent(s, caseA);
            assertArtistStatus(s, caseB, "APPROVED");

            assertArtistAbsent(s, dashB);
            assertArtistStatus(s, dashA, "SEED");

            assertArtistAbsent(s, quoteB);
            assertArtistStatus(s, quoteA, "PENDING_REVIEW");

            assertArtistAbsent(s, rejPending);
            assertArtistStatus(s, rejRejected, "REJECTED");

            assertArtistAbsent(s, eLoser);
            assertArtistAbsent(s, eLoser2);
            assertArtistStatus(s, eSurv, "APPROVED");
            assertArtistStatus(s, third, "SEED");

            assertArtistAbsent(s, isoA2);
            assertArtistStatus(s, isoA, "SEED");
            assertArtistAbsent(s, isoB2);
            assertArtistStatus(s, isoB, "SEED");

            assertArtistStatus(s, hebrew, "SEED");
            assertArtistStatus(s, japanese, "SEED");

            // 5. Edges: duplicate and self-loop collapsed away; clean repoint kept.
            assertThat(count(s, "artist_edge")).as("edge count after V13").isEqualTo(2);
            assertEdge(s, OWNER_A, eSurv, third, "SIMILAR_EXPANSION", "lastfm");
            assertEdge(s, OWNER_A, eSurv, third, "MEMBER_EXPANSION", "musicbrainz");
            assertNoEdgeReferencing(s, eLoser);
            assertNoEdgeReferencing(s, eLoser2);

            // 6. scan_job: survivor's own row kept, loser's deleted; earliest-next_due_at loser
            // repointed for the sources with no pre-existing survivor row.
            assertThat(count(s, "scan_job")).as("scan_job count after V13").isEqualTo(3);
            assertJob(s, "scan_job", OWNER_A, eSurv, "ticketmaster");
            assertJob(s, "scan_job", OWNER_A, eSurv, "bandsintown");
            assertJob(s, "scan_job", OWNER_A, eSurv, "setlistfm");

            // 7. expand_job: single-row repoint.
            assertThat(count(s, "expand_job")).as("expand_job count after V13").isEqualTo(1);
            assertJob(s, "expand_job", OWNER_A, eSurv, "musicbrainz");

            // 8. Invariant: no orphan FK -- every artist_edge/scan_job/expand_job row points at an
            // artist id that still exists.
            assertThat(orphanCount(s, "artist_edge", "from_artist_id")).as("no orphan from_artist_id").isZero();
            assertThat(orphanCount(s, "artist_edge", "to_artist_id")).as("no orphan to_artist_id").isZero();
            assertThat(orphanCount(s, "scan_job", "artist_id")).as("no orphan scan_job.artist_id").isZero();
            assertThat(orphanCount(s, "expand_job", "artist_id")).as("no orphan expand_job.artist_id").isZero();
        }

        // 9. Idempotency: re-running the merge logic directly (Flyway itself will never re-apply an
        // already-applied versioned migration, so this exercises it out-of-band) changes nothing.
        try (Connection c = postgres.createConnection("")) {
            V13__merge_duplicate_variant_artists.merge(c);
        }
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(count(s, "artist")).as("artist count unchanged by re-running the merge").isEqualTo(10);
            assertThat(count(s, "artist_edge")).as("edge count unchanged by re-running the merge").isEqualTo(2);
            assertThat(count(s, "scan_job")).as("scan_job count unchanged by re-running the merge").isEqualTo(3);
            assertThat(count(s, "expand_job")).as("expand_job count unchanged by re-running the merge").isEqualTo(1);
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

    private static void insertScanJob(Statement s, String owner, long artistId, String source, Instant nextDueAt)
            throws Exception {
        insertJob(s, "scan_job", owner, artistId, source, nextDueAt);
    }

    private static void insertExpandJob(Statement s, String owner, long artistId, String source, Instant nextDueAt)
            throws Exception {
        insertJob(s, "expand_job", owner, artistId, source, nextDueAt);
    }

    private static void insertJob(Statement s, String table, String owner, long artistId, String source,
                                    Instant nextDueAt) throws Exception {
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

    private static int count(Statement s, String table) throws Exception {
        ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table);
        rs.next();
        return rs.getInt(1);
    }

    private static void assertArtistAbsent(Statement s, long id) throws Exception {
        ResultSet rs = s.executeQuery("SELECT count(*) FROM artist WHERE id = " + id);
        rs.next();
        assertThat(rs.getInt(1)).as("artist id " + id + " deleted").isEqualTo(0);
    }

    private static void assertArtistStatus(Statement s, long id, String expectedStatus) throws Exception {
        ResultSet rs = s.executeQuery("SELECT status FROM artist WHERE id = " + id);
        assertThat(rs.next()).as("artist id " + id + " survives").isTrue();
        assertThat(rs.getString("status")).as("artist id " + id + " status").isEqualTo(expectedStatus);
    }

    private static void assertEdge(Statement s, String owner, long fromId, long toId, String type, String source)
            throws Exception {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM artist_edge WHERE owner = '" + owner + "' AND from_artist_id = " + fromId
                        + " AND to_artist_id = " + toId + " AND type = '" + type + "' AND source = '" + source + "'");
        rs.next();
        assertThat(rs.getInt(1)).as("edge " + fromId + "->" + toId + "/" + type + "/" + source + " present")
                .isEqualTo(1);
    }

    private static void assertNoEdgeReferencing(Statement s, long artistId) throws Exception {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM artist_edge WHERE from_artist_id = " + artistId + " OR to_artist_id = "
                        + artistId);
        rs.next();
        assertThat(rs.getInt(1)).as("no edge references deleted artist id " + artistId).isEqualTo(0);
    }

    private static void assertJob(Statement s, String table, String owner, long artistId, String source)
            throws Exception {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM " + table + " WHERE owner = '" + owner + "' AND artist_id = " + artistId
                        + " AND source = '" + source + "'");
        rs.next();
        assertThat(rs.getInt(1)).as(table + " row for artist " + artistId + "/" + source + " present")
                .isEqualTo(1);
    }

    private static int orphanCount(Statement s, String table, String fkColumn) throws Exception {
        ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM " + table + " t LEFT JOIN artist a ON a.id = t." + fkColumn
                        + " WHERE a.id IS NULL");
        rs.next();
        return rs.getInt(1);
    }
}
