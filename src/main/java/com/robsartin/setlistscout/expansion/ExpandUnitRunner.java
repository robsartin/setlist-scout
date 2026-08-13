package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.events.CandidateDiscovered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Runs one expand job: queries a single {@link RelationSource} for one base artist and
 * publishes a {@link CandidateDiscovered} event per result. Nothing here persists -- the
 * catalog module's {@code @ApplicationModuleListener} turns each published event into a
 * PENDING_REVIEW artist (with its own name-guard + dedup).
 * <p>
 * Each event is published inside its own short, committed transaction via
 * {@link TransactionTemplate} -- {@code @ApplicationModuleListener} is
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so without an active, committing
 * transaction around the publish call Modulith never registers the event and the listener
 * never fires (the PR3a lesson).
 * <p>
 * {@link #run} itself is deliberately NOT {@code @Transactional}: the {@link RelationSource#related}
 * call is a slow external API/LLM hit and must not hold a DB connection open across it (the same
 * boundary the retired whole-fleet expander kept). Query first, outside any transaction, then
 * publish each result in a short transaction.
 */
@Component
public class ExpandUnitRunner {

    private static final Logger log = LoggerFactory.getLogger(ExpandUnitRunner.class);

    private final List<RelationSource> relationSources;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;

    public ExpandUnitRunner(List<RelationSource> relationSources,
                            ApplicationEventPublisher publisher,
                            TransactionTemplate transactionTemplate) {
        this.relationSources = relationSources;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
    }

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

        // Slow external call -- run it OUTSIDE any transaction so we don't hold a DB connection across it.
        List<String> related = source.related(artistName);
        String classification = source.classification().name();
        String note = source.note(artistName);

        for (String name : related) {
            if (name == null || name.isBlank()) continue;
            transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(
                    new CandidateDiscovered(owner, name, classification, artistName, note)));
        }
    }
}
