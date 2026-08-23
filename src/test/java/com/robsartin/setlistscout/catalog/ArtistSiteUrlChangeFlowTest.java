package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.scan.BandSiteScraperService;
import com.robsartin.setlistscout.scan.ScanUnitRunner;
import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.ShowRepository;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SearchSettingsRepository;
import com.robsartin.setlistscout.shared.CurrentUser;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ui.ConcurrentModel;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-path Testcontainers proof of issue #248: drives {@link ArtistController#setSiteUrl} through
 * the REAL {@link ArtistSiteUrlService} and the real {@code scan.ShowRetirementListener}
 * {@code @ApplicationModuleListener} (not a mock), so a green result actually proves {@code
 * ArtistSiteUrlChanged} fired in a committed transaction and the listener's delete ran -- a Modulith
 * {@code Scenario} test would be a false green per ADR-0024. Mirrors {@link
 * RemoveFromSeedFlowTest}'s and {@code scan.HideAndCancelFlowTest}'s style: autowire the real
 * collaborators, construct {@link ArtistController} directly with a stubbed {@link CurrentUser}.
 * <p>
 * The two no-op tests ({@link #settingTheSameUrlAgainRetiresNothing},
 * {@link #settingAUrlWhereNoneExistedRetiresNothing}) each include a POSITIVE CONTROL -- a genuine
 * url change for a different artist, in the same test, confirmed via {@link #awaitQuiescence} --
 * the same technique {@code HideAndCancelFlowTest
 * #rejectedVenuePerformerStaysRejectedAcrossRealVenueRescans} uses: without it, a silently broken or
 * unwired listener would ALSO produce "nothing retired", which is exactly the too-broad-negative
 * failure mode #248's own brief warns about.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false"
})
class ArtistSiteUrlChangeFlowTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "site-url-change-flow@example.com";

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ArtistEdgeRepository artistEdgeRepository;

    @Autowired
    private ArtistSeedService artistSeedService;

    @Autowired
    private ArtistActivationService artistActivationService;

    @Autowired
    private ArtistSiteUrlService artistSiteUrlService;

    @Autowired
    private ArtistConnectionsService artistConnectionsService;

    @Autowired
    private ArtistImportService artistImportService;

    @Autowired
    private ArtistImportRepository artistImportRepository;

    @Autowired
    private ArtistPager artistPager;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SearchSettingsRepository searchSettingsRepository;

    @Autowired
    private ScanUnitRunner scanUnitRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private BandSiteScraperService scraper;

    private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        // Shared container/context across this class's test methods -- clear first, matching
        // RemoveFromSeedFlowTest/HideAndCancelFlowTest's identical rationale.
        showRepository.deleteAll();
        artistRepository.deleteAll();
        currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
    }

    private ArtistController controller() {
        return new ArtistController(artistRepository, artistEdgeRepository, currentUser, artistSeedService,
                artistActivationService, artistSiteUrlService, artistConnectionsService, artistImportService,
                artistImportRepository, artistPager);
    }

    private Long seedArtist(String name) {
        assertThat(artistSeedService.addSeedIfNew(OWNER, name)).isTrue();
        return artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.SEED).stream()
                .filter(a -> a.getName().equals(name))
                .findFirst().orElseThrow().getId();
    }

    private Long saveShow(String owner, Long artistId, String artistName, LocalDateTime when,
                           String venueName, String source, boolean hidden) {
        Show show = new Show(artistName, when, venueName, "Austin", null, source, "https://tickets.example",
                Show.Kind.MUSIC);
        show.setOwner(owner);
        show.setArtistId(artistId);
        if (hidden) {
            show.setHiddenAt(Instant.now());
        }
        return showRepository.save(show).getId();
    }

    @Test
    @DisplayName("issue #248: changing the site url retires ONLY that artist's VISIBLE band-site "
            + "shows from the OLD host -- ticketmaster/venue: rows, another artist's identically-"
            + "hosted band-site row, and a HIDDEN row under the same stale source all survive")
    void changingTheSiteUrlRetiresOnlyThatArtistsVisibleBandSiteShowsFromTheOldHost() {
        Long asoId = seedArtist("Austin Symphony Orchestra");
        Long otherArtistId = seedArtist("Other Artist");
        // Establish the ORIGINAL url first (old value null -> no event -- just a baseline, same as
        // MusicBrainz's first-ever resolve would do).
        controller().setSiteUrl(asoId, "https://www.austinsymphony.org", null, new ConcurrentModel());

        LocalDateTime when = LocalDateTime.now().plusDays(40).truncatedTo(ChronoUnit.SECONDS);
        Long staleShowId = saveShow(OWNER, asoId, "Austin Symphony Orchestra", when,
                "1113 Red River St", "band-site:www.austinsymphony.org", false);
        Long ticketmasterShowId = saveShow(OWNER, asoId, "Austin Symphony Orchestra", when.plusHours(1),
                "Long Center", "ticketmaster", false);
        Long venueShowId = saveShow(OWNER, asoId, "Austin Symphony Orchestra", when.plusHours(2),
                "Long Center", "venue:longcenter.org", false);
        Long otherArtistShowId = saveShow(OWNER, otherArtistId, "Other Artist", when.plusHours(3),
                "Some Venue", "band-site:www.austinsymphony.org", false);
        Long hiddenStaleShowId = saveShow(OWNER, asoId, "Austin Symphony Orchestra", when.plusHours(4),
                "Austin Symphony Orchestra", "band-site:www.austinsymphony.org", true);

        String view = controller().setSiteUrl(asoId, "https://austinsymphony.org/season-announcement/",
                null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists");
        awaitUntil(() -> showRepository.findById(staleShowId), Optional::isEmpty);
        // Not just the one polled effect -- every listener this action triggered has finished
        // (AbstractPostgresIntegrationTest#awaitQuiescence's own rationale).
        Long incomplete = awaitQuiescence(jdbcTemplate);
        assertThat(incomplete).as("every listener triggered by this change has finished").isZero();

        assertThat(showRepository.findById(staleShowId))
                .as("the stale-source row is gone").isEmpty();
        assertThat(showRepository.findById(ticketmasterShowId))
                .as("ticketmaster has nothing to do with the artist's own site -- survives").isPresent();
        assertThat(showRepository.findById(venueShowId))
                .as("venue: has nothing to do with the artist's own site -- survives").isPresent();
        assertThat(showRepository.findById(otherArtistShowId))
                .as("a DIFFERENT artist's identically-hosted band-site row survives -- artist-scoped")
                .isPresent();
        assertThat(showRepository.findById(hiddenStaleShowId))
                .as("a HIDDEN row under the exact stale source survives -- deleting it would remove "
                        + "the natural-key placeholder that keeps a later rescan from reinserting it "
                        + "unhidden (see HiddenShowSurvivesRescanTest)")
                .isPresent();
    }

    @Test
    @DisplayName("issue #248: setting the SAME url again retires nothing -- a genuine change for a "
            + "different (control) artist in the same test proves the pathway is genuinely alive, "
            + "not just idle, so this negative isn't a false green from a broken listener")
    void settingTheSameUrlAgainRetiresNothing() {
        Long targetId = seedArtist("Same URL Target");
        controller().setSiteUrl(targetId, "https://www.example-target.org", null, new ConcurrentModel());
        Long targetShowId = saveShow(OWNER, targetId, "Same URL Target",
                LocalDateTime.now().plusDays(41).truncatedTo(ChronoUnit.SECONDS), "Some Venue",
                "band-site:www.example-target.org", false);

        Long controlId = seedArtist("Same URL Control");
        controller().setSiteUrl(controlId, "https://www.control-old.org", null, new ConcurrentModel());
        Long controlShowId = saveShow(OWNER, controlId, "Same URL Control",
                LocalDateTime.now().plusDays(42).truncatedTo(ChronoUnit.SECONDS), "Control Venue",
                "band-site:www.control-old.org", false);
        controller().setSiteUrl(controlId, "https://control-new.org", null, new ConcurrentModel());
        awaitUntil(() -> showRepository.findById(controlShowId), Optional::isEmpty);

        // The no-op under test: re-setting the SAME url for the target artist.
        controller().setSiteUrl(targetId, "https://www.example-target.org", null, new ConcurrentModel());
        Long incomplete = awaitQuiescence(jdbcTemplate);
        assertThat(incomplete).as("every listener triggered by this test has finished").isZero();

        assertThat(showRepository.findById(targetShowId))
                .as("re-setting the same url published nothing -- the target's show survives")
                .isPresent();
    }

    @Test
    @DisplayName("issue #248: setting a url where none existed (old value null) retires nothing -- "
            + "same positive-control technique as the same-url case")
    void settingAUrlWhereNoneExistedRetiresNothing() {
        Long targetId = seedArtist("First Set Target");
        // Deliberately no prior setSiteUrl call for this artist -- officialSiteUrl starts null.
        Long targetShowId = saveShow(OWNER, targetId, "First Set Target",
                LocalDateTime.now().plusDays(43).truncatedTo(ChronoUnit.SECONDS), "Some Venue",
                "band-site:first-set-target.org", false);

        Long controlId = seedArtist("First Set Control");
        controller().setSiteUrl(controlId, "https://www.control2-old.org", null, new ConcurrentModel());
        Long controlShowId = saveShow(OWNER, controlId, "First Set Control",
                LocalDateTime.now().plusDays(44).truncatedTo(ChronoUnit.SECONDS), "Control Venue",
                "band-site:www.control2-old.org", false);
        controller().setSiteUrl(controlId, "https://control2-new.org", null, new ConcurrentModel());
        awaitUntil(() -> showRepository.findById(controlShowId), Optional::isEmpty);

        // The no-op under test: FIRST-time set for the target artist (old value was null).
        controller().setSiteUrl(targetId, "https://first-set-target.org", null, new ConcurrentModel());
        Long incomplete = awaitQuiescence(jdbcTemplate);
        assertThat(incomplete).as("every listener triggered by this test has finished").isZero();

        assertThat(showRepository.findById(targetShowId))
                .as("a first-time url set (old value null) published nothing -- the show survives")
                .isPresent();
    }

    @Test
    @DisplayName("issue #248: after retirement, a subsequent real scan against the NEW url "
            + "repopulates normally -- retirement leaves no residue that blocks a fresh insert")
    void aSubsequentScanRepopulatesFromTheNewUrl() {
        Long artistId = seedArtist("Rescan Repopulate Artist");
        String oldUrl = "https://www.rescan-repop.example";
        String newUrl = "https://rescan-repop.example/season/";
        controller().setSiteUrl(artistId, oldUrl, null, new ConcurrentModel());
        // city left null -- BandSiteShowSource#search skips distance filtering entirely when the
        // owner's settings carry no city, so this test needs no real geocoding call.
        searchSettingsRepository.save(new SearchSettings(OWNER, null, null, 50, 6));

        Long staleShowId = saveShow(OWNER, artistId, "Rescan Repopulate Artist",
                LocalDateTime.now().plusDays(45).truncatedTo(ChronoUnit.SECONDS), "Old Venue",
                "band-site:www.rescan-repop.example", false);

        controller().setSiteUrl(artistId, newUrl, null, new ConcurrentModel());
        awaitUntil(() -> showRepository.findById(staleShowId), Optional::isEmpty);

        LocalDateTime freshDate = LocalDateTime.now().plusDays(46).truncatedTo(ChronoUnit.SECONDS);
        when(scraper.scrapeShows(eq("Rescan Repopulate Artist"), eq(newUrl), any(), any())).thenReturn(List.of(
                new Show("Rescan Repopulate Artist", freshDate, "New Venue", "Austin", null,
                        "band-site:rescan-repop.example", newUrl, Show.Kind.MUSIC)));

        int saved = scanUnitRunner.run(OWNER, artistId, "band-site");

        assertThat(saved).as("the fresh scan against the corrected url inserted a new show").isEqualTo(1);
        List<Show> freshRows = showRepository.findByOwnerOrderByEventDateTimeAsc(OWNER).stream()
                .filter(s -> s.getArtistName().equals("Rescan Repopulate Artist")
                        && s.getVenueName().equals("New Venue"))
                .toList();
        assertThat(freshRows).hasSize(1);
        assertThat(freshRows.get(0).getSource())
                .as("the new row carries the CORRECTED source, not the retired one")
                .isEqualTo("band-site:rescan-repop.example");
    }
}
