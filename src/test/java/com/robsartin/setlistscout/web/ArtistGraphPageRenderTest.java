package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistEdge;
import com.robsartin.setlistscout.catalog.ArtistEdgeRepository;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real {@code /artists/{id}/graph} read-only validation page (issue #111) against a
 * booted context + Postgres: edge history (incoming/outgoing) plus the 2-hop reachable set, and
 * owner isolation on the endpoint itself. Mirrors {@link ArtistPageRenderTest}'s approach --
 * each test uses distinct owners so saved data can't leak between methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ArtistGraphPageRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ArtistEdgeRepository artistEdgeRepository;

    private Long saveArtist(String owner, String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist).getId();
    }

    // artistEdgeRepository.insertIfAbsent is a @Modifying native query with no @Transactional of
    // its own (see ArtistEdgeRepository's Javadoc) -- fine from a @Transactional repository test,
    // but this MockMvc test has no ambient transaction, so seed edges via plain save() instead
    // (JpaRepository#save is transactional on its own via Spring Data's repository proxy).
    private void saveEdge(String owner, Long from, Long to, String type, String source, String note) {
        artistEdgeRepository.save(new ArtistEdge(owner, from, to, type, source, note, null, Instant.now()));
    }

    @Test
    void graphPageShowsEdgeHistoryAndTwoHopReachableSet() throws Exception {
        String owner = "render-graph@example.com";
        Long a = saveArtist(owner, "Radiohead");
        Long b = saveArtist(owner, "Thom Yorke");
        Long c = saveArtist(owner, "Atoms for Peace");
        Long d = saveArtist(owner, "Flea");

        saveEdge(owner, a, b, "MEMBER_OF", "musicbrainz", "member/lineup relation of Radiohead");
        saveEdge(owner, a, c, "SIMILAR_TO", "lastfm", "similar to Radiohead");
        saveEdge(owner, b, d, "MEMBER_OF", "discogs", "member/lineup relation of Thom Yorke");

        mockMvc.perform(get("/artists/" + a + "/graph")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Radiohead")))
                .andExpect(content().string(containsString("Thom Yorke")))
                .andExpect(content().string(containsString("Atoms for Peace")))
                .andExpect(content().string(containsString("musicbrainz")))
                .andExpect(content().string(containsString("MEMBER_OF")))
                // the 2-hop reachable set must include Flea, reached only via B at depth 2
                .andExpect(content().string(containsString("Flea")))
                // layout chrome: shared nav + stylesheet
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString(">Artists<")));
    }

    @Test
    void graphPageIsOwnerScoped() throws Exception {
        Long a = saveArtist("owner-a@example.com", "Owner A's Band");

        mockMvc.perform(get("/artists/" + a + "/graph")
                        .with(oidcLogin().idToken(t -> t.claim("email", "owner-b@example.com"))))
                .andExpect(status().isNotFound());
    }
}
