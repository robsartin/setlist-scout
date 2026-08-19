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
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #202: {@code show_event.kind} records whether a show is music or comedy, read from
 * Ticketmaster's own per-event classification (see {@code TicketmasterService#classify}).
 * NOT NULL with a backfill, not nullable-as-unknown -- see V23's own comment for why.
 *
 * <p>Proves: the column doesn't exist pre-V23, every pre-existing row is backfilled to
 * {@code 'MUSIC'} (accurate -- every row that exists today was found under the old
 * classificationName=music-only filter), NOT NULL is enforced for a fresh insert that omits the
 * column, the {@code CHECK} constraint rejects a value outside {@code MUSIC}/{@code COMEDY}, a
 * valid {@code 'COMEDY'} value round-trips, and no row is lost. Runs in CI (needs Docker).
 */
@Testcontainers
class AddShowEventKindMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "migration-202@example.com";

    @Test
    void backfillsExistingRowsToMusicAndEnforcesNotNullPlusCheckGoingForward() throws Exception {
        // 1. Migrate up to V22 (pre-V23 state) so show_event exists without kind.
        Flyway toV22 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .target("22")
                .load();
        assertThat(toV22.migrate().success).isTrue();

        long preExisting;
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            preExisting = insertShowWithoutKind(s, "Wilco", "The Moody Center");
        }

        // 2. Confirm the fixture is real: pre-V23, kind doesn't exist at all.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELECT kind FROM show_event WHERE id = " + preExisting))
                    .as("kind must not exist before V23").isInstanceOf(SQLException.class);
        }

        // 3. Migrate to latest (V23 adds the column, backfills, and constrains it).
        Flyway toLatest = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult latest = toLatest.migrate();
        assertThat(latest.success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 4. The pre-existing row is backfilled to MUSIC -- accurate, not a guess, since it
            // was found under the old music-only filter.
            assertThat(kindOf(s, preExisting)).as("pre-existing row backfilled to MUSIC").isEqualTo("MUSIC");

            // 5. The rest of the pre-existing row is untouched.
            ResultSet rs = s.executeQuery("SELECT artist_name, venue_name FROM show_event WHERE id = " + preExisting);
            rs.next();
            assertThat(rs.getString("artist_name")).isEqualTo("Wilco");
            assertThat(rs.getString("venue_name")).isEqualTo("The Moody Center");

            // 6. NOT NULL is enforced: a fresh insert that omits kind now fails.
            assertThatThrownBy(() -> insertShowWithoutKind(s, "Radiohead", "ACL Live"))
                    .as("kind is NOT NULL after V23").isInstanceOf(SQLException.class);

            // 7. The CHECK constraint rejects a value outside MUSIC/COMEDY.
            assertThatThrownBy(() -> insertShowWithKind(s, "Some Sport", "Arena", "SPORTS"))
                    .as("kind is constrained to MUSIC/COMEDY").isInstanceOf(SQLException.class);

            // 8. A valid COMEDY value round-trips.
            long comedy = insertShowWithKind(s, "Aziz Ansari", "Moody Center", "COMEDY");
            assertThat(kindOf(s, comedy)).isEqualTo("COMEDY");

            // 9. No rows lost: the pre-existing row plus the one successful insert above.
            ResultSet count = s.executeQuery("SELECT count(*) FROM show_event");
            count.next();
            assertThat(count.getInt(1)).as("no rows deleted by V23").isEqualTo(2);
        }
    }

    private static long insertShowWithoutKind(Statement s, String artistName, String venueName) throws SQLException {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO show_event (owner, artist_name, event_date_time, venue_name, source, discovered_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, OWNER);
            ps.setString(2, artistName);
            ps.setTimestamp(3, Timestamp.valueOf("2026-09-01 20:00:00"));
            ps.setString(4, venueName);
            ps.setString(5, "ticketmaster");
            ps.setTimestamp(6, Timestamp.valueOf("2026-01-01 00:00:00"));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertShowWithKind(Statement s, String artistName, String venueName, String kind)
            throws SQLException {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO show_event (owner, artist_name, event_date_time, venue_name, source, discovered_at, kind) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, OWNER);
            ps.setString(2, artistName);
            ps.setTimestamp(3, Timestamp.valueOf("2026-09-01 20:00:00"));
            ps.setString(4, venueName);
            ps.setString(5, "ticketmaster");
            ps.setTimestamp(6, Timestamp.valueOf("2026-01-01 00:00:00"));
            ps.setString(7, kind);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static String kindOf(Statement s, long id) throws SQLException {
        ResultSet rs = s.executeQuery("SELECT kind FROM show_event WHERE id = " + id);
        rs.next();
        return rs.getString("kind");
    }
}
