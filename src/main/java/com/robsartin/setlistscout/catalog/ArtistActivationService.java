package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Owns Artist status transitions and publishes the resulting activation/deactivation events.
 * <p>
 * "Active" means the artist's shows are included in search: {@code SEED} and {@code APPROVED}.
 * A status change that crosses the active/inactive boundary publishes {@link ArtistActivated}
 * or {@link ArtistDeactivated}; a change that stays on the same side of that boundary
 * (e.g. {@code PENDING_REVIEW} &rarr; {@code REJECTED}) publishes nothing.
 */
@Service
public class ArtistActivationService {

    private final ArtistRepository artistRepository;
    private final ApplicationEventPublisher publisher;

    public ArtistActivationService(ArtistRepository artistRepository, ApplicationEventPublisher publisher) {
        this.artistRepository = artistRepository;
        this.publisher = publisher;
    }

    /**
     * Change {@code id}'s status (owner-scoped). No-op if no such artist exists for {@code owner}.
     * Publishes {@link ArtistActivated} or {@link ArtistDeactivated} when the change crosses the
     * active/inactive boundary; publishes nothing otherwise.
     */
    @Transactional
    public void changeStatus(Long id, String owner, ArtistStatus newStatus) {
        Optional<Artist> found = artistRepository.findByIdAndOwner(id, owner);
        if (found.isEmpty()) {
            return;
        }
        Artist artist = found.get();
        ArtistStatus old = artist.getStatus();
        artist.setStatus(newStatus);
        artistRepository.save(artist);

        if (isActive(newStatus) && !isActive(old)) {
            publisher.publishEvent(new ArtistActivated(owner, artist.getId(), artist.getName()));
        } else if (!isActive(newStatus) && isActive(old)) {
            publisher.publishEvent(new ArtistDeactivated(owner, artist.getId()));
        }
    }

    /** A newly created SEED artist is active from the start. */
    @Transactional
    public void onSeedCreated(Artist saved) {
        publisher.publishEvent(new ArtistActivated(saved.getOwner(), saved.getId(), saved.getName()));
    }

    private static boolean isActive(ArtistStatus status) {
        return status == ArtistStatus.SEED || status == ArtistStatus.APPROVED;
    }
}
