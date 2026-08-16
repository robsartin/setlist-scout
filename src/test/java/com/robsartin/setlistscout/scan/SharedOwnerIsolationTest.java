package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #163 spec section 4. This design widens {@code owner} from "a real user" to "a scan scope", and
 * these are the tests that contain that widening: a synthetic owner's rows must never surface on a
 * real user's pages. The isolation is by construction (different owner), which is exactly why it
 * deserves assertions rather than a comment -- nothing else would catch a regression.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SharedOwnerIsolationTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private ArtistRepository artistRepository;
    @Autowired private ShowRepository showRepository;

    private String sharedKey;

    @BeforeEach
    void setUp() {
        showRepository.deleteAll();
        artistRepository.deleteAll();
        sharedKey = SharedScanOwner.newKey();

        Artist sharedArtist = new Artist("Shared Only Artist", ArtistSource.SEED_LIST,
                ArtistStatus.SEED, null, null);
        sharedArtist.setOwner(sharedKey);
        artistRepository.save(sharedArtist);

        Show sharedShow = new Show("Shared Only Artist", LocalDateTime.now().plusDays(10),
                "Shared Only Venue", "Chicago", BigDecimal.TEN, "ticketmaster", "https://x");
        sharedShow.setOwner(sharedKey);
        showRepository.save(sharedShow);
    }

    private String pageAs(String path, String email) throws Exception {
        return mockMvc.perform(get(path).with(oidcLogin().idToken(t -> t.claim("email", email))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("a shared scan's shows never appear on a participant's own Shows page")
    void sharedShowsDoNotLeakIntoPersonalShows() throws Exception {
        assertThat(pageAs("/", ROB)).doesNotContain("Shared Only Venue");
    }

    @Test
    @DisplayName("a shared scan's artists never appear on a participant's Artists page")
    void sharedArtistsDoNotLeakIntoArtistsPage() throws Exception {
        assertThat(pageAs("/artists", ROB)).doesNotContain("Shared Only Artist");
    }

    @Test
    @DisplayName("a shared scan's artists never appear in the Candidates queue or its nav badge")
    void sharedArtistsDoNotLeakIntoCandidates() throws Exception {
        assertThat(pageAs("/artists/candidates", ROB)).doesNotContain("Shared Only Artist");
    }

    @Test
    @DisplayName("a synthetic owner key can never authenticate -- it is not an allow-listed address")
    void syntheticOwnerCannotAuthenticate() throws Exception {
        // SecurityConfig authorises against setlistscout.auth.allowed-emails; a generated key can
        // never match one. Asserting it here means a future change to that matcher trips this test.
        mockMvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", sharedKey))))
                .andExpect(status().is4xxClientError());
    }
}
