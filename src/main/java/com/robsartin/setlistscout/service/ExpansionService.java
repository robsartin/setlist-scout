package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ExpansionService.class);

    /** Longest a plausible band/artist name should be; anything past this reads as a sentence. */
    private static final int MAX_NAME_LENGTH = 60;

    /** Most words a plausible band/artist name should have. */
    private static final int MAX_NAME_WORDS = 8;

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

        log.atInfo().addKeyValue("baseArtists", baseArtists.size()).log("expansion started");
        long startNanos = System.nanoTime();
        int processed = 0;
        int candidates = 0;
        for (Artist base : baseArtists) {
            processed++;
            candidates += expandMemberRelations(owner, base);
            candidates += expandSimilarArtists(owner, base);
            if (base.getStatus() == ArtistStatus.SEED) {
                candidates += expandTributeBands(owner, base);
            }
        }
        log.atInfo()
                .addKeyValue("artistsProcessed", processed)
                .addKeyValue("candidatesFound", candidates)
                .addKeyValue("durationMs", (System.nanoTime() - startNanos) / 1_000_000)
                .log("expansion finished");
    }

    private int expandMemberRelations(String owner, Artist base) {
        Set<String> found = new HashSet<>();
        found.addAll(musicBrainz.findRelatedArtists(base.getName()));
        found.addAll(discogs.findRelatedArtists(base.getName()));

        int added = 0;
        for (String name : found) {
            if (saveIfNew(owner, name, ArtistSource.MEMBER_EXPANSION, base.getName(),
                    "member/lineup relation of " + base.getName())) {
                added++;
            }
        }
        log.atDebug().addKeyValue("artist", base.getName()).addKeyValue("members", added).log("member expansion");
        return added;
    }

    private int expandSimilarArtists(String owner, Artist base) {
        Set<String> lastFmResults = new HashSet<>(lastFm.findSimilarArtists(base.getName(), 8));
        Set<String> llmResults = new HashSet<>(similarArtistLlm.findSimilarArtists(base.getName(), 8));

        Set<String> all = new HashSet<>();
        all.addAll(lastFmResults);
        all.addAll(llmResults);

        int added = 0;
        for (String name : all) {
            boolean confirmedByBoth = containsIgnoreCase(lastFmResults, name)
                    && containsIgnoreCase(llmResults, name);
            String note = "similar to " + base.getName()
                    + (confirmedByBoth ? " (confirmed by Last.fm + LLM)" : " (single-source match)");
            if (saveIfNew(owner, name, ArtistSource.SIMILAR_EXPANSION, base.getName(), note)) {
                added++;
            }
        }
        log.atDebug().addKeyValue("artist", base.getName()).addKeyValue("similar", added).log("similar expansion");
        return added;
    }

    private int expandTributeBands(String owner, Artist base) {
        int added = 0;
        for (String name : tributeLlm.findTributeBands(base.getName(), 5)) {
            if (saveIfNew(owner, name, ArtistSource.TRIBUTE_EXPANSION, base.getName(),
                    "tribute/cover act for " + base.getName())) {
                added++;
            }
        }
        log.atDebug().addKeyValue("artist", base.getName()).addKeyValue("tributes", added).log("tribute expansion");
        return added;
    }

    private boolean containsIgnoreCase(Set<String> set, String value) {
        return set.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }

    private boolean saveIfNew(String owner, String name, ArtistSource source, String discoveredVia, String note) {
        if (name == null || name.isBlank()) return false;
        if (!looksLikeArtistName(name)) {
            log.atWarn().addKeyValue("source", source).addKeyValue("name", name)
                    .log("discarded implausible candidate name (prose/refusal?)");
            return false;
        }
        if (artistRepository.existsByOwnerAndNameIgnoreCase(owner, name)) return false; // already tracked for this user
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, note);
        artist.setOwner(owner);
        artistRepository.save(artist);
        return true;
    }

    /**
     * Guards against LLM expansion sources (tribute/similar) handing back prose or a refusal
     * instead of a name -- e.g. "I don't know of any well-known tribute bands for X." -- which would
     * otherwise be stored verbatim as a PENDING_REVIEW "artist" and later sent to Ticketmaster as a
     * keyword. A real band/artist name is short and has few words; punctuation is deliberately not
     * checked here since plenty of real names carry it ("St. Vincent", "R.E.M.", "Panic! at the Disco").
     */
    private boolean looksLikeArtistName(String name) {
        String trimmed = name.trim();
        if (trimmed.startsWith("#")) return false;
        if (trimmed.length() > MAX_NAME_LENGTH) return false;
        return trimmed.split("\\s+").length <= MAX_NAME_WORDS;
    }
}
