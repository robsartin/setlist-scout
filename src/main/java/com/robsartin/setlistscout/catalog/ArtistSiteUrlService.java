package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.ArtistSiteUrlChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the one write site for catalog's {@link Artist} aggregate's cached official-site URL
 * (#22) -- both {@code scan}'s MusicBrainz auto-resolve-and-cache
 * ({@code scan.ScanUnitRunner#resolveSiteUrl}) and the owner's own manual edit
 * ({@code ArtistController#setSiteUrl}) go through here rather than loading + saving the entity
 * directly, the same reasoning {@link ArtistActivationService} already applies to status changes:
 * one write site means one place that can read the OLD value before it's gone.
 * <p>
 * Since #248, that old-value read is exactly what makes the URL-change retirement flow possible:
 * when a real prior URL is REPLACED by a different one, this publishes {@link ArtistSiteUrlChanged}
 * so {@code scan.ShowRetirementListener} can delete the artist's shows still attributed to the old
 * site -- that source is stale by definition once the cached URL no longer points at it. Publishing
 * nothing when the old value was null or unchanged means "there is a genuine previous source to
 * retire" is exactly what the event's existence asserts; the listener doesn't have to re-derive
 * that decision.
 */
@Service
public class ArtistSiteUrlService {

    private final ArtistRepository artistRepository;
    private final ApplicationEventPublisher publisher;

    public ArtistSiteUrlService(ArtistRepository artistRepository, ApplicationEventPublisher publisher) {
        this.artistRepository = artistRepository;
        this.publisher = publisher;
    }

    /**
     * Cache {@code url} as {@code artistId}'s official-site URL (owner-scoped). No-op if no such
     * artist exists for {@code owner}. Publishes {@link ArtistSiteUrlChanged} with the OLD url --
     * read here, before the overwrite, since it can't be recovered afterwards -- when that old
     * value was non-null AND different from the new one (#248). A first-time set (old value null)
     * or re-setting the same value (including two no-ops) publishes nothing: neither case has a
     * genuine previous source to retire.
     */
    @Transactional
    public void recordOfficialSiteUrl(Long artistId, String owner, String url) {
        artistRepository.findByIdAndOwner(artistId, owner).ifPresent(artist -> {
            String oldUrl = artist.getOfficialSiteUrl();
            artist.setOfficialSiteUrl(url);
            artistRepository.save(artist);
            if (oldUrl != null && !oldUrl.equals(url)) {
                publisher.publishEvent(new ArtistSiteUrlChanged(owner, artist.getId(), oldUrl));
            }
        });
    }
}
