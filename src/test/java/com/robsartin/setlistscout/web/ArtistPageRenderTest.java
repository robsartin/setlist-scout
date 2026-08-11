package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real /artists Thymeleaf template against a booted context + Postgres, so a
 * template error (bad fragment, expression, or grouping) fails the build. Security filters
 * are disabled so the page renders without a login. Runs in CI (needs a Docker daemon).
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
class ArtistPageRenderTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-client-secret");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    @Test
    void artistsPageRendersBothPendingGroups() throws Exception {
        artistRepository.save(new Artist("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION,
                ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers",
                "tribute/cover act for Tom Petty and the Heartbreakers"));
        // A name NOT in seed-bands.txt -- DataInitializer imports those on startup (unique name constraint).
        artistRepository.save(new Artist("The Milk Carton Kids", ArtistSource.SIMILAR_EXPANSION,
                ArtistStatus.PENDING_REVIEW, "Dawes", "similar to Dawes (single-source match)"));

        mockMvc.perform(get("/artists"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cover / tribute acts")))
                .andExpect(content().string(containsString("Damn the Torpedoes")))
                .andExpect(content().string(containsString("Members")))
                .andExpect(content().string(containsString("Approve all remaining (2)")));
    }

    @Test
    void showsPageRendersZipLocationForm() throws Exception {
        // DataInitializer seeds the settings row with the default ZIP (78701) at startup.
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Near ZIP")))
                .andExpect(content().string(containsString("78701")));
    }
}

