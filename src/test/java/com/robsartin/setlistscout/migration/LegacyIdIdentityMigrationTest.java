package com.robsartin.setlistscout.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the issue #53 prod 500. In the adopted legacy DB, {@code search_settings.id} was a
 * plain bigint with no IDENTITY, so {@code @GeneratedValue(IDENTITY)} inserted a null id and every
 * new user's first-visit settings row was rejected. This builds that legacy shape -- a
 * {@code search_settings} whose {@code id} is not an identity column, with a row already in it --
 * then asserts the migrations make a keyless insert succeed (the id auto-generates). Runs in CI
 * (needs Docker). Without V3 this fails: the insert hits a NOT NULL violation on {@code id}.
 */
@Testcontainers
class LegacyIdIdentityMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // search_settings as V1 declares it, EXCEPT `id` is a plain bigint (no GENERATED AS IDENTITY) --
    // the drift that was in production. artist/show_event are left for V1 to create normally.
    private static final String LEGACY_SEARCH_SETTINGS = """
            CREATE TABLE search_settings (
                id bigint NOT NULL,
                owner varchar(255) NOT NULL,
                postal_code varchar(255),
                city varchar(255),
                state varchar(255),
                latitude double precision,
                longitude double precision,
                radius_miles integer NOT NULL,
                months_ahead integer NOT NULL,
                CONSTRAINT search_settings_pkey PRIMARY KEY (id),
                CONSTRAINT search_settings_owner_key UNIQUE (owner)
            );
            """;

    @Test
    void keylessInsertSucceedsAfterIdentityIsReconciled() throws Exception {
        // 1. Legacy search_settings (non-identity id) with a pre-existing row, like prod.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            s.execute(LEGACY_SEARCH_SETTINGS);
            s.execute("INSERT INTO search_settings (id, owner, radius_miles, months_ahead) "
                    + "VALUES (1, 'rob@example.com', 50, 6)");
        }

        // 2. Adopt Flyway with the app's real settings and migrate (V3 fixes the id identity).
        MigrateResult result = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(result.success).isTrue();

        // 3. A new user's first-visit insert supplies no id -- it must auto-generate now.
        try (Connection c = postgres.createConnection(""); Statement s = c.createStatement()) {
            ResultSet identity = s.executeQuery(
                    "SELECT is_identity FROM information_schema.columns "
                    + "WHERE table_name = 'search_settings' AND column_name = 'id'");
            assertThat(identity.next()).isTrue();
            assertThat(identity.getString(1)).as("id is now an identity column").isEqualTo("YES");

            s.execute("INSERT INTO search_settings (owner, radius_miles, months_ahead) "
                    + "VALUES ('new@example.com', 50, 6)"); // no id -> would fail before V3

            ResultSet row = s.executeQuery("SELECT id FROM search_settings WHERE owner = 'new@example.com'");
            assertThat(row.next()).isTrue();
            assertThat(row.getLong("id")).as("auto-generated id, past the existing max").isGreaterThan(1L);
        }
    }
}
