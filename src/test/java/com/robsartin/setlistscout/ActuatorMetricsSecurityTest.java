package com.robsartin.setlistscout;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #162: /actuator/metrics is newly exposed so Hikari connection-pool gauges
 * (hikaricp.connections.active/idle/pending/timeout/usage/acquire) can be read in production.
 * This is operational detail, not a public page -- it must stay behind the same authenticated
 * gate as the rest of the app.
 *
 * <p>{@link SecurityConfig} only {@code permitAll}s the exact literal {@code "/actuator/health"}
 * (not a prefix like {@code "/actuator/**"}), so {@code /actuator/metrics} falls through to
 * {@code anyRequest().authenticated()} automatically. This test pins that: it fails if a future
 * change ever widens the permitAll matcher and silently makes pool metrics public.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ActuatorMetricsSecurityTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("unauthenticated request to /actuator/metrics is rejected, same as any other page")
    void metricsEndpointRejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/oauth2/authorization/google")));
    }

    @Test
    @DisplayName("authenticated request to /actuator/metrics lists the Hikari pool gauges")
    void metricsEndpointRespondsToAuthenticatedRequest() throws Exception {
        String body = mockMvc.perform(get("/actuator/metrics")
                        .with(oidcLogin().idToken(t -> t.claim("email", "render-metrics@example.com"))))
                .andExpect(status().isOk())
                // Actuator's own versioned media type (application/vnd.spring-boot.actuator.v3+json),
                // not plain application/json -- still JSON on the wire, just not literally that type.
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("json")))
                .andReturn().getResponse().getContentAsString();

        // The gauges the #162 report reads to judge pool saturation: non-zero .pending is the
        // real saturation signal; .timeout confirms whether acquisition ever actually failed.
        assertThat(body)
                .contains("hikaricp.connections.active")
                .contains("hikaricp.connections.idle")
                .contains("hikaricp.connections.pending")
                .contains("hikaricp.connections.timeout")
                .contains("hikaricp.connections.usage")
                .contains("hikaricp.connections.acquire");
    }
}
