package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.DiscogsRelationSource;
import com.robsartin.setlistscout.expansion.source.LastFmSimilarSource;
import com.robsartin.setlistscout.expansion.source.MusicBrainzRelationSource;
import com.robsartin.setlistscout.expansion.source.SimilarLlmSource;
import com.robsartin.setlistscout.expansion.source.TributeLlmSource;
import com.robsartin.setlistscout.shared.events.CandidateDiscovered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntBiFunction;

/**
 * Runs the expansion pipeline against every SEED/APPROVED artist and publishes a
 * {@link CandidateDiscovered} event for each newly found name. Nothing here touches show
 * search directly, and nothing here persists -- the catalog module's listener turns a
 * published event into a PENDING_REVIEW artist (see CandidatePersistenceListener), and a
 * human has to approve it from there (see ReviewController).
 */
@Service
public class ExpansionService {

    private static final Logger log = LoggerFactory.getLogger(ExpansionService.class);

    private final ArtistRepository artistRepository;
    private final MusicBrainzRelationSource musicBrainzSource;
    private final DiscogsRelationSource discogsSource;
    private final LastFmSimilarSource lastFmSource;
    private final SimilarLlmSource similarLlmSource;
    private final TributeLlmSource tributeSource;
    private final ApplicationEventPublisher publisher;

    public ExpansionService(ArtistRepository artistRepository,
                             MusicBrainzRelationSource musicBrainzSource,
                             DiscogsRelationSource discogsSource,
                             LastFmSimilarSource lastFmSource,
                             SimilarLlmSource similarLlmSource,
                             TributeLlmSource tributeSource,
                             ApplicationEventPublisher publisher) {
        this.artistRepository = artistRepository;
        this.musicBrainzSource = musicBrainzSource;
        this.discogsSource = discogsSource;
        this.lastFmSource = lastFmSource;
        this.similarLlmSource = similarLlmSource;
        this.tributeSource = tributeSource;
        this.publisher = publisher;
    }

    public void expandAll(String owner) {
        List<Artist> baseArtists = artistRepository.findByOwnerAndStatusIn(
                owner, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));

        log.atInfo().addKeyValue("baseArtists", baseArtists.size()).log("expansion started");
        long startNanos = System.nanoTime();
        int processed = 0;
        int candidatesPublished = 0;
        for (Artist base : baseArtists) {
            processed++;
            candidatesPublished += safely(owner, base, "members", this::expandMemberRelations);
            candidatesPublished += safely(owner, base, "similar", this::expandSimilarArtists);
            if (base.getStatus() == ArtistStatus.SEED) {
                candidatesPublished += safely(owner, base, "tributes", this::expandTributeBands);
            }
        }
        log.atInfo()
                .addKeyValue("artistsProcessed", processed)
                .addKeyValue("candidatesPublished", candidatesPublished)
                .addKeyValue("durationMs", (System.nanoTime() - startNanos) / 1_000_000)
                .log("expansion finished");
    }

    /**
     * Runs a single expansion dimension for one artist, isolated from the others. One flaky
     * source (Discogs/Last.fm/LLM/MusicBrainz hiccup) must never abort the whole {@link #expandAll}
     * run -- log a WARN with enough context to chase it down and move on to the next dimension/artist.
     */
    private int safely(String owner, Artist base, String dimension, ToIntBiFunction<String, Artist> op) {
        try {
            return op.applyAsInt(owner, base);
        } catch (RuntimeException e) {
            log.atWarn().setCause(e)
                    .addKeyValue("owner", owner)
                    .addKeyValue("artist", base.getName())
                    .addKeyValue("dimension", dimension)
                    .log("expansion dimension failed");
            return 0;
        }
    }

    private int expandMemberRelations(String owner, Artist base) {
        Set<String> found = new HashSet<>();
        found.addAll(musicBrainzSource.related(base.getName()));
        found.addAll(discogsSource.related(base.getName()));

        int added = 0;
        for (String name : found) {
            publisher.publishEvent(new CandidateDiscovered(owner, name, ArtistSource.MEMBER_EXPANSION.name(),
                    base.getName(), "member/lineup relation of " + base.getName()));
            added++;
        }
        log.atDebug().addKeyValue("artist", base.getName()).addKeyValue("members", added).log("member expansion");
        return added;
    }

    private int expandSimilarArtists(String owner, Artist base) {
        Set<String> lastFmResults = new HashSet<>(lastFmSource.related(base.getName()));
        Set<String> llmResults = new HashSet<>(similarLlmSource.related(base.getName()));

        Set<String> all = new HashSet<>();
        all.addAll(lastFmResults);
        all.addAll(llmResults);

        int added = 0;
        for (String name : all) {
            boolean confirmedByBoth = containsIgnoreCase(lastFmResults, name)
                    && containsIgnoreCase(llmResults, name);
            String note = "similar to " + base.getName()
                    + (confirmedByBoth ? " (confirmed by Last.fm + LLM)" : " (single-source match)");
            publisher.publishEvent(new CandidateDiscovered(owner, name, ArtistSource.SIMILAR_EXPANSION.name(),
                    base.getName(), note));
            added++;
        }
        log.atDebug().addKeyValue("artist", base.getName()).addKeyValue("similar", added).log("similar expansion");
        return added;
    }

    private int expandTributeBands(String owner, Artist base) {
        int added = 0;
        for (String name : tributeSource.related(base.getName())) {
            publisher.publishEvent(new CandidateDiscovered(owner, name, ArtistSource.TRIBUTE_EXPANSION.name(),
                    base.getName(), "tribute/cover act for " + base.getName()));
            added++;
        }
        log.atDebug().addKeyValue("artist", base.getName()).addKeyValue("tributes", added).log("tribute expansion");
        return added;
    }

    private boolean containsIgnoreCase(Set<String> set, String value) {
        return set.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }
}
