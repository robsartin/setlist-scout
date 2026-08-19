package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.AppProperties;
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
 * <p>Note on what is NOT tested here: an end-to-end "a synthetic key cannot authenticate" test is
 * not reachable from MockMvc. The allow-list gate lives inside SecurityConfig's
 * allowListedOidcUserService, which runs during the OIDC user-info exchange, and oidcLogin()
 * injects an already-authenticated principal past that step by design -- so such a test would pass
 * whether or not the gate worked, which is worse than no test. The invariant is covered instead
 * from two reachable directions: SharedScanOwnerTest pins that a generated key is never
 * email-shaped and a real address is never a shared key, and the test below pins that no
 * allow-listed address is a shared-scan key.
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
    @Autowired private AppProperties appProperties;

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
                "Shared Only Venue", "Chicago", BigDecimal.TEN, "ticketmaster", "https://x", Show.Kind.MUSIC);
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

    /**
     * NOT an owner-scoping proof, unlike {@link #sharedShowsDoNotLeakIntoPersonalShows} and
     * {@link #sharedArtistsDoNotLeakIntoArtistsPage} above: the fixture artist in {@link #setUp()}
     * is SEED, which could never render in a PENDING_REVIEW-only queue for ANY owner -- this
     * assertion would still pass even if the Candidates query had no owner filter at all. A
     * genuine version would need a PENDING_REVIEW row under {@link #sharedKey}, but that state
     * cannot occur through any real path: {@code SharedScanGuardsTest#sharedOwnerGetsNoExpandJobs}
     * pins that a shared-scan owner is never given an expand job, and expansion is the only thing
     * that ever creates a PENDING_REVIEW artist. What this DOES cover: a shared scan's artist --
     * SEED, its one reachable status -- never shows up where a reviewer would look for something
     * to decide on.
     */
    @Test
    @DisplayName("a shared scan's SEED artist does not appear in the Candidates queue (its only "
            + "reachable status is not one Candidates would show anyway -- see Javadoc)")
    void sharedSeedArtistDoesNotAppearInCandidates() throws Exception {
        assertThat(pageAs("/artists/candidates", ROB)).doesNotContain("Shared Only Artist");
    }

    @Test
    @DisplayName("no allow-listed address is a shared-scan key -- a synthetic owner can never be configured to log in")
    void noAllowListedAddressIsASharedScanKey() {
        assertThat(appProperties.auth().allowedEmails())
                .as("a shared scan is a scan context, not a person; configuring one as a login "
                        + "would hand it a session and a Shows page")
                .noneMatch(SharedScanOwner::isSharedScanKey);
    }
}
