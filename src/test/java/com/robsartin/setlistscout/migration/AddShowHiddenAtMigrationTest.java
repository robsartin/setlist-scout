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
 * Issue #166: {@code show_event.hidden_at} lets an owner hide a single show from the default Shows
 * list (reversible via Unhide) without touching any other data on the row. Nullable timestamp, not
 * a boolean -- see V17's own comment for why.
 *
 * <p>Proves: the column exists and is queryable, every pre-existing row is left with
 * {@code hidden_at} NULL (unaffected -- "not hidden" is the correct migrated state for data nobody
 * has hidden yet), a fresh row's default (an insert that never mentions the column) is also NULL,
 * and the column genuinely round-trips a value rather than being some read-only artifact. Runs in
 * CI (needs Docker).
 */
@Testcontainers
class AddShowHiddenAtMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "migration-166@example.com";

    @Test
    void addsNullableHiddenAtColumnDefaultingToNullWithoutTouchingExistingRows() throws Exception {
        // 1. Migrate up to V16 (pre-V17 state) so show_event exists without hidden_at.
        Flyway toV16 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .target("16")
                .load();
        assertThat(toV16.migrate().success).isTrue();

        Instant discoveredAt = Instant.parse("2026-01-01T00:00:00Z");
        long preExisting;
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            preExisting = insertShow(s, "Wilco", "The Moody Center", discoveredAt);
        }

        // 2. Confirm the fixture is real: pre-V17, hidden_at doesn't exist at all.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> s.executeQuery("SELECT hidden_at FROM show_event WHERE id = " + preExisting))
                    .as("hidden_at must not exist before V17").isInstanceOf(java.sql.SQLException.class);
        }

        // 3. Migrate to latest (V17 adds the column).
        Flyway toLatest = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult latest = toLatest.migrate();
        assertThat(latest.success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 4. The column exists and is queryable, and the pre-existing row is unaffected.
            assertThat(hiddenAt(s, preExisting)).as("pre-existing row is unaffected -- not hidden").isNull();

            // 5. The rest of the pre-existing row is untouched.
            ResultSet rs = s.executeQuery("SELECT artist_name, venue_name FROM show_event WHERE id = " + preExisting);
            rs.next();
            assertThat(rs.getString("artist_name")).isEqualTo("Wilco");
            assertThat(rs.getString("venue_name")).isEqualTo("The Moody Center");

            // 6. A freshly inserted row that doesn't mention hidden_at also defaults to NULL.
            // Post-latest-migration, not insertShow(): #202/V23 made show_event.kind NOT NULL
            // after this test's own migration point (V17), so an insert running after "migrate to
            // latest" must supply it -- insertShow's column list stays exactly what V17 expected,
            // since its OTHER call (line ~53, pre-V17) still needs to omit a column that doesn't
            // exist yet at that point in the migration timeline.
            long fresh = insertShowWithKind(s, "Radiohead", "ACL Live", discoveredAt, "MUSIC");
            assertThat(hiddenAt(s, fresh)).as("fresh row's default is also NULL (not hidden)").isNull();

            // 7. The column can actually be set (round-trips a real timestamp) -- proves it's not
            // somehow generated/read-only.
            s.execute("UPDATE show_event SET hidden_at = now() WHERE id = " + fresh);
            assertThat(hiddenAt(s, fresh)).as("hidden_at can be set").isNotNull();

            // 8. No rows lost.
            ResultSet count = s.executeQuery("SELECT count(*) FROM show_event");
            count.next();
            assertThat(count.getInt(1)).as("no rows deleted by V17").isEqualTo(2);
        }
    }

    private static long insertShow(Statement s, String artistName, String venueName, Instant discoveredAt)
            throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO show_event (owner, artist_name, event_date_time, venue_name, source, discovered_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, OWNER);
            ps.setString(2, artistName);
            ps.setTimestamp(3, Timestamp.valueOf("2026-09-01 20:00:00"));
            ps.setString(4, venueName);
            ps.setString(5, "ticketmaster");
            ps.setTimestamp(6, Timestamp.from(discoveredAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Like {@link #insertShow}, but also sets {@code kind} -- needed for an insert running after V23 made it NOT NULL. */
    private static long insertShowWithKind(Statement s, String artistName, String venueName,
                                            Instant discoveredAt, String kind) throws Exception {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO show_event (owner, artist_name, event_date_time, venue_name, source, discovered_at, kind) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, OWNER);
            ps.setString(2, artistName);
            ps.setTimestamp(3, Timestamp.valueOf("2026-09-01 20:00:00"));
            ps.setString(4, venueName);
            ps.setString(5, "ticketmaster");
            ps.setTimestamp(6, Timestamp.from(discoveredAt));
            ps.setString(7, kind);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static Instant hiddenAt(Statement s, long id) throws Exception {
        ResultSet rs = s.executeQuery("SELECT hidden_at FROM show_event WHERE id = " + id);
        rs.next();
        Timestamp ts = rs.getTimestamp("hidden_at");
        return ts == null ? null : ts.toInstant();
    }
}
