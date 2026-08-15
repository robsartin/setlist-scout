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
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real {@code /artists/connections} page (issue #112, graph phase 3) against a
 * booted context + Postgres: an aggregate 2-hop traversal from every one of the owner's active
 * artists, filtered to genuinely new (PENDING_REVIEW) discoveries. Mirrors {@link
 * ArtistGraphPageRenderTest}'s approach -- each test uses a distinct owner so saved data can't
 * leak between methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ArtistConnectionsPageRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ArtistEdgeRepository artistEdgeRepository;

    private Long saveArtist(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist).getId();
    }

    private void saveEdge(String owner, Long from, Long to, String type, String source) {
        artistEdgeRepository.save(new ArtistEdge(owner, from, to, type, source, null, null, Instant.now()));
    }

    @Test
    void connectionsPageShowsTwoHopDiscoveriesButNotAlreadyCatalogedArtists() throws Exception {
        String owner = "render-connections@example.com";
        Long radiohead = saveArtist(owner, "Radiohead", ArtistStatus.SEED);
        Long thomYorke = saveArtist(owner, "Thom Yorke", ArtistStatus.PENDING_REVIEW);
        Long atomsForPeace = saveArtist(owner, "Atoms for Peace", ArtistStatus.PENDING_REVIEW);
        // Already decided by the owner -- graph-reachable, but must NOT show up as "new".
        Long alreadyApproved = saveArtist(owner, "Portishead", ArtistStatus.APPROVED);
        Long alreadyRejected = saveArtist(owner, "Massive Attack", ArtistStatus.REJECTED);

        saveEdge(owner, radiohead, thomYorke, "MEMBER_OF", "musicbrainz");
        saveEdge(owner, thomYorke, atomsForPeace, "MEMBER_OF", "discogs");
        saveEdge(owner, radiohead, alreadyApproved, "SIMILAR_TO", "lastfm");
        saveEdge(owner, radiohead, alreadyRejected, "SIMILAR_TO", "lastfm");

        mockMvc.perform(get("/artists/connections")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                // 1-hop and 2-hop PENDING_REVIEW discoveries both appear, with seed/edge provenance.
                .andExpect(content().string(containsString("Thom Yorke")))
                .andExpect(content().string(containsString("Atoms for Peace")))
                .andExpect(content().string(containsString("Radiohead")))
                .andExpect(content().string(containsString("MEMBER_OF")))
                .andExpect(content().string(containsString("musicbrainz")))
                // Already-decided artists never appear, even though they're graph-reachable.
                .andExpect(content().string(not(containsString("Portishead"))))
                .andExpect(content().string(not(containsString("Massive Attack"))))
                // layout chrome: shared nav + stylesheet
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString(">Artists<")));
    }

    @Test
    void connectionsPageIsOwnerScoped() throws Exception {
        String owner = "render-connections-owner@example.com";
        String otherOwner = "render-connections-other@example.com";
        Long ownerSeed = saveArtist(owner, "Owner Seed", ArtistStatus.SEED);

        Long otherSeed = saveArtist(otherOwner, "Other Seed", ArtistStatus.SEED);
        Long otherTarget = saveArtist(otherOwner, "Other Owner's Secret Band", ArtistStatus.PENDING_REVIEW);
        saveEdge(otherOwner, otherSeed, otherTarget, "MEMBER_OF", "musicbrainz");

        mockMvc.perform(get("/artists/connections")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Other Owner's Secret Band"))));
    }

    @Test
    void connectionsPageHandlesNoActiveArtistsGracefully() throws Exception {
        String owner = "render-connections-empty@example.com";

        mockMvc.perform(get("/artists/connections")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/app.css")));
    }
}
