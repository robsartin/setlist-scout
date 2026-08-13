package com.robsartin.setlistscout.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real / (shows) Thymeleaf template against a booted context + Postgres, signed in
 * as a test user. Checks that the migrated shows.html carries the shared layout (nav + app.css)
 * on the full page, but that the htmx swap fragment stays bare (no page chrome). Uses an owner
 * not in seed-bands.txt so seeded data doesn't leak into this test. Runs in CI (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ShowsPageRenderTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "render-layout@example.com";

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-client-secret");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void showsPageRendersWithNavAndStylesheet() throws Exception {
        mvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))   // Shows is current
                .andExpect(content().string(containsString(">Artists<")))               // nav link present
                .andExpect(content().string(containsString("id=\"shows-region\"")))     // htmx region preserved
                .andExpect(content().string(containsString("/settings")));              // settings form preserved
    }

    @Test
    void scanNowHtmxReturnsBareShowsRegionFragment() throws Exception {
        var res = mvc.perform(post("/scan-now").header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // htmx fragment must be JUST the region -- no full-page chrome:
        assertThat(res).contains("shows-region");
        assertThat(res).doesNotContain("<head").doesNotContain("topbar");
    }
}
