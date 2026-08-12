package com.robsartin.setlistscout.shared;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RenderDatabaseUrlTest {

    @Test
    void parsesUrlWithExplicitPort() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgresql://scout:secret@dpg-abc-a.oregon-postgres.render.com:5432/scoutdata");

        assertThat(result).isPresent();
        assertThat(result.get().jdbcUrl())
                .isEqualTo("jdbc:postgresql://dpg-abc-a.oregon-postgres.render.com:5432/scoutdata");
        assertThat(result.get().username()).isEqualTo("scout");
        assertThat(result.get().password()).isEqualTo("secret");
    }

    @Test
    void parsesInternalUrlWithoutPort() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgresql://scout:secret@dpg-abc-a/scoutdata");

        assertThat(result).isPresent();
        assertThat(result.get().jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc-a/scoutdata");
        assertThat(result.get().username()).isEqualTo("scout");
        assertThat(result.get().password()).isEqualTo("secret");
    }

    @Test
    void acceptsPostgresScheme() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgres://user:pw@host:5432/db");

        assertThat(result).isPresent();
        assertThat(result.get().jdbcUrl()).isEqualTo("jdbc:postgresql://host:5432/db");
    }

    @Test
    void urlDecodesUsernameAndPassword() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgresql://scout:p%40ss%2Fword@host/db");

        assertThat(result).isPresent();
        assertThat(result.get().password()).isEqualTo("p@ss/word");
    }

    @Test
    void preservesQueryString() {
        Optional<RenderDatabaseUrl.Parsed> result =
                RenderDatabaseUrl.parse("postgresql://u:pw@host:5432/db?sslmode=require");

        assertThat(result).isPresent();
        assertThat(result.get().jdbcUrl()).isEqualTo("jdbc:postgresql://host:5432/db?sslmode=require");
    }

    @Test
    void returnsEmptyForBlankNullAndInvalidInput() {
        assertThat(RenderDatabaseUrl.parse(null)).isEmpty();
        assertThat(RenderDatabaseUrl.parse("")).isEmpty();
        assertThat(RenderDatabaseUrl.parse("   ")).isEmpty();
        assertThat(RenderDatabaseUrl.parse("not-a-url")).isEmpty();
        assertThat(RenderDatabaseUrl.parse("mysql://u:pw@host/db")).isEmpty();
        assertThat(RenderDatabaseUrl.parse("postgresql://host/db")).isEmpty(); // no user:pass@
    }
}
