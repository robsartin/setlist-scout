package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.expansion.SimilarArtistLlmService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Similar artists from LLM (limit 8) behind the {@link RelationSource} port. */
@Component
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
}
