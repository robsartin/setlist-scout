package com.robsartin.setlistscout.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #223: {@code V28__add_show_event_artist_id} adds {@code show_event.artist_id} and backfills
 * it for every row that already existed before it ran -- the riskiest part of the migration, since
 * it runs once against the full live table (227 rows profiled 2026-08-21: 172 venue, 6 band-site,
 * 49 ticketmaster) and can never be re-triggered for a row it got wrong.
 * <p>
 * Builds a populated pre-V28 DB (migrated through V26) with one hand-seeded {@code artist} row per
 * case the migration has to get right: an exact-name match, a CASE variant, and a HYPHEN-SPACING
 * variant -- deliberately not just a case variant, since a case-only fixture cannot distinguish the
 * required {@link com.robsartin.setlistscout.catalog.ArtistNameNormalizer} backfill from a cheaper
 * {@code toLowerCase()} substitute (see {@code NormalizedNameBackfillMigrationTest}'s identical
 * concern for V19). Also seeds an unmatched Ticketmaster-style event-title row (must stay null,
 * unaltered) and a same-name row owned by a DIFFERENT owner (must stay null -- the backfill must
 * never link a show to another owner's artist). Runs in CI (needs Docker).
 */
@Testcontainers
class AddShowEventArtistIdMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "migration-223@example.com";
    private static final String OTHER_OWNER = "migration-223-other@example.com";

    @Test
    void backfillsArtistIdByNormalizedNameAndLeavesUnmatchedAndCrossOwnerRowsNull() throws Exception {
        // 1. Migrate up to V26 (pre-V28 state): artist_id does not exist yet.
        Flyway toV26 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .target("26")
                .load();
        assertThat(toV26.migrate().success).isTrue();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        long exactMatchArtistId;
        long caseVariantArtistId;
        long hyphenSpacingArtistId;
        long crossOwnerArtistId;
        long exactMatchShowId;
        long caseVariantShowId;
        long hyphenSpacingShowId;
        long unmatchedShowId;
        long crossOwnerShowId;

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            exactMatchArtistId = insertArtist(s, OWNER, "Wilco", t0);
            caseVariantArtistId = insertArtist(s, OWNER, "Radiohead", t0.plusSeconds(1));
            hyphenSpacingArtistId = insertArtist(s, OWNER, "Emerson Lake-Palmer", t0.plusSeconds(2));
            crossOwnerArtistId = insertArtist(s, OTHER_OWNER, "Cross Owner Band", t0.plusSeconds(3));

            // Exact name match.
            exactMatchShowId = insertShow(s, OWNER, "Wilco", "The Moody Center");
            // Case variant -- a bare toLowerCase() would also catch this one, which is why it alone
            // would NOT distinguish the real normalizer from a cheaper substitute.
            caseVariantShowId = insertShow(s, OWNER, "RADIOHEAD", "ACL Live");
            // Hyphen-spacing variant (issue #157's fold rule): "Emerson Lake - Palmer" (spaced)
            // against a stored "Emerson Lake-Palmer" (unspaced) -- toLowerCase() alone would NOT
            // match these (different literal strings even after case-folding); only
            // ArtistNameNormalizer's whitespace-touching-a-hyphen collapse does.
            hyphenSpacingShowId = insertShow(s, OWNER, "Emerson Lake - Palmer", "Stubb's");
            // Ticketmaster-style: artist_name is the EVENT TITLE, matches nothing in the catalog.
            unmatchedShowId = insertShow(s, OWNER,
                    "A Very Merry Symphony ft. Austin Symphony Orchestra", "Long Center");
            // Same literal name as another owner's artist -- must NOT link cross-owner.
            crossOwnerShowId = insertShow(s, OWNER, "Cross Owner Band", "Mohawk");
        }

        // 2. Pre-migration baseline: the column does not exist at all yet.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            ResultSet columnExists = s.executeQuery(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_name = 'show_event' AND column_name = 'artist_id'");
            columnExists.next();
            assertThat(columnExists.getInt(1)).as("artist_id column absent before V28").isZero();
        }

        // 3. Migrate to latest (V28 adds the column, index, FK, and runs the Java backfill).
        Flyway toLatest = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult latest = toLatest.migrate();
        assertThat(latest.success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(artistIdOf(s, exactMatchShowId))
                    .as("exact name match").isEqualTo(exactMatchArtistId);
            assertThat(artistIdOf(s, caseVariantShowId))
                    .as("case variant matched via ArtistNameNormalizer").isEqualTo(caseVariantArtistId);
            assertThat(artistIdOf(s, hyphenSpacingShowId))
                    .as("hyphen-spacing variant matched -- proves real normalizer, not toLowerCase()")
                    .isEqualTo(hyphenSpacingArtistId);
            assertThat(artistIdOf(s, unmatchedShowId))
                    .as("an event-title row that matches nothing stays null").isNull();
            assertThat(artistIdOf(s, crossOwnerShowId))
                    .as("owner-scoped -- never linked to another owner's artist despite the "
                            + "identical name").isNull();

            // 4. Nothing else about the unmatched row was touched.
            ResultSet unmatchedRow = s.executeQuery(
                    "SELECT artist_name, venue_name FROM show_event WHERE id = " + unmatchedShowId);
            unmatchedRow.next();
            assertThat(unmatchedRow.getString("artist_name"))
                    .isEqualTo("A Very Merry Symphony ft. Austin Symphony Orchestra");
            assertThat(unmatchedRow.getString("venue_name")).isEqualTo("Long Center");

            // 5. No row deleted or added by the backfill itself.
            ResultSet count = s.executeQuery("SELECT count(*) FROM show_event");
            count.next();
            assertThat(count.getInt(1)).as("no rows deleted or added by V28").isEqualTo(5);

            // 6. The column is indexed (the whole point -- #220's filter becomes a join).
            ResultSet indexExists = s.executeQuery(
                    "SELECT count(*) FROM pg_indexes WHERE tablename = 'show_event' "
                            + "AND indexdef LIKE '%artist_id%'");
            indexExists.next();
            assertThat(indexExists.getInt(1)).as("artist_id is indexed").isGreaterThanOrEqualTo(1);

            // 7. A bogus artist_id is rejected -- the column is a real FK, not just a bigint.
            assertThatThrownBy(() -> s.execute(
                    "UPDATE show_event SET artist_id = 999999999 WHERE id = " + exactMatchShowId))
                    .as("artist_id enforces a real foreign key to artist(id)")
                    .isInstanceOf(java.sql.SQLException.class);
        }

        // 8. ON DELETE SET NULL: deleting the referenced artist must not strand or delete its show.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            s.execute("DELETE FROM artist WHERE id = " + exactMatchArtistId);
            ResultSet afterDelete = s.executeQuery(
                    "SELECT artist_id FROM show_event WHERE id = " + exactMatchShowId);
            assertThat(afterDelete.next())
                    .as("the show row itself survives the artist's deletion").isTrue();
            assertThat(afterDelete.getObject("artist_id"))
                    .as("artist_id is set NULL, not left dangling").isNull();
        }
    }

    private static long insertArtist(Statement s, String owner, String name, Instant createdAt) throws Exception {
        // By V26, artist.normalized_name is NOT NULL + UNIQUE(owner, normalized_name) (V19/V21) --
        // a raw INSERT (no @PrePersist callback) must supply it explicitly, same as
        // NormalizedNameBackfillMigrationTest's own fixtures do for artist rows seeded post-V19.
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist (owner, name, normalized_name, source, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.setString(3, ArtistNameNormalizer.normalize(name));
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

    private static long insertShow(Statement s, String owner, String artistName, String venueName) throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO show_event (owner, artist_name, event_date_time, venue_name, source, "
                        + "discovered_at, kind) VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner);
            ps.setString(2, artistName);
            ps.setTimestamp(3, Timestamp.valueOf("2026-09-01 20:00:00"));
            ps.setString(4, venueName);
            ps.setString(5, "ticketmaster");
            ps.setTimestamp(6, Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")));
            ps.setString(7, "MUSIC");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static Long artistIdOf(Statement s, long showId) throws Exception {
        ResultSet rs = s.executeQuery("SELECT artist_id FROM show_event WHERE id = " + showId);
        rs.next();
        long value = rs.getLong("artist_id");
        return rs.wasNull() ? null : value;
    }
}
