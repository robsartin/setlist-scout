package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.TributeLlmService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tribute bands from LLM (limit 5) behind the {@link RelationSource} port. On by default
 * ({@code matchIfMissing = true}) -- set {@code setlistscout.sources.tribute-llm=false} (Render
 * env var {@code SETLISTSCOUT_SOURCES_TRIBUTELLM=false}) to opt this source out with zero effect
 * on the other 7 (issue #139).
 */
@Component
@ConditionalOnProperty(name = "setlistscout.sources.tribute-llm", havingValue = "true", matchIfMissing = true)
public class TributeLlmSource implements RelationSource {

    private final TributeLlmService tributeLlm;

    public TributeLlmSource(TributeLlmService tributeLlm) {
        this.tributeLlm = tributeLlm;
    }

    @Override
    public String id() {
        return "tribute-llm";
    }

    @Override
    public List<String> related(String artistName) {
        return tributeLlm.findTributeBands(artistName, 5);
    }

    @Override
    public ArtistSource classification() {
        return ArtistSource.TRIBUTE_EXPANSION;
    }

    @Override
    public String note(String baseArtist) {
        return "tribute/cover act for " + baseArtist;
    }

    /**
     * Tribute expansion is SEED-only: it hunts for tribute/cover bands of an original act, which
     * only makes sense for a hand-curated seed, not for an already-expanded artist.
     */
    @Override
    public boolean appliesTo(ArtistStatus status) {
        return status == ArtistStatus.SEED;
    }
}
