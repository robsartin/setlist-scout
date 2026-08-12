package com.robsartin.setlistscout.shared;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUrlEnvironmentPostProcessorTest {

    private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

    @Test
    void setsDatasourcePropertiesWhenConnectionUrlPresent() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_CONNECTION_URL", "postgresql://scout:secret@dpg-abc-a/scoutdata");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-abc-a/scoutdata");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("scout");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("secret");
    }

    @Test
    void takesPrecedenceOverExistingDatasourceProperties() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/setlistscout");
        env.setProperty("spring.datasource.username", "setlistscout");
        env.setProperty("spring.datasource.password", "setlistscout");
        env.setProperty("DATABASE_CONNECTION_URL", "postgresql://scout:secret@dpg-abc-a:5432/scoutdata");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-abc-a:5432/scoutdata");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("scout");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("secret");
    }

    @Test
    void noOpWhenConnectionUrlAbsent() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/setlistscout");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:5432/setlistscout");
        assertThat(env.getPropertySources().contains("renderDatastoreUrl")).isFalse();
    }

    @Test
    void noOpWhenConnectionUrlUnparseable() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_CONNECTION_URL", "not-a-url");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getPropertySources().contains("renderDatastoreUrl")).isFalse();
    }
}
