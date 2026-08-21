package com.robsartin.setlistscout.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #230: production's {@code show_event} table carries THREE unique constraints, not the one
 * the {@link com.robsartin.setlistscout.scan.Show} entity declares:
 *
 * <pre>
 * show_event_owner_artist_name_event_date_time_venue_name_key  UNIQUE (owner, artist_name, event_date_time, venue_name)  -- declared, V1
 * uk&lt;hash&gt;                                                       UNIQUE (owner, artist_name, event_date_time, venue_name)  -- redundant twin
 * uk&lt;hash&gt;                                                       UNIQUE (artist_name, event_date_time, venue_name)          -- NO OWNER -- the bug
 * </pre>
 *
 * Both {@code uk<hash>} names are Hibernate {@code ddl-auto=update}-era leftovers, never written
 * in any migration -- the same class of bug {@code V22} (#191) found and dropped on {@code
 * artist}. The owner-less one is the actual production defect: it lets any given concert exist
 * for only ONE owner in the whole system, so a second user (or a shared scan, whose whole point is
 * to surface shows two people both follow) discovering an already-stored show cannot save it --
 * observed for real as the {@code rob}+{@code david} shared scan's Brandi Carlile job failing
 * against {@code uklue4drx2rhjt9e4wsrered7tv} even though the row it collided with belonged to a
 * different owner entirely.
 *
 * <p><b>Two distinct column signatures, unlike V22's one.</b> Signature
 * {@code {artist_name, event_date_time, owner, venue_name}} matches BOTH the declared survivor and
 * its redundant twin -- exactly V22's shape, so (as there) the survivor must be excluded BY NAME or
 * a signature-only match could drop either one nondeterministically, including the one that must
 * stay. Signature {@code {artist_name, event_date_time, venue_name}} matches only the owner-less
 * constraint -- nothing else shares it, so any match there is safe to drop outright.
 * {@link #dropsBothLeftoverConstraintsButKeepsDeclaredOneEnforced} uses DELIBERATELY different fake
 * hash names than the real production ones (see V22's own test for the same practice) to prove the
 * migration finds its targets by column signature, not by a hardcoded/guessed name.
 *
 * <p>Builds a database at V28 (so every column {@code show_event} carries today -- {@code
 * hidden_at} (V17), {@code kind} (V23), {@code artist_id} (V28) -- already exists), manually adds
 * both leftover constraints plus a real row, migrates to latest, and asserts: exactly one unique
 * constraint remains ({@code show_event_owner_artist_name_event_date_time_venue_name_key}), the
 * pre-existing row survived untouched ({@code DROP CONSTRAINT} touches no rows), two DIFFERENT
 * owners can now share a natural key (the regression this issue is about), and the SAME owner still
 * cannot duplicate one (the surviving constraint still does its job).
 *
 * <p>{@link #noOpWhenNeitherLeftoverConstraintExisted} is the companion case: a database built from
 * V1 onward never carries either leftover (V1 creates {@code show_event} with only the named
 * composite constraint), so migrating straight to latest with nothing manually added proves the
 * migration finds nothing and does nothing harmful -- the shape of every real dev/test/CI database.
 * Each test method gets its own container (instance, not static field) so the two starting states
 * can never leak into each other. Runs in CI (needs Docker).
 */
@Testcontainers
class DropShowEventOwnerlessAndRedundantConstraintsMigrationTest {

    @Container
    final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER_A = "owner-a@example.com";
    private static final String OWNER_B = "owner-b@example.com";
    private static final String DECLARED_CONSTRAINT = "show_event_owner_artist_name_event_date_time_venue_name_key";

    // Deliberately DIFFERENT-looking hashes than the real prod names (ukgwkodx24oenxoi5mv4oqd41v8,
    // uklue4drx2rhjt9e4wsrered7tv), to prove the migration finds them by column signature, not a
    // hardcoded/guessed name.
    private static final String REDUNDANT_TWIN_NAME = "uk1a2b3c4d5e6f7a8b9c0d1e2f3a";
    private static final String OWNERLESS_CONSTRAINT_NAME = "uk9f8e7d6c5b4a39281706f5e4d3";

    @Test
    void dropsBothLeftoverConstraintsButKeepsDeclaredOneEnforced() throws Exception {
        // 1. Migrate to V28: every column show_event carries today already exists, and only the
        // one real, declared constraint is in place -- the shape every migration-built database has
        // before this one runs.
        assertThat(migrate("28").success).isTrue();

        // 2. Manually add both leftover constraints plus a real row, mirroring exactly what
        // production's ddl-auto-era table looks like (read-only profiled 2026-08-21).
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE show_event ADD CONSTRAINT " + REDUNDANT_TWIN_NAME
                    + " UNIQUE (owner, artist_name, event_date_time, venue_name)");
            s.execute("ALTER TABLE show_event ADD CONSTRAINT " + OWNERLESS_CONSTRAINT_NAME
                    + " UNIQUE (artist_name, event_date_time, venue_name)");
            s.execute("INSERT INTO show_event "
                    + "(owner, artist_name, event_date_time, venue_name, source, discovered_at, kind) "
                    + "VALUES ('" + OWNER_A + "', 'Brandi Carlile - The Human Tour', "
                    + "'2026-09-06 19:00:00', 'Moody Center ATX', 'ticketmaster', now(), 'MUSIC')");
        }

        // 3. Confirm the fixture is real: three UNIQUE constraints exist, two of them on the exact
        // same column set -- the shape production carries.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(uniqueConstraintNames(s))
                    .as("fixture has the declared constraint plus both fake leftovers")
                    .containsExactlyInAnyOrder(DECLARED_CONSTRAINT, REDUNDANT_TWIN_NAME, OWNERLESS_CONSTRAINT_NAME);
        }

        // 4. Migrate to latest -- V29 finds both leftovers by signature and drops them, excluding
        // the declared survivor by name.
        assertThat(migrate(null).success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 5. Exactly the declared constraint remains.
            assertThat(uniqueConstraintNames(s))
                    .as("only the declared owner-scoped constraint remains")
                    .containsExactly(DECLARED_CONSTRAINT);

            // 6. DROP CONSTRAINT touches no rows -- the pre-existing row is untouched.
            ResultSet rs = s.executeQuery("SELECT count(*) FROM show_event WHERE owner = '" + OWNER_A + "'");
            rs.next();
            assertThat(rs.getInt(1)).as("the pre-existing row survived the constraint drop").isEqualTo(1);
        }

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 7. The regression this issue is about: a DIFFERENT owner can now store the exact same
            // concert -- the owner-less constraint that used to reject this is gone.
            assertThatCode(() -> s.execute("INSERT INTO show_event "
                    + "(owner, artist_name, event_date_time, venue_name, source, discovered_at, kind) "
                    + "VALUES ('" + OWNER_B + "', 'Brandi Carlile - The Human Tour', "
                    + "'2026-09-06 19:00:00', 'Moody Center ATX', 'ticketmaster', now(), 'MUSIC')"))
                    .as("issue #230: a second owner can now hold the same show")
                    .doesNotThrowAnyException();
        }

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 8. The SAME owner still cannot duplicate their own show -- the surviving, declared
            // constraint still does its job.
            assertThatThrownBy(() -> s.execute("INSERT INTO show_event "
                    + "(owner, artist_name, event_date_time, venue_name, source, discovered_at, kind) "
                    + "VALUES ('" + OWNER_A + "', 'Brandi Carlile - The Human Tour', "
                    + "'2026-09-06 19:00:00', 'Moody Center ATX', 'ticketmaster', now(), 'MUSIC')"))
                    .as("the owner-scoped constraint still rejects a true duplicate")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(DECLARED_CONSTRAINT);
        }
    }

    @Test
    void noOpWhenNeitherLeftoverConstraintExisted() throws Exception {
        // A database built from V1 onward never carries either leftover -- this is the actual shape
        // of every dev/test/CI database. Migrate straight to latest with nothing manually added and
        // prove V29's signature loop simply finds nothing to drop.
        assertThat(migrate(null).success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(uniqueConstraintNames(s))
                    .as("V29 had nothing to drop -- only the declared constraint was ever there")
                    .containsExactly(DECLARED_CONSTRAINT);
        }
    }

    private MigrateResult migrate(String target) {
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

    /** Names of every UNIQUE constraint currently on {@code show_event}. */
    private static List<String> uniqueConstraintNames(Statement s) throws SQLException {
        ResultSet rs = s.executeQuery(
                "SELECT conname FROM pg_constraint WHERE contype = 'u' AND conrelid = 'show_event'::regclass");
        List<String> result = new ArrayList<>();
        while (rs.next()) {
            result.add(rs.getString("conname"));
        }
        return result;
    }
}
