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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #219: {@code artist_owner_name_key} -- {@code UNIQUE (owner, name)}, declared directly by
 * {@code V1__baseline.sql}'s {@code CREATE TABLE} and guaranteed present even on a legacy pre-V1
 * database by {@code V2__reconcile_owner_columns.sql}'s retrofit -- is dropped as strictly
 * redundant with the stronger {@code artist_owner_normalized_name_key} added by V21 (#179).
 *
 * <p>#179 kept this constraint deliberately, arguing that since {@code normalize} is a function of
 * {@code name}, the normalized constraint strictly implies this one, so "this one can never fire on
 * its own" (see {@code ArtistRepository#insertIfAbsent}'s javadoc, as it read before this
 * migration). CI (PR #226) falsified that "never" outright: {@code ArtistSeedServiceRaceTest} threw
 * a genuine {@code DataIntegrityViolationException} on {@code artist_owner_name_key} under real
 * concurrency, with data a full Java recompute proved fully self-consistent (zero of 33,077
 * production rows have a {@code normalized_name} differing from a fresh {@code normalize()}). The
 * mechanism needs no inconsistent data at all: Postgres {@code ON CONFLICT} uses SPECULATIVE
 * insertion -- pre-check the arbiter index, insert speculatively if clear, then insert into EVERY
 * index -- and a conflict on a NON-arbiter index raises instead of being absorbed. Two genuinely
 * simultaneous inserts of the same brand-new name can both pass the {@code (owner,
 * normalized_name)} arbiter's pre-check, both insert speculatively, and only then collide on
 * {@code artist_owner_name_key} during index insertion. See the issue's comment history for the two
 * earlier, wrong diagnoses (a sequential probe, then an artificially-staggered concurrent one) that
 * could not reach this window.
 *
 * <p>This is the schema assertion the fix rests on: {@code artist} carries exactly ONE unique
 * constraint that includes {@code owner} after V27 -- the normalized one -- so a future entity or
 * migration change cannot silently reintroduce the second. It also proves the redundancy half of
 * #179's argument, which was always correct and is what makes dropping this constraint (rather than
 * re-targeting {@code ON CONFLICT} again) safe: every real duplicate {@code artist_owner_name_key}
 * ever caught is still caught, by the stronger constraint, and cross-owner sharing -- which neither
 * constraint ever restricted -- is unaffected. The one behaviour that genuinely changes is recorded
 * deliberately at the end of the test, not left as a silent side effect. Runs in CI (needs Docker).
 */
@Testcontainers
class DropArtistOwnerNameConstraintMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER_A = "owner-a@example.com";
    private static final String OWNER_B = "owner-b@example.com";

    @Test
    void dropsTheNameConstraintButNormalizedUniquenessStillCatchesEveryRealDuplicate() throws Exception {
        // 1. Migrate to right below V27: both real constraints exist, exactly production's shape.
        assertThat(migrate("26").success).isTrue();

        String nebraskaNormalized = ArtistNameNormalizer.normalize("Nebraska");
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            insertArtist(s, OWNER_A, "Nebraska", nebraskaNormalized);

            // 2. Confirm the fixture is real, and pin production's actual pre-migration shape
            // (read-only profiled 2026-08-21): exactly two UNIQUE constraints on artist, both
            // including owner.
            assertThat(uniqueConstraintNames(s))
                    .as("both real constraints present before V27 -- production's shape")
                    .containsExactlyInAnyOrder("artist_owner_name_key", "artist_owner_normalized_name_key");
        }

        // 3. Isolates that artist_owner_name_key alone is still enforcing something pre-migration:
        // same owner + same name, but a normalized_name that does NOT match the normalizer's
        // output, so the stronger constraint sees no conflict and only the narrower one can catch
        // it. Mirrors DropOrphanedArtistNameConstraintMigrationTest's and
        // DropRedundantArtistOwnerNameConstraintMigrationTest's same trick.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> insertArtist(s, OWNER_A, "Nebraska", "not-the-normalized-form"))
                    .as("pre-migration: artist_owner_name_key alone rejects this")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("artist_owner_name_key");
        }

        // 4. Migrate to latest -- V27 drops artist_owner_name_key.
        assertThat(migrate(null).success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 5. THE schema assertion: exactly one unique constraint over owner remains, and it's
            // the normalized one, by name -- so a future entity/migration change cannot silently
            // reintroduce the second.
            assertThat(uniqueConstraintNames(s))
                    .as("artist_owner_name_key dropped -- only the normalized constraint remains")
                    .containsExactly("artist_owner_normalized_name_key");
        }

        // 6. Duplicate-name protection is NOT lost: a real, consistently-normalized repeat of
        // (owner, name) is still rejected -- now by the stronger constraint alone. This is the
        // assertion that proves dropping artist_owner_name_key lost no real protection.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> insertArtist(s, OWNER_A, "Nebraska", nebraskaNormalized))
                    .as("same owner + same name is still rejected, via the normalized arbiter now")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("artist_owner_normalized_name_key");
        }

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // 7. Cross-owner sharing is unaffected -- neither constraint ever scoped across owners.
            insertArtist(s, OWNER_B, "Nebraska", nebraskaNormalized);
            ResultSet rs = s.executeQuery("SELECT count(*) FROM artist WHERE name = 'Nebraska'");
            rs.next();
            assertThat(rs.getInt(1)).as("both owners' Nebraska rows persisted").isEqualTo(2);
        }

        // 8. The one case that genuinely changes behaviour, recorded deliberately (#219): same
        // owner + same name, with a normalized_name that is NOT the normalizer's output for it, now
        // SUCCEEDS -- only artist_owner_name_key ever caught this shape, and it requires a
        // hand-written inconsistent row; no live write path can produce one (every insertIfAbsent
        // call site derives normalized_name from name via ArtistNameNormalizer, and the JPA path
        // via @PrePersist). A full recompute over all 33,077 production rows found zero whose
        // stored normalized_name differs from a fresh normalize() -- this row shape has never
        // existed in production, so dropping the one constraint that alone caught it loses nothing
        // real.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThatCode(() -> insertArtist(s, OWNER_A, "Nebraska", "still-not-the-normalized-form"))
                    .as("artist_owner_name_key no longer exists to catch a mismatched normalized_name "
                            + "-- accepted, since no real write path can produce one (#219)")
                    .doesNotThrowAnyException();
        }

        // 9. No data loss: DROP CONSTRAINT touches no rows, and none of the successful inserts
        // above were themselves duplicates of an existing (owner, normalized_name) key. Three rows
        // total: OWNER_A/Nebraska (step 1), OWNER_B/Nebraska (step 7), and OWNER_A/Nebraska again
        // with a mismatched normalized_name (step 8, a distinct (owner, normalized_name) key).
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            ResultSet total = s.executeQuery("SELECT count(*) FROM artist");
            total.next();
            assertThat(total.getInt(1)).as("row count unaffected by DROP CONSTRAINT (no rows lost)").isEqualTo(3);
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

    private static void insertArtist(Statement s, String owner, String name, String normalizedName)
            throws SQLException {
        try (PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO artist (owner, name, normalized_name, source, status, created_at) "
                        + "VALUES (?, ?, ?, 'SEED_LIST', 'SEED', now())")) {
            ps.setString(1, owner);
            ps.setString(2, name);
            ps.setString(3, normalizedName);
            ps.executeUpdate();
        }
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
