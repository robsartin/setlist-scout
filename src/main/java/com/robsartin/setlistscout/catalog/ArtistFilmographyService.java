package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.WikidataEntity;
import com.robsartin.setlistscout.shared.WikidataFilmCredit;
import com.robsartin.setlistscout.shared.WikidataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads what an artist made and records it as works and credits (#286).
 *
 * <h2>A film never becomes an artist</h2>
 * Everything written here lands in {@code work} and {@code person_work_edge}. No path through this
 * class creates an {@code Artist} row, and a test asserts the count rather than the intent -- the
 * same guard #284 put on cinema scans, for the same reason: a film title in the catalog reaches
 * the review queue, the scan claim and the expansion sources, and is tedious to unpick afterwards.
 */
@Service
public class ArtistFilmographyService {

    private static final Logger log = LoggerFactory.getLogger(ArtistFilmographyService.class);

    /** The {@code source} recorded on every edge written here -- see {@code PersonWorkEdge}. */
    static final String SOURCE = "wikidata";

    private final WikidataIdentityService identities;
    private final WikidataService wikidata;
    private final WorkRepository works;
    private final PersonWorkEdgeRepository edges;

    public ArtistFilmographyService(WikidataIdentityService identities, WikidataService wikidata,
                                     WorkRepository works, PersonWorkEdgeRepository edges) {
        this.identities = identities;
        this.wikidata = wikidata;
        this.works = works;
        this.edges = edges;
    }

    /**
     * Resolves the artist if needed, reads their filmography, and records it. Returns how many
     * credits Wikidata reported and this stored -- not how many were new, because both inserts are
     * idempotent and a re-read of the same person legitimately reports the same films again.
     *
     * <p>Zero for an unresolved artist, and no query is sent for one: without an identity there is
     * nothing to ask about, and guessing is the failure this whole path is shaped to avoid.
     */
    @Transactional
    public int refresh(String owner, Long artistId) {
        Optional<WikidataEntity> identity = identities.resolve(owner, artistId);
        if (identity.isEmpty()) return 0;

        List<WikidataFilmCredit> credits = wikidata.filmography(identity.get().qid());
        Instant now = Instant.now();
        int stored = 0;
        for (WikidataFilmCredit credit : credits) {
            works.insertIfAbsent(owner, credit.qid(), credit.title(),
                    ArtistNameNormalizer.normalize(credit.title()), credit.releaseYear(), now);
            Long workId = works.findByOwnerAndWikidataQid(owner, credit.qid())
                    .map(Work::getId)
                    .orElse(null);
            if (workId == null) {
                // Only reachable if the insert above was rejected for a reason ON CONFLICT does not
                // swallow. Logged rather than skipped silently: a filmography that quietly loses
                // films looks exactly like a person who made fewer.
                log.atWarn().addKeyValue("qid", credit.qid()).addKeyValue("title", credit.title())
                        .log("work could not be read back after insert, credit dropped");
                continue;
            }
            edges.insertIfAbsent(owner, artistId, workId, credit.role(), SOURCE, now);
            stored++;
        }
        log.atInfo().addKeyValue("artist", artistId).addKeyValue("qid", identity.get().qid())
                .addKeyValue("credits", stored).log("filmography recorded");
        return stored;
    }

    /** This artist's credits, one row per film with every role they had on it, newest first. */
    @Transactional(readOnly = true)
    public List<WorkCreditView> creditsFor(String owner, Long artistId) {
        List<PersonWorkEdge> credits = edges.findByOwnerAndArtistId(owner, artistId);
        if (credits.isEmpty()) return List.of();

        Set<Long> workIds = credits.stream().map(PersonWorkEdge::getWorkId).collect(Collectors.toSet());
        Map<Long, Work> byId = works.findByOwnerAndIdIn(owner, workIds).stream()
                .collect(Collectors.toMap(Work::getId, w -> w));

        // A TreeSet per film: two sources asserting DIRECTED are two edges but one role, and the
        // owner should see the role once.
        Map<Long, Set<String>> rolesByWork = new LinkedHashMap<>();
        for (PersonWorkEdge credit : credits) {
            rolesByWork.computeIfAbsent(credit.getWorkId(), k -> new TreeSet<>()).add(credit.getRole());
        }

        List<WorkCreditView> views = new ArrayList<>(rolesByWork.size());
        rolesByWork.forEach((workId, roles) -> {
            Work work = byId.get(workId);
            if (work != null) views.add(new WorkCreditView(work.getTitle(), work.getReleaseYear(), List.copyOf(roles)));
        });

        // Newest first; a film with no stated year sorts last rather than first, where a null would
        // otherwise read as "brand new".
        views.sort(Comparator.comparing(WorkCreditView::releaseYear,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WorkCreditView::title));
        return views;
    }
}
