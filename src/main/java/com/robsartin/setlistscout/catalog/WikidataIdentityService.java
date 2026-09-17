package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.MusicBrainzService;
import com.robsartin.setlistscout.shared.WikidataEntity;
import com.robsartin.setlistscout.shared.WikidataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Decides which Wikidata entity an artist IS, and records it (#286).
 *
 * <h2>Two sources, in this order, and the order is the safety property</h2>
 * MusicBrainz first, because its {@code wikidata} url-rel is a human-curated assertion about
 * <em>this</em> artist. Only when MusicBrainz carries no link does this fall back to matching the
 * name against Wikidata's own labels, and that fallback refuses anything ambiguous.
 *
 * <p>The asymmetry is deliberate. A wrong QID is the one error nothing downstream can catch: it is
 * well-formed, it resolves, and it silently hangs somebody else's filmography on an artist the
 * owner follows. Against live Wikidata the name "Willie Nelson" matches the musician and an
 * American boxer; "Nirvana" matches three bands. MusicBrainz resolves all of them, because it
 * already knows which one is a recording artist -- so the curated path is not merely cheaper, it
 * is the one that gets the common case right.
 */
@Service
public class WikidataIdentityService {

    private static final Logger log = LoggerFactory.getLogger(WikidataIdentityService.class);

    private final MusicBrainzService musicBrainz;
    private final WikidataService wikidata;
    private final ArtistRepository artists;

    public WikidataIdentityService(MusicBrainzService musicBrainz, WikidataService wikidata,
                                    ArtistRepository artists) {
        this.musicBrainz = musicBrainz;
        this.wikidata = wikidata;
        this.artists = artists;
    }

    /**
     * This artist's Wikidata identity, resolving and storing it on first ask.
     *
     * <p>Empty means "not identified", which is a legitimate resting state and not an error: an
     * artist whose name cannot be pinned to exactly one entity stays unresolved, has no
     * filmography read for them, and costs nothing further.
     */
    @Transactional
    public Optional<WikidataEntity> resolve(String owner, Long artistId) {
        Artist artist = artists.findById(artistId)
                .filter(a -> a.getOwner().equals(owner))
                .orElse(null);
        if (artist == null) return Optional.empty();

        // Already decided. Re-asking would spend two network calls to learn what the row says, and
        // -- worse -- would let a later Wikidata edit silently move an artist's identity.
        if (artist.getWikidataQid() != null) {
            return Optional.of(new WikidataEntity(
                    artist.getWikidataQid(), artist.getWikidataLabel(), artist.getWikidataDescription()));
        }

        String name = artist.getName();
        Optional<WikidataEntity> resolved = musicBrainz.findWikidataQid(name)
                // MusicBrainz gives a bare QID. Fetch the label and description so the match is
                // legible on the artist page -- "Q206112" tells nobody it is the right person.
                .map(qid -> wikidata.describe(qid).orElseGet(() -> new WikidataEntity(qid, name, null)))
                .or(() -> wikidata.resolveByExactLabel(name));

        resolved.ifPresentOrElse(
                entity -> {
                    artist.setWikidataIdentity(entity.qid(), entity.label(), entity.description());
                    log.atInfo().addKeyValue("artist", name).addKeyValue("qid", entity.qid())
                            .log("artist resolved to a Wikidata entity");
                },
                () -> log.atInfo().addKeyValue("artist", name)
                        .log("artist left unresolved -- no curated link and no unambiguous name match"));
        return resolved;
    }
}
