package com.robsartin.setlistscout.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the failure behind the #29 prod crash: a schema change must apply cleanly to an
 * ALREADY-POPULATED database, not just a fresh one. Simulates the existing prod DB (schema
 * present + rows, no Flyway history), then runs Flyway with the app's real config and asserts
 * it adopts the DB, applies migrations, and leaves the data intact. Runs in CI (needs Docker).
 *
 * When a future V__ migration is added, this test catches one that would break against real
 * data before it reaches production.
 */
@Testcontainers
class MigrationAgainstPopulatedDbTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrationsApplyCleanlyToAPopulatedDatabase() throws Exception {
        // 1. Simulate an existing (pre-Flyway) database: create the schema and insert a row.
        String baseline = new String(new ClassPathResource("db/migration/V1__baseline.sql")
                .getInputStream().readAllBytes());
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            s.execute(baseline);
            s.execute("INSERT INTO artist (owner, name, source, status, created_at) "
                    + "VALUES ('rob@example.com', 'Dawes', 'SEED_LIST', 'SEED', now())");
        }

        // 2. Adopt Flyway on the populated DB using the app's real settings and migrate.
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();

        // 3. The pre-existing data survived the migration.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT count(*) FROM artist WHERE name = 'Dawes'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
