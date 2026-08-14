package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.TributeLlmService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Tribute bands from LLM (limit 5) behind the {@link RelationSource} port. */
@Component
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
