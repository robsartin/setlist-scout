package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.RelationDiscovered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Turns a {@link RelationDiscovered} event into BOTH a PENDING_REVIEW {@link Artist} node (if one
 * doesn't already exist for this owner, under ANY spelling) AND an {@link ArtistEdge} row
 * recording the specific assertion -- in one listener transaction, so there's no async ordering
 * race between "the node exists" and "the edge referencing it exists" (see
 * {@code docs/explorations/2026-08-14-artist-graph-model.md} section "Modulith boundaries").
 * <p>
 * Does NOT short-circuit the EDGE write when the to-artist already exists for this owner (unlike
 * the retired {@code CandidatePersistenceListener} this replaces): the edge is always attempted,
 * so a second source corroborating an already-known relationship still gets its own {@code
 * artist_edge} row instead of being silently dropped -- the corroboration-loss defect the graph
 * model was built to fix.
 * <p>
 * DOES short-circuit the NODE write via {@link ArtistNameMatcher} (issue #118): {@link
 * ArtistRepository#insertIfAbsent}'s {@code ON CONFLICT (owner, name)} only catches an
 * exact-string repeat, and the DB constraint behind it is case- and punctuation-SENSITIVE (see
 * {@code ArtistRepositoryTest#uniqueConstraintIsCaseSensitive}) -- left alone, a rejected
 * "Charlie Parker's Re-Boppers" does nothing to stop "Charlie Parker's Re-boppers" from a
 * different source insertIfAbsent-ing its way in as a brand-new PENDING_REVIEW row. The matcher
 * catches that case regardless of the existing row's status (including REJECTED), and this
 * listener resolves the edge against whichever row already exists instead of creating a second one.
 */
@Component
public class RelationDiscoveredListener {

    private static final Logger log = LoggerFactory.getLogger(RelationDiscoveredListener.class);

    /** Longest a plausible band/artist name should be; anything past this reads as a sentence. */
    private static final int MAX_NAME_LENGTH = 60;

    /** Most words a plausible band/artist name should have. */
    private static final int MAX_NAME_WORDS = 8;

    private final ArtistRepository artistRepository;
    private final ArtistEdgeRepository artistEdgeRepository;
    private final ArtistNameMatcher artistNameMatcher;

    public RelationDiscoveredListener(ArtistRepository artistRepository, ArtistEdgeRepository artistEdgeRepository,
                                       ArtistNameMatcher artistNameMatcher) {
        this.artistRepository = artistRepository;
        this.artistEdgeRepository = artistEdgeRepository;
        this.artistNameMatcher = artistNameMatcher;
    }

    @ApplicationModuleListener
    void on(RelationDiscovered e) {
        String toArtistName = e.toArtistName();
        if (toArtistName == null || toArtistName.isBlank()) return;
        if (!looksLikeArtistName(toArtistName)) {
            log.atWarn().addKeyValue("source", e.source()).addKeyValue("name", toArtistName)
                    .log("discarded implausible related-artist name (prose/refusal?)");
            return;
        }

        ArtistSource type;
        try {
            type = ArtistSource.valueOf(e.type());
        } catch (IllegalArgumentException ex) {
            log.atWarn().addKeyValue("type", e.type()).addKeyValue("name", toArtistName)
                    .log("discarded relation with unknown type");
            return;
        }

        Long toArtistId = resolveOrCreateToArtist(e.owner(), toArtistName, type, e.fromArtistName(), e.note());
        if (toArtistId == null) {
            // Shouldn't happen -- either the matcher found an existing row, or insertIfAbsent just
            // ensured one exists -- but guards against writing an edge with a null to_artist_id
            // (the artist_edge FK is NOT NULL).
            log.atWarn().addKeyValue("owner", e.owner()).addKeyValue("name", toArtistName)
                    .log("could not resolve to-artist id; skipping edge write");
            return;
        }

        // Same idempotent-upsert invariant as insertIfAbsent above, on the artist_edge_unique
        // (owner, from_artist_id, to_artist_id, type, source) constraint -- and critically, source
        // is part of that constraint, so a second source corroborating this exact relationship
        // inserts a second, distinct edge rather than colliding with the first.
        artistEdgeRepository.insertIfAbsent(e.owner(), e.fromArtistId(), toArtistId,
                type.name(), e.source(), e.note(), null, Instant.now());
    }

    /**
     * Resolves the to-artist id for the edge write, creating a new PENDING_REVIEW node only if
     * none already exists for this owner under ANY spelling (issue #118). A normalized-name match
     * against an existing row -- of ANY status, including REJECTED -- reuses that row's id instead
     * of inserting a new one, so a re-suggestion of a previously rejected artist under a new
     * spelling doesn't resurface as a fresh PENDING_REVIEW candidate.
     */
    private Long resolveOrCreateToArtist(String owner, String toArtistName, ArtistSource type,
                                          String fromArtistName, String note) {
        Optional<ArtistNameStatusView> existing = artistNameMatcher.findExistingMatch(owner, toArtistName);
        if (existing.isPresent()) {
            ArtistNameStatusView match = existing.get();
            if (match.getStatus() == ArtistStatus.REJECTED) {
                log.atInfo().addKeyValue("owner", owner).addKeyValue("name", toArtistName)
                        .addKeyValue("matchedName", match.getName())
                        .log("suppressed re-suggestion of a previously rejected artist under a new spelling");
            }
            return match.getId();
        }

        // DB-level idempotent insert (ON CONFLICT DO NOTHING) rather than save()+catch: this
        // listener's whole body runs in one @ApplicationModuleListener transaction, and on an
        // IDENTITY-keyed table an uncaught DataIntegrityViolationException from a real (owner,
        // name) race poisons that transaction -- Modulith's own AFTER_COMMIT completion write then
        // fails too, leaving the event stuck for redelivery instead of a clean no-op. This remains
        // the backstop for a same-spelling race that slips past the matcher's read above (the
        // matcher is a best-effort pre-check, same as the existsBy pre-check it supersedes).
        artistRepository.insertIfAbsent(owner, toArtistName, ArtistNameNormalizer.normalize(toArtistName),
                type.name(), ArtistStatus.PENDING_REVIEW.name(), fromArtistName, note, Instant.now());

        return artistRepository.findByOwnerAndName(owner, toArtistName).map(Artist::getId).orElse(null);
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
