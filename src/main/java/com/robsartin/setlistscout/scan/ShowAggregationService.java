package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.scan.source.ScanQuery;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SearchSettingsRepository;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runs show search for every SEED/APPROVED artist against each injected {@link ShowSource}
 * (Ticketmaster, Bandsintown, band-site -- ordered by {@code @Order}, first-writer-wins on
 * de-dup), using the live SearchSettings (city/state/radius/window), and persists new shows
 * only.
 *
 * NOTE: Austin-local sources (venue calendars, Austin Chronicle, Do512, KUTX) aren't
 * wired up yet -- those don't have clean JSON APIs like Ticketmaster/Bandsintown, so
 * they'll likely need lightweight scraping. Left as a follow-up; see README.
 */
@Service
public class ShowAggregationService {

    private static final Logger log = LoggerFactory.getLogger(ShowAggregationService.class);

    private final ArtistRepository artistRepository;
    private final SearchSettingsRepository settingsRepository;
    private final List<ShowSource> showSources;
    // Same-package helper: owns the one copy of resolveSiteUrl/persistNew, shared with ScanUnitRunner
    // (Phase B PR4a's per-(artist,source) runner) so neither duplicates the other's logic.
    private final ScanUnitRunner scanUnitRunner;

    public ShowAggregationService(ArtistRepository artistRepository,
                                   ShowRepository showRepository,
                                   SearchSettingsRepository settingsRepository,
                                   MusicBrainzService musicBrainz,
                                   List<ShowSource> showSources) {
        this.artistRepository = artistRepository;
        this.settingsRepository = settingsRepository;
        this.showSources = showSources;
        this.scanUnitRunner = new ScanUnitRunner(showSources, artistRepository, showRepository,
                settingsRepository, musicBrainz);
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

            ScanQuery query = scanUnitRunner.buildQuery(artist, settings, start, end);

            List<Show> shows = new java.util.ArrayList<>();
            for (ShowSource source : showSources) {
                List<Show> sourceShows = source.search(query);
                found += sourceShows.size();
                shows.addAll(sourceShows);
                log.atDebug()
                        .addKeyValue("artist", artist.getName())
                        .addKeyValue("source", source.id())
                        .addKeyValue("count", sourceShows.size())
                        .log("artist source scanned");
            }
            saved += scanUnitRunner.persistNew(owner, shows);
        }

        log.atInfo()
                .addKeyValue("artistsSearched", searched)
                .addKeyValue("showsFound", found)
                .addKeyValue("showsSaved", saved)
                .addKeyValue("durationMs", (System.nanoTime() - startNanos) / 1_000_000)
                .log("scan finished");
    }
}
