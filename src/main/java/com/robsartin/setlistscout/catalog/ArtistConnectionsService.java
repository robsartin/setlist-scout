package com.robsartin.setlistscout.catalog;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Connections page's traversal/aggregation logic (issue #112, graph phase 3): an AGGREGATE
 * 2-hop traversal of the {@code artist_edge} graph starting from EVERY one of the owner's active
 * (SEED/APPROVED) artists at once, surfacing artists reachable that way as genuinely new
 * discovery material -- something the per-artist debug tool at {@code GET /artists/{id}/graph}
 * (issue #111, {@code ArtistController#graph}) doesn't do: that page walks 2 hops from ONE
 * artist and shows the raw reachable set, with no filtering and no aggregation across an owner's
 * whole active list.
 *
 * <h2>"Already in the catalog" means already decided, not "any row exists"</h2>
 * A literal reading of "exclude anything the owner already has in their catalog, under any
 * status" turns out to mean something specific and non-obvious in this codebase: {@code
 * RelationDiscoveredListener} always resolves-or-creates the to-artist's {@link Artist} row
 * (as {@code PENDING_REVIEW}) in the SAME transaction as the edge write (see its Javadoc), and
 * {@code artist_edge}'s FK constraints guarantee both endpoints of every edge already have an
 * {@code Artist} row. That means EVERY artist reachable via ANY persisted edge structurally
 * already has an {@code Artist} row for this owner, in SOME status -- so "exclude if any status
 * row exists at all" would exclude the entire reachable set, always, by construction, regardless
 * of what the graph looks like. That can't be the intent of a page meant to be "something a user
 * actually looks at."
 * <p>
 * The sensible reading -- and the one this class implements -- is "exclude anything the owner
 * has already made a decision about": {@code SEED} (already tracking it), {@code APPROVED}
 * (already tracking it), {@code REJECTED} (already said no), {@code REMOVED} (a former seed
 * already taken off the list). A {@code PENDING_REVIEW} artist has NOT been decided on yet --
 * from the owner's point of view it's still new, even though it already has a database row --
 * so it's the one status allowed to appear here. In practice this means: a {@code PENDING_REVIEW}
 * artist reachable through the graph shows up here too, alongside (and grouped by) which
 * seed(s)/path(s) the graph says connect to it -- a different, richer lens on the same candidate
 * than the flat Candidates page, not a duplicate of it.
 *
 * <h2>Traversal approach</h2>
 * Two batched, set-based queries instead of a recursive CTE or a per-artist loop:
 * <ol>
 *     <li>Hop 1: every outgoing edge from ANY active artist, in one
 *     {@link ArtistEdgeRepository#findByOwnerAndFromArtistIdIn} call.</li>
 *     <li>Hop 2: every outgoing edge from any hop-1 TARGET, in a second call.</li>
 * </ol>
 * That's it -- exactly 2 rounds, so a 3-hop-only artist is never queried at all (the bound is
 * structural, not a runtime depth check). Both queries use the existing
 * {@code artist_edge_from_idx (owner, from_artist_id)} index. At this app's real scale (~5,400
 * edges total, active-artist counts in the tens to low hundreds) this is two indexed lookups
 * touching a small slice of the table, regardless of how many active artists there are -- unlike
 * calling the single-artist {@code reachableWithin} recursive CTE once per active artist (N
 * round trips), or a multi-origin recursive CTE (which would need extra machinery to recover
 * per-hop edge type/source for display; these two flat queries hand back real {@link ArtistEdge}
 * rows directly, which is exactly the provenance detail this page needs to show).
 * <p>
 * Reachability is NOT collapsed to a shortest-depth summary (unlike {@code reachableWithin}):
 * every distinct path -- every seed, every intermediate, every corroborating source -- that
 * reaches a discovered artist is preserved and shown, because an artist reachable multiple ways
 * is exactly the kind of signal this page exists to surface honestly.
 */
@Component
public class ArtistConnectionsService {

    private final ArtistRepository artistRepository;
    private final ArtistEdgeRepository artistEdgeRepository;

