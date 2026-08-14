package com.robsartin.setlistscout;

import com.robsartin.setlistscout.scan.source.BandSiteShowSource;
import com.robsartin.setlistscout.scan.source.BandsintownShowSource;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.scan.source.TicketmasterShowSource;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the entire Spring context against a throwaway Postgres -- exactly as production
 * does -- so context-wiring, autoconfig, and security-gate regressions fail the build
 * instead of only surfacing on deploy. This is the guard the all-unit-test suite lacked;
 * it would have caught the bean-wiring bug (#14) that shipped and crash-looped on Render.
 *
 * Requires a Docker daemon (CI runners have one; locally, start Docker Desktop/Colima).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApplicationContextSmokeTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private List<ShowSource> showSources;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    @DisplayName("the full application context starts")
    void contextLoads() {
        // Fails if any bean (e.g. the RestClient services) can't be wired -- the #14 regression guard.
    }

    @Test
    @DisplayName("ShowSource beans are injected in the expected @Order: Ticketmaster, Bandsintown, band-site")
    void showSourcesAreInjectedInExpectedOrder() {
        assertThat(showSources).hasSize(3);
        assertThat(showSources.get(0)).isInstanceOf(TicketmasterShowSource.class);
        assertThat(showSources.get(1)).isInstanceOf(BandsintownShowSource.class);
        assertThat(showSources.get(2)).isInstanceOf(BandSiteShowSource.class);
    }

    @Test
    @DisplayName("/actuator/health responds 200 UP without authentication")
    void healthEndpointIsUpAndUnauthenticated() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("an unauthenticated request to / is redirected to Google login")
    void rootRedirectsToGoogleLogin() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).contains("/oauth2/authorization/google"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
