package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SearchSettingsRepository;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runs show search for every SEED/APPROVED artist against Ticketmaster + Bandsintown,
 * using the live SearchSettings (city/state/radius/window), and persists new shows only.
 *
 * NOTE: Austin-local sources (venue calendars, Austin Chronicle, Do512, KUTX) aren't
 * wired up yet -- those don't have clean JSON APIs like Ticketmaster/Bandsintown, so
 * they'll likely need lightweight scraping. Left as a follow-up; see README.
 */
@Service
public class ShowAggregationService {

    private static final Logger log = LoggerFactory.getLogger(ShowAggregationService.class);

    private final ArtistRepository artistRepository;
    private final ShowRepository showRepository;
    private final SearchSettingsRepository settingsRepository;
    private final TicketmasterService ticketmaster;
    private final BandsintownService bandsintown;
    private final MusicBrainzService musicBrainz;
    private final BandSiteScraperService bandSiteScraper;

    public ShowAggregationService(ArtistRepository artistRepository,
                                   ShowRepository showRepository,
                                   SearchSettingsRepository settingsRepository,
                                   TicketmasterService ticketmaster,
                                   BandsintownService bandsintown,
                                   MusicBrainzService musicBrainz,
                                   BandSiteScraperService bandSiteScraper) {
        this.artistRepository = artistRepository;
        this.showRepository = showRepository;
        this.settingsRepository = settingsRepository;
        this.ticketmaster = ticketmaster;
        this.bandsintown = bandsintown;
        this.musicBrainz = musicBrainz;
        this.bandSiteScraper = bandSiteScraper;
    }

    public void scanForShows(String owner) {
        SearchSettings settings = settingsRepository.findByOwner(owner)
                .orElseThrow(() -> new IllegalStateException(
                        "SearchSettings row missing for " + owner + " -- provisioned on first login"));

        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());

        List<Artist> activeArtists = artistRepository.findByOwnerAndStatusIn(
                owner, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));

        log.atInfo().addKeyValue("activeArtists", activeArtists.size()).log("scan started");
        long startNanos = System.nanoTime();
        int searched = 0;
        int found = 0;
        int saved = 0;
        for (Artist artist : activeArtists) {
            // Defense in depth (issue #49): a blank name searches Ticketmaster with keyword="",
            // which returns every local event. Never let a bad row trigger that, whatever its source.
            if (artist.getName() == null || artist.getName().isBlank()) continue;
            searched++;

            List<Show> tmShows = ticketmaster.searchShows(
                    artist.getName(), settings.getPostalCode(),
                    settings.getRadiusMiles(), start, end);
            // Bandsintown has no server-side radius filter, so it filters by distance from
            // the geocoded ZIP lat/long (falls back to all-in-window if the geocode is missing).
            List<Show> bitShows = bandsintown.searchShows(
                    artist.getName(), settings.getLatitude(), settings.getLongitude(),
                    settings.getRadiusMiles(), start, end);
            List<Show> siteShows = scrapeBandSite(artist, settings, start, end);

            found += tmShows.size() + bitShows.size() + siteShows.size();
            saved += persistNew(owner, tmShows);
            saved += persistNew(owner, bitShows);
            saved += persistNew(owner, siteShows);

            log.atDebug()
                    .addKeyValue("artist", artist.getName())
                    .addKeyValue("ticketmaster", tmShows.size())
                    .addKeyValue("bandsintown", bitShows.size())
                    .addKeyValue("bandSite", siteShows.size())
                    .log("artist scanned");
        }

        log.atInfo()
                .addKeyValue("artistsSearched", searched)
                .addKeyValue("showsFound", found)
                .addKeyValue("showsSaved", saved)
                .addKeyValue("durationMs", (System.nanoTime() - startNanos) / 1_000_000)
                .log("scan finished");
    }

    /**
     * Scrapes the artist's official site for tour dates (#22). Discovers + caches the site URL
     * from MusicBrainz on first use. v1 filters scraped shows by a loose city-name match to the
     * user's location (precise per-show distance filtering is deferred -- see #28).
     */
    private List<Show> scrapeBandSite(Artist artist, SearchSettings settings,
                                      LocalDateTime start, LocalDateTime end) {
        String url = artist.getOfficialSiteUrl();
        if (url == null) {
            url = musicBrainz.findOfficialHomepage(artist.getName()).orElse(null);
            if (url != null) {
                artist.setOfficialSiteUrl(url);
                artistRepository.save(artist);
            }
        }
        if (url == null) return List.of();

        List<Show> shows = bandSiteScraper.scrapeShows(artist.getName(), url, start, end);
        String city = settings.getCity();
        if (city == null) return shows;
        return shows.stream()
                .filter(s -> s.getVenueCity() != null && s.getVenueCity().equalsIgnoreCase(city))
                .toList();
    }

    private int persistNew(String owner, List<Show> shows) {
        int saved = 0;
        for (Show show : shows) {
            if (show.getEventDateTime() == null) continue;
            boolean exists = showRepository.existsByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                    owner, show.getArtistName(), show.getEventDateTime(), show.getVenueName());
            if (!exists) {
                show.setOwner(owner);
                showRepository.save(show);
                saved++;
            }
        }
        return saved;
    }
}
