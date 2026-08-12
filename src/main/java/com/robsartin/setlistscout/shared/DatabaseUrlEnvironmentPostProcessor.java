package com.robsartin.setlistscout.shared;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges Render's linked internal Datastore URL to Spring's datasource. When
 * {@code DATABASE_CONNECTION_URL} is present (Render keeps it current across credential
 * rotations), parse it and publish {@code spring.datasource.{url,username,password}} at highest
 * precedence so both JPA and Flyway use the rotated credentials with no manual env-var edits.
 *
 * <p>Absent or unparseable → no-op; the {@code application.yml} split-var configuration stands
 * (local dev, tests). Runs as an {@link EnvironmentPostProcessor} — before the application
 * context, Flyway, and JPA — registered in {@code META-INF/spring.factories}.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String ENV_VAR = "DATABASE_CONNECTION_URL";
    static final String SOURCE_NAME = "renderDatastoreUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(ENV_VAR);
        RenderDatabaseUrl.parse(raw).ifPresent(parsed -> {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("spring.datasource.url", parsed.jdbcUrl());
            props.put("spring.datasource.username", parsed.username());
            props.put("spring.datasource.password", parsed.password());
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
        });
    }
}
