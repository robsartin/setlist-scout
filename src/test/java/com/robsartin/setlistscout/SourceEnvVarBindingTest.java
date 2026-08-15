package com.robsartin.setlistscout;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #139: pins the exact Render environment-variable spelling for
 * {@code setlistscout.sources.<id>} -- README.md tells a human to set this literally to kill a
 * misbehaving source (Bandsintown was the motivating incident) without touching any other config,
 * so getting the spelling wrong would ship broken instructions. Easy to guess wrong: Spring Boot's
 * own reference docs state the canonical env-var conversion is "replace dots with underscores,
 * REMOVE any dashes, uppercase" (their own worked example is {@code spring.main.log-startup-info}
 * -> {@code SPRING_MAIN_LOGSTARTUPINFO}, NOT {@code ..._LOG_STARTUP_INFO}) -- so for the three
 * hyphenated ids here ({@code band-site}, {@code similar-llm}, {@code tribute-llm}) the dash
 * disappears with no separating underscore. Rather than trust that recollection, this test drives
 * a real {@link SystemEnvironmentPropertySource} (the exact class Spring wraps
 * {@code System.getenv()} in) through {@link ConfigurationPropertySources#attach}, the same
 * relaxed-binding path {@code @ConditionalOnProperty} itself resolves against in the running app.
 * <p>
 * (An underscore-preserving spelling like {@code BAND_SITE} also happens to resolve today, via a
 * second "legacy" candidate Spring Boot's {@code SystemEnvironmentPropertyMapper} generates
 * alongside the canonical one -- confirmed empirically while writing this test. README.md
 * documents only the canonical, docs-guaranteed form below, since the legacy fallback is an
 * unadvertised implementation detail this codebase shouldn't rely on.)
 */
class SourceEnvVarBindingTest {

    /** True if setting {@code envVarName=false} in the OS environment disables {@code propertyKey}. */
    private static boolean envVarDisables(String envVarName, String propertyKey) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of(envVarName, "false")));
        ConfigurationPropertySources.attach(environment);
        return "false".equals(environment.getProperty(propertyKey));
    }

    @Test
    void bandsintownEnvVarIsTheUrgentMotivatingCase() {
        // No hyphen in "bandsintown" -- the dot-to-underscore/uppercase part of the rule alone.
        assertThat(envVarDisables("SETLISTSCOUT_SOURCES_BANDSINTOWN", "setlistscout.sources.bandsintown"))
                .as("SETLISTSCOUT_SOURCES_BANDSINTOWN must disable Bandsintown in production")
                .isTrue();
    }

    @Test
    void unhyphenatedSourceIdsBindTheObviousUppercaseForm() {
        assertThat(envVarDisables("SETLISTSCOUT_SOURCES_TICKETMASTER", "setlistscout.sources.ticketmaster")).isTrue();
        assertThat(envVarDisables("SETLISTSCOUT_SOURCES_MUSICBRAINZ", "setlistscout.sources.musicbrainz")).isTrue();
        assertThat(envVarDisables("SETLISTSCOUT_SOURCES_DISCOGS", "setlistscout.sources.discogs")).isTrue();
        assertThat(envVarDisables("SETLISTSCOUT_SOURCES_LASTFM", "setlistscout.sources.lastfm")).isTrue();
    }

    @Test
    void hyphenatedSourceIdsDropTheHyphenEntirely() {
        // band-site / similar-llm / tribute-llm: the canonical env var has NO separator where the
        // hyphen was -- BANDSITE, not BAND_SITE.
        assertThat(envVarDisables("SETLISTSCOUT_SOURCES_BANDSITE", "setlistscout.sources.band-site")).isTrue();
        assertThat(envVarDisables("SETLISTSCOUT_SOURCES_SIMILARLLM", "setlistscout.sources.similar-llm")).isTrue();
        assertThat(envVarDisables("SETLISTSCOUT_SOURCES_TRIBUTELLM", "setlistscout.sources.tribute-llm")).isTrue();
    }
}
