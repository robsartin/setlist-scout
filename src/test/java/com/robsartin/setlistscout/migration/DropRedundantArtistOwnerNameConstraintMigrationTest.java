package com.robsartin.setlistscout.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #191: production's {@code artist} table carries a SECOND, REDUNDANT
 * {@code UNIQUE (owner, name)} constraint alongside the one the migrations declare
 * ({@code artist_owner_name_key}, from {@code V1__baseline.sql}). Its name is Hibernate's legacy
 * {@code ddl-auto=update} signature -- a {@code uk<hash>} name, e.g.
 * {@code ukpn9qra77pidiwnm8vj55pl6bt} -- environment-specific and never written in any migration,
 * from before ADR-0009 moved the schema to Flyway. It is the same class of bug as #141 (V15, see
 * {@link DropOrphanedArtistNameConstraintMigrationTest}): invisible in the migration history, and
 * since #179 moved {@code ArtistRepository.insertIfAbsent}'s {@code ON CONFLICT} target to
 * {@code (owner, normalized_name)}, neither remaining {@code (owner, name)} constraint is the
 * arbiter of inserts any more, so a future change to the declared one would leave this invisible
 * twin still enforcing the old rule.
 *
 * <p>Unlike V15 (whose target column set {@code {name}} could never match the composite
 * constraint), this migration's signature -- {@code {name, owner}} -- matches BOTH the redundant
 * constraint AND {@code artist_owner_name_key} itself, so
 * {@link #dropsRedundantConstraintButKeepsDeclaredOneEnforced} exists specifically to pin that the
 * declared one survives, by name, rather than by accident of which match the loop happens to hit
 * first.
 *
 * <p>Builds a database at V21 (so {@code owner}, {@code name} and {@code normalized_name} all
 * exist and both real constraints are already in place), then manually adds a second
 * {@code UNIQUE (owner, name)} constraint under a deliberately fake Hibernate-style hash name --
 * exactly what production's ddl-auto leftover looks like, but a different-looking hash than the
 * real one, to prove the migration finds it by column signature, not a hardcoded/guessed name.
 * Migrates to latest and asserts: the redundant constraint is gone, {@code artist_owner_name_key}
 * survives untouched, {@code artist_owner_normalized_name_key} is unaffected, and the composite
 * constraint still does its job -- one owner still cannot duplicate a name, two different owners
 * still can share one.
 *
 * <p>{@link #noOpWhenTheRedundantConstraintNeverExisted} is the companion case: a database built
 * from V1 onward never has the redundant constraint (V1 creates {@code artist} with only the
 * named composite), so this proves the migration finds nothing and does nothing harmful when
 * there is nothing to drop -- the scenario the other test's fixture can never exercise, since it
 * deliberately creates the very thing being dropped. Each test method gets its own container
 * (instance, not static field) so the two starting states can never leak into each other. Runs in
 * CI (needs Docker).
 */
@Testcontainers
class DropRedundantArtistOwnerNameConstraintMigrationTest {

    @Container
    final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER_A = "owner-a@example.com";
    private static final String OWNER_B = "owner-b@example.com";

    // Deliberately a DIFFERENT-looking hash than the real prod name (ukpn9qra77pidiwnm8vj55pl6bt),
    // to prove the migration finds it by column signature, not a hardcoded/guessed name.
    private static final String REDUNDANT_CONSTRAINT_NAME = "uk00112233445566778899aa";

    @Test
    void dropsRedundantConstraintButKeepsDeclaredOneEnforced() throws Exception {
        // 1. Migrate to a version below V22: owner, name and normalized_name all exist, and both
        // real constraints (artist_owner_name_key, artist_owner_normalized_name_key) are already
        // in place.
        assertThat(migrate("21").success).isTrue();

        // 2. Manually create the redundant constraint plus a pre-existing row, mirroring exactly
        // what a real ddl-auto-era production table looks like.
        String nebraskaNormalized = ArtistNameNormalizer.normalize("Nebraska");
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE artist ADD CONSTRAINT " + REDUNDANT_CONSTRAINT_NAME
                    + " UNIQUE (owner, name)");
            s.execute("INSERT INTO artist (owner, name, normalized_name, source, status, created_at) "
                    + "VALUES ('" + OWNER_A + "', 'Nebraska', '" + nebraskaNormalized
                    + "', 'SEED_LIST', 'SEED', now())");
        }

        // 3. Confirm the fixture is real: three UNIQUE constraints exist, two of them on the exact
        // same column set -- the shape production carries, read-only profiled 2026-08-18.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(uniqueConstraintNames(s))
                    .as("fixture has both real constraints plus the fake redundant one")
                    .containsExactlyInAnyOrder("artist_owner_name_key", "artist_owner_normalized_name_key",
                            REDUNDANT_CONSTRAINT_NAME);
        }

        // 4. Migrate to latest -- V22 finds the redundant constraint by signature and drops it.
        assertThat(migrate(null).success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 5. Exactly the two real constraints remain -- the redundant one dropped, nothing
            // else touched.
            assertThat(uniqueConstraintNames(s))
                    .as("only the two declared constraints remain")
                    .containsExactlyInAnyOrder("artist_owner_name_key", "artist_owner_normalized_name_key");

            // 6. Same owner, same name, but a normalized_name that is NOT the normalizer's output
            // for it -- the one case only the (owner, name) constraint can catch, isolating that
            // artist_owner_name_key specifically is still enforcing (not merely the stronger
            // artist_owner_normalized_name_key). Mirrors
            // DropOrphanedArtistNameConstraintMigrationTest's same trick.
            assertThatThrownBy(() -> s.execute(
                    "INSERT INTO artist (owner, name, normalized_name, source, status, created_at) "
                            + "VALUES ('" + OWNER_A + "', 'Nebraska', 'not-the-normalized-form', "
                            + "'SEED_LIST', 'SEED', now())"))
                    .as("one owner still cannot duplicate a name -- artist_owner_name_key enforces it")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("artist_owner_name_key");
        }

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 7. Two DIFFERENT owners can still share a name -- neither surviving constraint scopes
            // across owners, so this must keep succeeding exactly as it did before the migration.
            s.execute("INSERT INTO artist (owner, name, normalized_name, source, status, created_at) "
                    + "VALUES ('" + OWNER_B + "', 'Nebraska', '" + nebraskaNormalized
                    + "', 'SEED_LIST', 'SEED', now())");

            ResultSet rs = s.executeQuery("SELECT count(*) FROM artist WHERE name = 'Nebraska'");
            rs.next();
            assertThat(rs.getInt(1)).as("both owners' Nebraska rows persisted").isEqualTo(2);
        }
    }

    @Test
    void noOpWhenTheRedundantConstraintNeverExisted() throws Exception {
        // A database built from V1 onward never carries the redundant constraint -- this is the
        // actual shape of every dev/test/CI database. Migrate straight to latest with nothing
        // manually added, and prove V22's signature loop simply finds nothing to drop.
        assertThat(migrate(null).success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(uniqueConstraintNames(s))
                    .as("only the two declared constraints ever existed -- V22 had nothing to drop")
                    .containsExactlyInAnyOrder("artist_owner_name_key", "artist_owner_normalized_name_key");
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

    /** Names of every UNIQUE constraint currently on {@code artist}. */
    private static List<String> uniqueConstraintNames(Statement s) throws SQLException {
        ResultSet rs = s.executeQuery(
                "SELECT conname FROM pg_constraint WHERE contype = 'u' AND conrelid = 'artist'::regclass");
        List<String> result = new ArrayList<>();
        while (rs.next()) {
            result.add(rs.getString("conname"));
        }
        return result;
    }
}