    public ArtistConnectionsService(ArtistRepository artistRepository, ArtistEdgeRepository artistEdgeRepository) {
        this.artistRepository = artistRepository;
        this.artistEdgeRepository = artistEdgeRepository;
    }

    public List<DiscoveredConnection> discoverConnections(String owner) {
        List<Artist> active = artistRepository.findByOwnerAndStatusIn(
                owner, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));
        if (active.isEmpty()) {
            return List.of();
        }
        Map<Long, String> activeNamesById = active.stream()
                .collect(Collectors.toMap(Artist::getId, Artist::getName));

        List<ArtistEdge> hop1Edges = artistEdgeRepository.findByOwnerAndFromArtistIdIn(owner, activeNamesById.keySet());

        Set<Long> hop1TargetIds = hop1Edges.stream().map(ArtistEdge::getToArtistId).collect(Collectors.toSet());
        List<ArtistEdge> hop2Edges = hop1TargetIds.isEmpty() ? List.of()
                : artistEdgeRepository.findByOwnerAndFromArtistIdIn(owner, hop1TargetIds);

        Set<Long> candidateIds = new HashSet<>();
        hop1Edges.forEach(e -> candidateIds.add(e.getToArtistId()));
        hop2Edges.forEach(e -> candidateIds.add(e.getToArtistId()));
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        // Names/statuses needed for: every candidate (to filter + display) and every hop-1 target
        // (as a possible "via" intermediate name for a 2-hop path).
        Set<Long> idsNeedingResolution = new HashSet<>(candidateIds);
        idsNeedingResolution.addAll(hop1TargetIds);
        Map<Long, Artist> artistsById = artistRepository.findByOwnerAndIdIn(owner, idsNeedingResolution).stream()
                .collect(Collectors.toMap(Artist::getId, a -> a));

        // Hop-1 edges grouped by target: both this owner's direct (1-hop) connections AND, for a
        // hop-2 edge's "from" id, how that intermediate artist was itself reached from a seed.
        Map<Long, List<ArtistEdge>> hop1ByTarget = hop1Edges.stream()
                .collect(Collectors.groupingBy(ArtistEdge::getToArtistId));

        Map<Long, List<ConnectionPath>> pathsByCandidate = new HashMap<>();
        for (ArtistEdge e : hop1Edges) {
            String seedName = activeNamesById.get(e.getFromArtistId());
            pathsByCandidate.computeIfAbsent(e.getToArtistId(), id -> new ArrayList<>())
                    .add(new ConnectionPath(seedName, null, 1, e.getType(), e.getSource()));
        }
        for (ArtistEdge e : hop2Edges) {
            Long viaId = e.getFromArtistId();
            Artist viaArtist = artistsById.get(viaId);
            String viaName = viaArtist != null ? viaArtist.getName() : null;
            for (ArtistEdge seedEdge : hop1ByTarget.getOrDefault(viaId, List.of())) {
                String seedName = activeNamesById.get(seedEdge.getFromArtistId());
                pathsByCandidate.computeIfAbsent(e.getToArtistId(), id -> new ArrayList<>())
                        .add(new ConnectionPath(seedName, viaName, 2, e.getType(), e.getSource()));
            }
        }

        List<DiscoveredConnection> results = new ArrayList<>();
        for (Long id : candidateIds) {
            Artist artist = artistsById.get(id);
            // The "already in the catalog" filter -- see class Javadoc for why PENDING_REVIEW is
            // the one status allowed through.
            if (artist == null || artist.getStatus() != ArtistStatus.PENDING_REVIEW) {
                continue;
            }
            List<ConnectionPath> paths = pathsByCandidate.getOrDefault(id, List.of());
            if (paths.isEmpty()) {
                continue;
            }
            results.add(new DiscoveredConnection(id, artist.getName(), paths));
        }
        results.sort(Comparator.comparing(DiscoveredConnection::artistName, String.CASE_INSENSITIVE_ORDER));
        return results;
    }
}
