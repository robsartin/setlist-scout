package com.robsartin.setlistscout.expansion.source;

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
}
