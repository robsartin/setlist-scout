package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.CandidateDiscovered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Turns a {@link CandidateDiscovered} event into a PENDING_REVIEW {@link Artist}, applying the
 * same name-plausibility guard and per-owner dedup that expansion used to apply inline before
 * candidate persistence moved behind this durable, asynchronous event listener.
 */
@Component
public class CandidatePersistenceListener {

    private static final Logger log = LoggerFactory.getLogger(CandidatePersistenceListener.class);

    /** Longest a plausible band/artist name should be; anything past this reads as a sentence. */
    private static final int MAX_NAME_LENGTH = 60;

    /** Most words a plausible band/artist name should have. */
    private static final int MAX_NAME_WORDS = 8;

    private final ArtistRepository artistRepository;

    public CandidatePersistenceListener(ArtistRepository artistRepository) {
        this.artistRepository = artistRepository;
    }

    @ApplicationModuleListener
    void on(CandidateDiscovered e) {
        String name = e.name();
        if (name == null || name.isBlank()) return;
        if (!looksLikeArtistName(name)) {
            log.atWarn().addKeyValue("source", e.sourceType()).addKeyValue("name", name)
                    .log("discarded implausible candidate name (prose/refusal?)");
            return;
        }
        if (artistRepository.existsByOwnerAndNameIgnoreCase(e.owner(), name)) return; // already tracked for this user

        ArtistSource source;
        try {
            source = ArtistSource.valueOf(e.sourceType());
        } catch (IllegalArgumentException ex) {
            log.atWarn().addKeyValue("sourceType", e.sourceType()).addKeyValue("name", name)
                    .log("discarded candidate with unknown sourceType");
            return;
        }

        // DB-level idempotent insert (ON CONFLICT DO NOTHING) rather than save()+catch: this
        // listener's whole body runs in one @ApplicationModuleListener transaction, and on an
        // IDENTITY-keyed table an uncaught DataIntegrityViolationException from a real (owner,
        // name) race poisons that transaction -- Modulith's own AFTER_COMMIT completion write then
        // fails too, leaving the event stuck for redelivery instead of a clean no-op. The
        // case-insensitive existsBy check above is a best-effort pre-filter; this is the real,
        // DB-enforced guard against the case-sensitive unique constraint.
        artistRepository.insertIfAbsent(e.owner(), name, source.name(), ArtistStatus.PENDING_REVIEW.name(),
                e.discoveredVia(), e.note(), Instant.now());
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
