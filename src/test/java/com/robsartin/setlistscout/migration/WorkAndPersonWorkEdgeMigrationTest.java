package com.robsartin.setlistscout.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #286 (Films 2/3): V39 adds the {@code work} and {@code person_work_edge} tables and the
 * {@code artist.wikidata_*} columns.
 *
 * <p>Proves the two identity decisions the sub-project rests on, each of which is invisible in
 * Java and enforced only here: a work is identified by its QID rather than by title+year, and a
 * person-work credit is unique per source so two sources corroborating the same role are two rows
 * rather than one silently dropped. Also proves the existing catalog is untouched. Needs Docker.
 */
@Testcontainers
class WorkAndPersonWorkEdgeMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "migration-286@example.com";

    private Flyway flywayTo(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration", "classpath:db/migration/support")
                .target(target)
                .load();
    }

    @Test
    @DisplayName("V39 adds the work graph without disturbing the catalog it hangs off")
    void addsTheWorkGraphAndLeavesTheCatalogAlone() throws Exception {
        assertThat(flywayTo("38").migrate().success).isTrue();

        long artistId;
        long preExistingArtists;
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            assertThat(tableExists(s, "work")).as("work must not exist before V39").isFalse();
            assertThat(columnExists(s, "artist", "wikidata_qid"))
                    .as("artist.wikidata_qid must not exist before V39").isFalse();

            artistId = insertArtist(s, "Martin Scorsese");
            insertArtist(s, "Willie Nelson");
            preExistingArtists = count(s, "SELECT count(*) FROM artist WHERE owner = '" + OWNER + "'");
        }

        assertThat(flywayTo("39").migrate().success).isTrue();

        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            // The catalog survives, and every existing artist is simply unresolved.
            assertThat(count(s, "SELECT count(*) FROM artist WHERE owner = '" + OWNER + "'"))
                    .isEqualTo(preExistingArtists);
            assertThat(count(s, "SELECT count(*) FROM artist WHERE owner = '" + OWNER
                    + "' AND wikidata_qid IS NULL")).isEqualTo(preExistingArtists);

            // A work's identity is its QID: same title, different film, two rows.
            long dune1984 = insertWork(s, "Q191518", "Dune", "dune", 1984);
            long dune2021 = insertWork(s, "Q56239097", "Dune", "dune", 2021);
            assertThat(dune1984).isNotEqualTo(dune2021);

            // ...and the same QID twice is one work, whatever the title says.
            assertThatThrownBy(() -> insertWork(s, "Q191518", "Dune (1984)", "dune 1984", 1984))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("work_unique");

            // A year is optional: Wikidata states none for some films, and a film with no year
            // must still be storable rather than dropped.
            insertWork(s, "Q999999", "Untitled Short", "untitled short", null);

            // Two sources asserting the same role is corroboration, not duplication -- the same
            // rule artist_edge_unique encodes for V10.
            insertEdge(s, artistId, dune1984, "DIRECTED", "wikidata");
            insertEdge(s, artistId, dune1984, "DIRECTED", "imdb");
            assertThat(count(s, "SELECT count(*) FROM person_work_edge WHERE owner = '" + OWNER
                    + "' AND role = 'DIRECTED'")).isEqualTo(2);

            assertThatThrownBy(() -> insertEdge(s, artistId, dune1984, "DIRECTED", "wikidata"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("person_work_edge_unique");

            // One person, one film, two roles: two credits.
            insertEdge(s, artistId, dune1984, "ACTED_IN", "wikidata");
            assertThat(count(s, "SELECT count(*) FROM person_work_edge WHERE owner = '" + OWNER
                    + "' AND work_id = " + dune1984)).isEqualTo(3);

            // An edge cannot dangle off either end.
            assertThatThrownBy(() -> insertEdge(s, 999999L, dune1984, "DIRECTED", "wikidata"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertEdge(s, artistId, 999999L, "DIRECTED", "wikidata"))
                    .isInstanceOf(SQLException.class);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static boolean tableExists(Statement s, String table) throws SQLException {
        return count(s, "SELECT count(*) FROM information_schema.tables WHERE table_name = '"
                + table + "'") > 0;
    }

    private static boolean columnExists(Statement s, String table, String column) throws SQLException {
        return count(s, "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                + table + "' AND column_name = '" + column + "'") > 0;
    }

    private static long count(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long insertArtist(Statement s, String name) throws SQLException {
        s.executeUpdate("INSERT INTO artist (owner, name, normalized_name, source, status, created_at) "
                + "VALUES ('" + OWNER + "', '" + name + "', '" + name.toLowerCase()
                + "', 'SEED_LIST', 'SEED', now())", Statement.RETURN_GENERATED_KEYS);
        try (ResultSet keys = s.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static long insertWork(Statement s, String qid, String title, String normalized,
                                    Integer year) throws SQLException {
        s.executeUpdate("INSERT INTO work (owner, wikidata_qid, title, normalized_title, release_year, created_at) "
                + "VALUES ('" + OWNER + "', '" + qid + "', '" + title + "', '" + normalized + "', "
                + (year == null ? "NULL" : year) + ", now())", Statement.RETURN_GENERATED_KEYS);
        try (ResultSet keys = s.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static void insertEdge(Statement s, long artistId, long workId, String role, String source)
            throws SQLException {
        s.executeUpdate("INSERT INTO person_work_edge (owner, artist_id, work_id, role, source, created_at) "
                + "VALUES ('" + OWNER + "', " + artistId + ", " + workId + ", '" + role + "', '"
                + source + "', now())");
    }
}
