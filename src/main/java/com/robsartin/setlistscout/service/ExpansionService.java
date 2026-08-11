package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the expansion pipeline against every SEED/APPROVED artist and stores newly
 * discovered names as PENDING_REVIEW. Nothing here touches show search directly --
 * a human has to approve a PENDING_REVIEW artist first (see ArtistController).
 */
@Service
public class ExpansionService {

    private final ArtistRepository artistRepository;
    private final MusicBrainzService musicBrainz;
    private final DiscogsService discogs;
    private final LastFmService lastFm;
    private final SimilarArtistLlmService similarArtistLlm;
    private final TributeLlmService tributeLlm;

    public ExpansionService(ArtistRepository artistRepository,
                             MusicBrainzService musicBrainz,
                             DiscogsService discogs,
                             LastFmService lastFm,
                             SimilarArtistLlmService similarArtistLlm,
                             TributeLlmService tributeLlm) {
        this.artistRepository = artistRepository;
        this.musicBrainz = musicBrainz;
        this.discogs = discogs;
        this.lastFm = lastFm;
        this.similarArtistLlm = similarArtistLlm;
        this.tributeLlm = tributeLlm;
    }

    public void expandAll(String owner) {
        List<Artist> baseArtists = artistRepository.findByOwnerAndStatusIn(
                owner, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));

        for (Artist base : baseArtists) {
            expandMemberRelations(owner, base);
            expandSimilarArtists(owner, base);
            if (base.getStatus() == ArtistStatus.SEED) {
                expandTributeBands(owner, base);
            }
        }
    }

    private void expandMemberRelations(String owner, Artist base) {
        Set<String> found = new HashSet<>();
        found.addAll(musicBrainz.findRelatedArtists(base.getName()));
        found.addAll(discogs.findRelatedArtists(base.getName()));

        for (String name : found) {
            saveIfNew(owner, name, ArtistSource.MEMBER_EXPANSION, base.getName(),
                    "member/lineup relation of " + base.getName());
        }
    }

    private void expandSimilarArtists(String owner, Artist base) {
        Set<String> lastFmResults = new HashSet<>(lastFm.findSimilarArtists(base.getName(), 8));
        Set<String> llmResults = new HashSet<>(similarArtistLlm.findSimilarArtists(base.getName(), 8));

        Set<String> all = new HashSet<>();
        all.addAll(lastFmResults);
        all.addAll(llmResults);

        for (String name : all) {
            boolean confirmedByBoth = containsIgnoreCase(lastFmResults, name)
                    && containsIgnoreCase(llmResults, name);
            String note = "similar to " + base.getName()
                    + (confirmedByBoth ? " (confirmed by Last.fm + LLM)" : " (single-source match)");
            saveIfNew(owner, name, ArtistSource.SIMILAR_EXPANSION, base.getName(), note);
        }
    }

    private void expandTributeBands(String owner, Artist base) {
        for (String name : tributeLlm.findTributeBands(base.getName(), 5)) {
            saveIfNew(owner, name, ArtistSource.TRIBUTE_EXPANSION, base.getName(),
                    "tribute/cover act for " + base.getName());
        }
    }

    private boolean containsIgnoreCase(Set<String> set, String value) {
        return set.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }

    private void saveIfNew(String owner, String name, ArtistSource source, String discoveredVia, String note) {
        if (name == null || name.isBlank()) return;
        if (artistRepository.existsByOwnerAndNameIgnoreCase(owner, name)) return; // already tracked for this user
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, note);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }
}
