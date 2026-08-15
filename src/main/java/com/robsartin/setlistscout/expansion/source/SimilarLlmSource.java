package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.expansion.SimilarArtistLlmService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Similar artists from LLM (limit 8) behind the {@link RelationSource} port. On by default
 * ({@code matchIfMissing = true}) -- set {@code setlistscout.sources.similar-llm=false} (Render
 * env var {@code SETLISTSCOUT_SOURCES_SIMILARLLM=false}) to opt this source out with zero effect
 * on the other 7 (issue #139).
 */
@Component
@ConditionalOnProperty(name = "setlistscout.sources.similar-llm", havingValue = "true", matchIfMissing = true)
public class SimilarLlmSource implements RelationSource {

    private final SimilarArtistLlmService similarArtistLlm;

    public SimilarLlmSource(SimilarArtistLlmService similarArtistLlm) {
        this.similarArtistLlm = similarArtistLlm;
    }

    @Override
    public String id() {
        return "similar-llm";
    }

    @Override
    public List<String> related(String artistName) {
        return similarArtistLlm.findSimilarArtists(artistName, 8);
    }

    @Override
    public ArtistSource classification() {
        return ArtistSource.SIMILAR_EXPANSION;
    }

    @Override
    public String note(String baseArtist) {
        return "similar to " + baseArtist + " (via LLM)";
    }
}
