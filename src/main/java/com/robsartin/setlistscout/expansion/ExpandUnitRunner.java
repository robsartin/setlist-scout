package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.events.CandidateDiscovered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Runs one expand job: queries a single {@link RelationSource} for one base artist and
 * publishes a {@link CandidateDiscovered} event per result. Nothing here persists -- the
 * catalog module's {@code @ApplicationModuleListener} turns each published event into a
 * PENDING_REVIEW artist (with its own name-guard + dedup).
 * <p>
 * {@link #run} is {@code @Transactional} so every publish happens inside a committed
 * transaction -- {@code @ApplicationModuleListener} is
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so without an active, committing
 * transaction around the publish call Modulith never registers the event and the listener
 * never fires (the PR3a lesson).
 */
@Component
public class ExpandUnitRunner {

    private static final Logger log = LoggerFactory.getLogger(ExpandUnitRunner.class);

    private final List<RelationSource> relationSources;
    private final ApplicationEventPublisher publisher;

    public ExpandUnitRunner(List<RelationSource> relationSources, ApplicationEventPublisher publisher) {
        this.relationSources = relationSources;
        this.publisher = publisher;
    }

    @Transactional
    public void run(String owner, Long artistId, String sourceId, String artistName) {
        RelationSource source = relationSources.stream()
                .filter(candidate -> candidate.id().equals(sourceId))
                .findFirst()
                .orElse(null);

        if (source == null) {
            log.atWarn()
                    .addKeyValue("owner", owner)
                    .addKeyValue("artistId", artistId)
                    .addKeyValue("sourceId", sourceId)
                    .log("no RelationSource found for expand job; skipping");
            return;
        }

        for (String name : source.related(artistName)) {
            if (name == null || name.isBlank()) continue;
            publisher.publishEvent(new CandidateDiscovered(
                    owner, name, source.classification().name(), artistName, source.note(artistName)));
        }
    }
}
