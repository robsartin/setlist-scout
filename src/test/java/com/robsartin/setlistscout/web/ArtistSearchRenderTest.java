package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #250: searching the artists page. The search has to work WITH #174's keyset pagination --
 * filter and cursor evaluated together -- and must not disturb the ids and position text that
 * pagination and the screen-reader announcement depend on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false",
        "setlistscout.artists-page-size=2"
})
class ArtistSearchRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "artist-search-render@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    @BeforeEach
    void setUp() {
        artistRepository.deleteAll();
        save("Tom Petty", ArtistStatus.APPROVED);
        save("Petty Officer", ArtistStatus.SEED);
        save("Pettybone", ArtistStatus.APPROVED);
        save("Foo-Bar", ArtistStatus.SEED);
        save("Wilco", ArtistStatus.APPROVED);
        save("Amanda Shires", ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("a search narrows the active list to matches and drops the rest")
    void narrowsToMatches() throws Exception {
        // Page size is 2 here, and "petty" has three matches sorting
        // petty officer < pettybone < tom petty -- so page 1 is the first two. Asserting all
        // three would be asserting that pagination had stopped working.
        search("petty")
                .andExpect(content().string(containsString(">Petty Officer<")))
                .andExpect(content().string(containsString(">Pettybone<")))
                .andExpect(content().string(not(containsString(">Tom Petty<"))))
                .andExpect(content().string(not(containsString(">Wilco<"))));
    }

    @Test
    @DisplayName("a hyphen-spacing variant finds the artist -- the assertion a naive lower() fails")
    void foldsHyphenSpacing() throws Exception {
        search("foo - bar").andExpect(content().string(containsString(">Foo-Bar<")));
    }

    @Test
    @DisplayName("the position text reflects the filtered count, and the pinned ids survive")
    void positionTextAndIdsSurvive() throws Exception {
        search("petty")
                .andExpect(content().string(containsString("id=\"active-section\"")))
                .andExpect(content().string(containsString("id=\"active-position\"")))
                .andExpect(content().string(containsString("artists matching &quot;petty&quot;")));
    }

    @Test
    @DisplayName("paging a filtered result keeps the filter -- the Next link carries q")
    void pagingKeepsTheFilter() throws Exception {
        search("petty")
                .andExpect(content().string(containsString("id=\"active-next\"")))
                .andExpect(content().string(containsString("q=petty")));
    }

    @Test
    @DisplayName("following the filtered Next link shows the third match, not an unfiltered page")
    void nextPageStaysFiltered() throws Exception {
        // page size 2, matches sort: petty officer, pettybone, tom petty
        mockMvc.perform(get("/artists").param("q", "petty").param("after", "pettybone")
                        .with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Tom Petty<")))
                .andExpect(content().string(not(containsString(">Wilco<"))));
    }

    @Test
    @DisplayName("a query matching nothing active says so, and surfaces the rejected match instead "
            + "of a bare empty list")
    void emptyResultSurfacesRejectedMatches() throws Exception {
        search("shires")
                .andExpect(content().string(containsString("No active artists match")))
                .andExpect(content().string(containsString("Previously rejected or removed")))
                .andExpect(content().string(containsString(">Amanda Shires<")))
                .andExpect(content().string(not(containsString("No artists yet"))));
    }

    @Test
    @DisplayName("rejected matches never appear without a search")
    void rejectedNeverLeaksIntoTheUnfilteredList() throws Exception {
        mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Previously rejected or removed"))))
                .andExpect(content().string(not(containsString(">Amanda Shires<"))));
    }

    @Test
    @DisplayName("search never returns another owner's artists")
    void ownerScoped() throws Exception {
        Artist theirs = new Artist("Petty Thief", ArtistSource.SEED_LIST, ArtistStatus.APPROVED, null, null);
        theirs.setOwner("someone-else@example.com");
        artistRepository.save(theirs);

        search("petty").andExpect(content().string(not(containsString(">Petty Thief<"))));
    }

    @Test
    @DisplayName("the search box has a real label and echoes the current query back")
    void labelledAndEchoed() throws Exception {
        search("petty")
                .andExpect(content().string(containsString("for=\"artist-search\"")))
                .andExpect(content().string(containsString("id=\"artist-search\"")))
                .andExpect(content().string(containsString("value=\"petty\"")));
    }

    @Test
    @DisplayName("a wildcard in the query is literal -- '%' must not widen the result set")
    void wildcardIsLiteral() throws Exception {
        search("pet%ty").andExpect(content().string(not(containsString(">Tom Petty<"))));
    }

    private org.springframework.test.web.servlet.ResultActions search(String q) throws Exception {
        return mockMvc.perform(get("/artists").param("q", q)
                        .with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk());
    }

    private void save(String name, ArtistStatus status) {
        Artist a = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        a.setOwner(OWNER);
        artistRepository.save(a);
    }
}
