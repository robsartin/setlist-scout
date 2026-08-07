package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.domain.SearchSettings;
import com.robsartin.setlistscout.domain.Show;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.repository.SearchSettingsRepository;
import com.robsartin.setlistscout.repository.ShowRepository;
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

    private final ArtistRepository artistRepository;
    private final ShowRepository showRepository;
    private final SearchSettingsRepository settingsRepository;
    private final TicketmasterService ticketmaster;
    private final BandsintownService bandsintown;

    public ShowAggregationService(ArtistRepository artistRepository,
                                   ShowRepository showRepository,
                                   SearchSettingsRepository settingsRepository,
                                   TicketmasterService ticketmaster,
                                   BandsintownService bandsintown) {
        this.artistRepository = artistRepository;
        this.showRepository = showRepository;
        this.settingsRepository = settingsRepository;
        this.ticketmaster = ticketmaster;
        this.bandsintown = bandsintown;
    }

    public void scanForShows() {
        SearchSettings settings = settingsRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException(
                        "SearchSettings row missing -- run the DataInitializer / seed it manually"));

        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());

        List<Artist> activeArtists = artistRepository.findByStatusIn(
                List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));

        for (Artist artist : activeArtists) {
            List<Show> tmShows = ticketmaster.searchShows(
                    artist.getName(), settings.getCity(), settings.getState(),
                    settings.getRadiusMiles(), start, end);
            // Bandsintown has no server-side radius filter; pass cityFilter=null to cast
            // a wider net, then rely on Ticketmaster's radius filter as the primary cut.
            List<Show> bitShows = bandsintown.searchShows(
                    artist.getName(), null, settings.getState(), start, end);

            persistNew(tmShows);
            persistNew(bitShows);
        }
    }

    private void persistNew(List<Show> shows) {
        for (Show show : shows) {
            if (show.getEventDateTime() == null) continue;
            boolean exists = showRepository.existsByArtistNameAndEventDateTimeAndVenueName(
                    show.getArtistName(), show.getEventDateTime(), show.getVenueName());
            if (!exists) {
                showRepository.save(show);
            }
        }
    }
}
