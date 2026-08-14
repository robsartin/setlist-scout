package com.robsartin.setlistscout.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the one write {@code scan} needs to make against catalog's {@link Artist} aggregate: the
 * cached official-site URL used for band-site scraping (#22). Narrow on purpose -- {@code scan}
 * used to load the {@code Artist} itself and call {@code setOfficialSiteUrl}/{@code save}
 * directly ({@code scan.ScanUnitRunner#resolveSiteUrl}), which meant a {@code scan}-module
 * component was mutating catalog's aggregate root. Kept separate from
 * {@link ArtistActivationService} (status transitions + event publishing) since this write is
 * neither: it's a plain cache-fill, no status change, no event.
 */
@Service
public class ArtistSiteUrlService {

    private final ArtistRepository artistRepository;

    public ArtistSiteUrlService(ArtistRepository artistRepository) {
        this.artistRepository = artistRepository;
    }

    /**
     * Cache {@code url} as {@code artistId}'s official-site URL (owner-scoped). No-op if no such
     * artist exists for {@code owner}.
     */
    @Transactional
    public void recordOfficialSiteUrl(Long artistId, String owner, String url) {
        artistRepository.findByIdAndOwner(artistId, owner).ifPresent(artist -> {
            artist.setOfficialSiteUrl(url);
            artistRepository.save(artist);
        });
    }
}
