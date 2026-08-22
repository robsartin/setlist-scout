package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSiteUrlService;
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
 * Runs show search for exactly one (artist, source) pair -- the per-unit replacement for the
 * whole-fleet batch scanner retired in Phase B PR4b. Owns the one shared copy of
 * {@link #resolveSiteUrl} (triggers the one write in the scan flow -- the band-site URL cache,
 * delegated to {@code catalog.ArtistSiteUrlService} since #102) and {@link #persistNew}.
 */
@Service
public class ScanUnitRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanUnitRunner.class);

    private final List<ShowSource> showSources;
    private final ArtistRepository artistRepository;
    private final ArtistSiteUrlService artistSiteUrlService;
    private final ShowRepository showRepository;
    private final SearchSettingsRepository settingsRepository;
    private final MusicBrainzService musicBrainz;

    public ScanUnitRunner(List<ShowSource> showSources,
                           ArtistRepository artistRepository,
                           ArtistSiteUrlService artistSiteUrlService,
                           ShowRepository showRepository,
                           SearchSettingsRepository settingsRepository,
                           MusicBrainzService musicBrainz) {
        this.showSources = showSources;
        this.artistRepository = artistRepository;
        this.artistSiteUrlService = artistSiteUrlService;
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

        ScanQuery query = buildQuery(artist, settings, start, end);

        return persistNew(owner, artistId, source.search(query));
    }

    /**
     * Builds the {@link ScanQuery} for one artist at the owner's location/window, resolving (and
     * caching) the official-site URL along the way. The one copy of this construction -- {@link #run}
     * calls it.
     */
    ScanQuery buildQuery(Artist artist, SearchSettings settings, LocalDateTime start, LocalDateTime end) {
        return new ScanQuery(artist.getName(), resolveSiteUrl(artist),
                artist.getDefaultVenueName(), artist.getDefaultVenueCity(),
                settings.getPostalCode(), settings.getLatitude(), settings.getLongitude(),
                settings.getRadiusMiles(), settings.getCity(), settings.getState(), start, end);
    }

    /**
     * The artist's official-site URL for band-site scraping (#22): the cached value, or a MusicBrainz
     * "official homepage" lookup on first use, cached back onto the artist via
     * {@link ArtistSiteUrlService#recordOfficialSiteUrl} -- catalog owns the {@link Artist} aggregate,
     * so {@code scan} calls into it rather than loading + saving the entity itself (#102). This is
     * the one write the scan flow triggers -- the show sources themselves are query-only.
     */
    String resolveSiteUrl(Artist artist) {
        String url = artist.getOfficialSiteUrl();
        if (url == null) {
            url = musicBrainz.findOfficialHomepage(artist.getName()).orElse(null);
            if (url != null) {
                artistSiteUrlService.recordOfficialSiteUrl(artist.getId(), artist.getOwner(), url);
            }
        }
        return url;
    }

    /**
     * Persists every genuinely-new show, and repairs a null {@code artist_id} on one already
     * persisted (issue #223).
     * <p>
     * {@code artistId} is written through on a NEW row unconditionally: {@code run}'s caller
     * already knows exactly which artist it scanned, so this is 100% accurate even for a
     * Ticketmaster row whose {@code artistName} is the event title, since it never goes through
     * name matching at all.
     * <p>
     * For a row that already exists by natural key, the null-repair is deliberately narrow: only
     * fills a null {@code artist_id} (a row written before this column existed, or otherwise left
     * unresolved), and never overwrites one already set. That bounds a name-matching-era null to
     * self-heal within one scan cycle instead of staying null for up to six months (until the
     * show's own event date passes) -- see the {@code V27} Java backfill migration for the
     * one-time historical repair this complements.
     * <p>
     * <b>Issue #230:</b> the insert itself goes through {@link ShowRepository#insertIfAbsent}, an
     * atomic {@code INSERT ... ON CONFLICT (owner, artist_name, event_date_time, venue_name) DO
     * NOTHING} against the real unique constraint -- never {@code existsBy}/{@code findBy} +
     * {@code save}, per CLAUDE.md's idempotent-write rule ({@code VenueScanRunner#persist} already
     * did this; this method predated that rule). The old read-then-write pattern raced: {@code
     * ScanPoller} runs each claimed job with no ambient transaction around {@code
     * ScanUnitRunner#run} (slow adapter calls would otherwise tie up a connection for nothing), so
     * each repository call commits independently, and two concurrent runs discovering the same
     * natural key could both see "not found" before either committed -- the loser's {@code save()}
     * then threw a genuine constraint violation, which propagated out of this loop and killed every
     * show after the colliding one in the batch. {@code insertIfAbsent} can never throw on that
     * collision; it just reports 0 rows inserted, so one colliding show never takes the rest of the
     * batch down with it (see {@code ScanUnitRunnerTest
     * #aConstraintViolationOnOneShowDoesNotAbortTheRestOfTheBatch}). When it returns 0 the row
     * already existed (written by this call or a concurrent one), so the self-heal check below still
     * runs against it exactly as before.
     */
    int persistNew(String owner, Long artistId, List<Show> shows) {
        int saved = 0;
        for (Show show : shows) {
            if (show.getEventDateTime() == null) continue;
            show.setOwner(owner);
            show.setArtistId(artistId);
            int inserted = showRepository.insertIfAbsent(owner, show.getArtistName(), show.getEventDateTime(),
                    show.getVenueName(), show.getVenueCity(), show.getPrice(), show.getSource(),
                    show.getTicketUrl(), show.getKind().name(), show.getDiscoveredAt(), artistId);
            if (inserted == 1) {
                saved++;
            } else {
                Optional<Show> existing = showRepository.findByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                        owner, show.getArtistName(), show.getEventDateTime(), show.getVenueName());
                if (existing.isPresent() && existing.get().getArtistId() == null) {
                    existing.get().setArtistId(artistId);
                    showRepository.save(existing.get());
                }
            }
        }
        return saved;
    }
}
