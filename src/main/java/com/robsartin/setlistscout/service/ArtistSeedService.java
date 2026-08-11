package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import org.springframework.stereotype.Service;

/**
 * Adds a band name to an owner's seed list, applied consistently everywhere a name can arrive:
 * the single "Add a band" box, an uploaded text file, and the startup seed-bands.txt import.
 * One place decides what counts as a real, new name (trimmed, non-blank, not a {@code #} comment,
 * not already present for the owner).
 */
@Service
public class ArtistSeedService {

    private final ArtistRepository artistRepository;

    public ArtistSeedService(ArtistRepository artistRepository) {
        this.artistRepository = artistRepository;
    }

    /**
     * Add {@code rawName} as a SEED artist for {@code owner}, unless it is blank, a {@code #} comment,
     * or already exists for that owner (case-insensitive). The name is trimmed before use.
     *
     * @return {@code true} if a new seed was added, {@code false} if it was skipped.
     */
    public boolean addSeedIfNew(String owner, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.startsWith("#")) {
            return false;
        }
        if (artistRepository.existsByOwnerAndNameIgnoreCase(owner, name)) {
            return false;
        }
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        artistRepository.save(artist);
        return true;
    }
}
