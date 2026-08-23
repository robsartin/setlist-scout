package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.events.ArtistSiteUrlChanged;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Retires an artist's stale band-site shows when its official-site URL changes (issue #248):
 * {@code catalog.ArtistSiteUrlService#recordOfficialSiteUrl} publishes {@link ArtistSiteUrlChanged}
 * only when a real prior URL was replaced by a different one, so every delivery here means there is
 * a genuine old source to clean up -- the old site is no longer scraped, so anything still on
 * {@code show_event} under its source is stale by definition.
 * <p>
 * {@code catalog} cannot reach {@code scan}'s {@link ShowRepository} directly -- {@code scan}
 * already depends on {@code catalog}, and the reverse is a module cycle {@code ModularityTests}
 * rejects (proven while building #246) -- so this listens for the event instead, the same shape
 * {@link ScanJobListener} already uses for {@code ArtistActivated}/{@code ArtistDeactivated}.
 */
@Component
public class ShowRetirementListener {

    private final ShowRepository showRepository;

    public ShowRetirementListener(ShowRepository showRepository) {
        this.showRepository = showRepository;
    }

    @ApplicationModuleListener
    void onArtistSiteUrlChanged(ArtistSiteUrlChanged e) {
        // BandSiteScraperService#domainOf is the SAME method that derived the source string when
        // the now-stale rows were originally scraped -- reusing it (rather than a second,
        // hand-rolled host derivation) guarantees the delete's source string is byte-for-byte the
        // one those rows actually carry, which is the whole point: "band-site:www.example.com" and
        // "band-site:example.com" differ only by "www." and must never be conflated.
        String oldSource = "band-site:" + BandSiteScraperService.domainOf(e.oldUrl());
        showRepository.deleteByOwnerAndArtistIdAndSourceAndHiddenAtIsNull(e.owner(), e.artistId(), oldSource);
    }
}
