package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
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
import java.util.Optional;

/**
 * Runs show search for exactly one (artist, source) pair -- the per-unit counterpart to
 * {@link ShowAggregationService#scanForShows}'s whole-fleet batch (Phase B PR4a). Owns the one
 * shared copy of {@link #resolveSiteUrl} (the one write -- band-site URL cache) and
 * {@link #persistNew}; {@code ShowAggregationService} delegates to this same class rather than
 * duplicating that logic.
 */
@Service
public class ScanUnitRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanUnitRunner.class);

    private final List<ShowSource> showSources;
    private final ArtistRepository artistRepository;
    private final ShowRepository showRepository;
    private final SearchSettingsRepository settingsRepository;
    private final MusicBrainzService musicBrainz;

    public ScanUnitRunner(List<ShowSource> showSources,
                           ArtistRepository artistRepository,
                           ShowRepository showRepository,
                           SearchSettingsRepository settingsRepository,
                           MusicBrainzService musicBrainz) {
        this.showSources = showSources;
        this.artistRepository = artistRepository;
        this.showRepository = showRepository;
        this.settingsRepository = settingsRepository;
        this.musicBrainz = musicBrainz;
    }

    /**
     * Scans one artist against one source and persists new shows. No-ops (returns 0) if the
     * artist or the owner's SearchSettings can't be found, the artist name is blank, or
     * {@code sourceId} doesn't match an injected {@link ShowSource}.
     */
    public int run(String owner, Long artistId, String sourceId) {
        Optional<Artist> artistOpt = artistRepository.findByIdAndOwner(artistId, owner);
        if (artistOpt.isEmpty()) {
            log.atWarn().addKeyValue("owner", owner).addKeyValue("artistId", artistId)
                    .log("scan unit skipped -- artist not found");
            return 0;
        }

        Optional<SearchSettings> settingsOpt = settingsRepository.findByOwner(owner);
        if (settingsOpt.isEmpty()) {
            log.atWarn().addKeyValue("owner", owner).log("scan unit skipped -- SearchSettings missing");
            return 0;
        }

        Artist artist = artistOpt.get();
        // Defense in depth (issue #49): a blank name searches Ticketmaster with keyword="",
        // which returns every local event. Never let a bad row trigger that, whatever its source.
        if (artist.getName() == null || artist.getName().isBlank()) {
            return 0;
        }

        ShowSource source = showSources.stream()
                .filter(s -> s.id().equals(sourceId))
                .findFirst()
                .orElse(null);
        if (source == null) {
            log.atWarn().addKeyValue("sourceId", sourceId).log("scan unit skipped -- unknown source");
            return 0;
        }

        SearchSettings settings = settingsOpt.get();
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());

        ScanQuery query = new ScanQuery(artist.getName(), resolveSiteUrl(artist),
                settings.getPostalCode(), settings.getLatitude(), settings.getLongitude(),
                settings.getRadiusMiles(), settings.getCity(), start, end);

        return persistNew(owner, source.search(query));
    }

    /**
     * The artist's official-site URL for band-site scraping (#22): the cached value, or a MusicBrainz
     * "official homepage" lookup on first use, cached back onto the artist. This is the one write in the
     * scan flow -- the show sources themselves are query-only.
     */
    String resolveSiteUrl(Artist artist) {
        String url = artist.getOfficialSiteUrl();
        if (url == null) {
            url = musicBrainz.findOfficialHomepage(artist.getName()).orElse(null);
            if (url != null) {
                artist.setOfficialSiteUrl(url);
                artistRepository.save(artist);
            }
        }
        return url;
    }

    int persistNew(String owner, List<Show> shows) {
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
