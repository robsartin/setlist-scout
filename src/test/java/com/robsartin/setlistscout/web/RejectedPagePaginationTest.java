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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #251: the Rejected page used to render every rejected row in one response -- 32,201 of them
 * in production, each wrapping its own form and submit button.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false",
        "setlistscout.artists-page-size=5"
})
class RejectedPagePaginationTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rejected-pagination@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    @BeforeEach
    void setUp() {
        artistRepository.deleteAll();
        // 30 rejected rows against a page size of 5. Asserted against the PAGE SIZE below, never a
        // hand-count -- the point of the issue is that the render must not scale with the list.
        for (int i = 0; i < 30; i++) {
            save(String.format(Locale.ROOT, "Rejected Act %02d", i), ArtistStatus.REJECTED);
        }
        save("Tom Petty", ArtistStatus.REJECTED);
        save("Wilco", ArtistStatus.APPROVED);
    }

    @Test
    @DisplayName("renders a bounded number of rows regardless of how many rejected artists exist")
    void rendersABoundedNumberOfRows() throws Exception {
        assertThat(unrejectForms(body(get("/artists/rejected")))).isEqualTo(5);
    }

    @Test
    @DisplayName("paging forward and back never skips or repeats a row (#174's guarantee)")
    void pagingNeverSkipsOrRepeats() throws Exception {
        List<String> seen = new ArrayList<>();
        String body = body(get("/artists/rejected"));
        seen.addAll(namesOn(body));

        String cursor = nextCursor(body);
        while (cursor != null && seen.size() < 40) {
            body = body(get("/artists/rejected").param("after", cursor));
            List<String> page = namesOn(body);
            assertThat(page).as("a page must not repeat a row already seen").doesNotContainAnyElementsOf(seen);
            seen.addAll(page);
            cursor = nextCursor(body);
        }

        assertThat(seen).as("every rejected row is reachable by paging, exactly once")
                .hasSize(31).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an active artist never appears on the rejected page")
    void activeArtistsNeverAppear() throws Exception {
        assertThat(body(get("/artists/rejected"))).doesNotContain("Wilco");
    }

    @Test
    @DisplayName("search narrows the rejected list and the position text reflects it")
    void searchNarrows() throws Exception {
        mockMvc.perform(get("/artists/rejected").param("q", "petty")
                        .with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tom Petty")))
                .andExpect(content().string(not(containsString("Rejected Act 00"))))
                .andExpect(content().string(containsString("rejected artist")));
    }

    @Test
    @DisplayName("paging a search keeps the filter -- the Next link carries q")
    void pagingASearchKeepsTheFilter() throws Exception {
        String body = body(get("/artists/rejected").param("q", "rejected act"));
        assertThat(body).contains("id=\"rejected-next\"").contains("q=rejected");
    }

    @Test
    @DisplayName("a query matching nothing says so rather than rendering a blank page")
    void emptySearchStateIsClear() throws Exception {
        assertThat(body(get("/artists/rejected").param("q", "zzzznope")))
                .contains("No rejected artists match");
    }

    @Test
    @DisplayName("another owner's rejected artists never appear")
    void ownerScoped() throws Exception {
        Artist theirs = new Artist("Their Rejected Act", ArtistSource.SEED_LIST, ArtistStatus.REJECTED, null, null);
        theirs.setOwner("someone-else@example.com");
        artistRepository.save(theirs);

        assertThat(body(get("/artists/rejected"))).doesNotContain("Their Rejected Act");
    }

    @Test
    @DisplayName("unreject works from page 2 and transitions that row, not the first page's")
    void unrejectFromPageTwo() throws Exception {
        String page2 = body(get("/artists/rejected").param("after", "rejected act 04"));
        List<String> names = namesOn(page2);
        assertThat(names).isNotEmpty();
        String target = names.get(0);
        Long id = artistRepository.findByOwnerAndName(OWNER, target).orElseThrow().getId();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/artists/" + id + "/unreject")
                        .with(oidcLogin().idToken(t -> t.claim("email", OWNER)))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findByOwnerAndName(OWNER, target).orElseThrow().getStatus())
                .isEqualTo(ArtistStatus.PENDING_REVIEW);
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        MvcResult result = mockMvc.perform(req.with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString();
    }

    private static int unrejectForms(String body) {
        return body.split("/unreject", -1).length - 1;
    }

    private static List<String> namesOn(String body) {
        List<String> names = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("aria-label=\"Unreject ([^\"]+)\"").matcher(body);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static String nextCursor(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("id=\"rejected-next\"[^>]*href=\"[^\"]*[?&]after=([^\"&]+)").matcher(body);
        return m.find() ? java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8) : null;
    }

    private void save(String name, ArtistStatus status) {
        Artist a = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        a.setOwner(OWNER);
        artistRepository.save(a);
    }
}
