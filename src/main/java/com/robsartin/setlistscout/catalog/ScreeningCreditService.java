package com.robsartin.setlistscout.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Why a screening is worth seeing: the people the owner follows who are credited on the film it is
 * showing (#289). The join between #284's screenings and #286's works.
 *
 * <h2>The matching rule, and why it refuses so readily</h2>
 * A screening offers a title and sometimes a year; a work has a normalized title, a year and a
 * QID. The rule is:
 *
 * <ul>
 *   <li>exactly one work carries that title -- that is the film, whatever the years say;</li>
 *   <li>more than one -- the years must be <em>equal</em>, and must both be present;</li>
 *   <li>anything still ambiguous -- no match.</li>
 * </ul>
 *
 * <p>Measured rather than assumed. Against live Wikidata, <strong>seven</strong> different films
 * are called "Titanic" and <strong>seven</strong> are called "The Mummy", so a title is not an
 * identity. And the obvious softening -- allow a year either side, listings are sloppy -- collides
 * on real repertory staples: Titanic has a 1996 <em>and</em> a 1997, Nosferatu a 2023 and a 2024.
 * A tolerance wide enough to absorb a bad listing is wide enough to name the wrong film.
 *
 * <p>So this misses a 4K restoration listed under its re-release year, on purpose. Missing a match
 * shows the screening with no reason attached, which is what happens today; naming the wrong film
 * tells the owner something false in the one place they are looking for a reason to go.
 *
 * <h2>Computed, never stored</h2>
 * No {@code show_work} table. The inputs change whenever a filmography is refreshed or an artist
 * is approved, so a persisted link would go stale silently -- and silence is the failure mode this
 * whole sub-project is shaped around.
 */
@Service
public class ScreeningCreditService {

    private final WorkRepository works;
    private final PersonWorkEdgeRepository edges;
    private final ArtistRepository artists;

    public ScreeningCreditService(WorkRepository works, PersonWorkEdgeRepository edges,
                                   ArtistRepository artists) {
        this.works = works;
        this.edges = edges;
        this.artists = artists;
    }

    /**
     * The followed people credited on each screening's film. Screenings with no confident match,
     * and matches nobody followed worked on, are simply absent from the map rather than present
     * with an empty list -- "no reason" is one state, not two.
     */
    @Transactional(readOnly = true)
    public Map<ScreeningKey, List<ScreeningCredit>> creditsFor(String owner,
                                                                Collection<ScreeningKey> screenings) {
        Set<ScreeningKey> distinct = screenings.stream()
                .filter(k -> k != null && k.normalizedTitle() != null && !k.normalizedTitle().isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.isEmpty()) return Map.of();

        Set<String> titles = distinct.stream().map(ScreeningKey::normalizedTitle).collect(Collectors.toSet());
        Map<String, List<Work>> byTitle = works.findByOwnerAndNormalizedTitleIn(owner, titles).stream()
                .collect(Collectors.groupingBy(Work::getNormalizedTitle));

        Map<ScreeningKey, Work> matched = new LinkedHashMap<>();
        for (ScreeningKey key : distinct) {
            resolve(byTitle.getOrDefault(key.normalizedTitle(), List.of()), key.releaseYear())
                    .ifPresent(work -> matched.put(key, work));
        }
        if (matched.isEmpty()) return Map.of();

        Set<Long> workIds = matched.values().stream().map(Work::getId).collect(Collectors.toSet());
        List<PersonWorkEdge> credits = edges.findByOwnerAndWorkIdIn(owner, workIds);
        if (credits.isEmpty()) return Map.of();

        // "Followed" is ArtistActivationService#isActive -- SEED or APPROVED -- reused rather than
        // re-derived, the same way ShowController's own venue filter does. A PENDING_REVIEW artist
        // is not yet a reason to see anything, and a REJECTED one is a reason not to.
        Map<Long, String> followedNames = artists.findByOwnerAndIdIn(owner,
                        credits.stream().map(PersonWorkEdge::getArtistId).collect(Collectors.toSet()))
                .stream()
                .filter(a -> ArtistActivationService.isActive(a.getStatus()))
                .collect(Collectors.toMap(Artist::getId, Artist::getName));
        if (followedNames.isEmpty()) return Map.of();

        Map<Long, Map<Long, SortedSet<String>>> rolesByWork = new LinkedHashMap<>();
        for (PersonWorkEdge credit : credits) {
            if (!followedNames.containsKey(credit.getArtistId())) continue;
            rolesByWork
                    .computeIfAbsent(credit.getWorkId(), k -> new LinkedHashMap<>())
                    // A TreeSet because two sources asserting DIRECTED are two edges but one role.
                    .computeIfAbsent(credit.getArtistId(), k -> new TreeSet<>())
                    .add(credit.getRole());
        }

        Map<ScreeningKey, List<ScreeningCredit>> result = new LinkedHashMap<>();
        matched.forEach((key, work) -> {
            Map<Long, SortedSet<String>> perArtist = rolesByWork.get(work.getId());
            if (perArtist == null || perArtist.isEmpty()) return;
            result.put(key, perArtist.entrySet().stream()
                    .map(e -> new ScreeningCredit(followedNames.get(e.getKey()), List.copyOf(e.getValue())))
                    .sorted(Comparator.comparing(ScreeningCredit::artistName))
                    .toList());
        });
        return result;
    }

    /**
     * Which of these same-titled works the screening is showing, if it can be told at all.
     *
     * <p>A single candidate wins without consulting the year, which is what lets "Goodfellas" match
     * a listing that states none. Past that, only an exact year decides, and an exact year that
     * still leaves two candidates decides nothing.
     */
    private static Optional<Work> resolve(List<Work> candidates, Integer screeningYear) {
        if (candidates.size() == 1) return Optional.of(candidates.get(0));
        if (candidates.isEmpty() || screeningYear == null) return Optional.empty();

        List<Work> sameYear = candidates.stream()
                .filter(w -> Objects.equals(screeningYear, w.getReleaseYear()))
                .toList();
        return sameYear.size() == 1 ? Optional.of(sameYear.get(0)) : Optional.empty();
    }
}
