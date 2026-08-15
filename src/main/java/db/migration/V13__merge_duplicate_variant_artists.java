package db.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue #123: merges the ~57 duplicate-variant {@code artist} groups already live in prod --
 * rows for the same {@code owner} whose {@code name} normalizes to the same match form under
 * {@link ArtistNameNormalizer} (case, whitespace, and unicode dash/quote folding), but were never
 * caught at insert time because {@code artist(owner, name)}'s unique constraint is an exact-string
 * match. #118's {@code ArtistNameMatcher} guard stops the set from growing further; this cleans up
 * what already accumulated before that guard existed.
 *
 * <h2>Why a Java migration, not SQL</h2>
 * {@link ArtistNameNormalizer#normalize(String)} folds unicode dashes/quotes with explicit
 * character-by-character {@code replace} calls and preserves non-ASCII text (see its javadoc: an
 * earlier, ASCII-stripping normalization collapsed every all-Hebrew/all-Japanese name to the same
 * empty key). Reproducing that exactly in plain SQL would mean a second, hand-rolled
 * normalization -- e.g. a chain of {@code regexp_replace}/{@code translate} calls -- that could
 * silently drift from the Java implementation over time (the same drift that inflated the #118
 * issue's own first live-profiling pass from 3 real pairs to a false 13). Calling the real
 * normalizer from a Flyway {@link BaseJavaMigration} guarantees one definition of "same name" for
 * both the live app-layer guard and this one-time historical cleanup, at the cost of the migration
 * living in Java instead of a portable .sql file -- an acceptable tradeoff for a single-owner-scale
 * (~4,600 artists, ~57 groups) one-time cleanup.
 *
 * <h2>Survivor selection (per normalized-name group, scoped to one owner)</h2>
 * <ol>
 *   <li>Highest status rank wins: {@code REJECTED} (3) &gt; {@code APPROVED}/{@code SEED} (2, tied)
 *       &gt; {@code PENDING_REVIEW} (1). This guarantees that if ANY row in a group is
 *       {@code REJECTED}, the surviving row is REJECTED too -- the rank comparison always prefers a
 *       REJECTED row over a non-REJECTED one, so the group's survivor can only be a
 *       non-REJECTED row when no REJECTED row exists in the group. Resurrecting a rejected artist
 *       under this migration is exactly the bug #118 fixed; this migration must not reintroduce it.</li>
 *   <li>Tie-break: oldest {@code created_at} (the original discovery survives).</li>
 *   <li>Final tie-break: lowest {@code id}.</li>
 * </ol>
 *
 * <h2>Repointing (per group, applied to every loser)</h2>
 * <ul>
 *   <li>{@code artist_edge.from_artist_id}/{@code to_artist_id}: repointed to the survivor. Any
 *       edge that would become a duplicate under {@code artist_edge_unique
 *       (owner, from_artist_id, to_artist_id, type, source)} after repointing is collapsed -- the
 *       earliest-created (by {@code created_at}, then {@code id}) survives, the rest are deleted.
 *       An edge whose {@code from}/{@code to} both collapse to the survivor (a would-be self-loop)
 *       is deleted outright, never repointed.</li>
 *   <li>{@code scan_job}/{@code expand_job.artist_id}: repointed to the survivor. Both are unique on
 *       {@code (owner, artist_id, source)}, so per {@code source}: if the survivor already has its
 *       own row for that source, it is kept and every loser row for that source is deleted (the
 *       survivor's own schedule wins over a duplicate's). Otherwise, the loser row with the
 *       earliest {@code next_due_at} (tie: lowest {@code id}) is repointed to the survivor and kept;
 *       the rest are deleted.</li>
 *   <li>{@code show_event} references the artist by {@code artist_name} (a plain string column, no
 *       FK to {@code artist.id} -- see {@code V1__baseline.sql}), so nothing to repoint there.</li>
 * </ul>
 *
 * <p>Loser {@code artist} rows are deleted only after every referencing row has been repointed or
 * removed, so no {@code artist_edge}/{@code scan_job}/{@code expand_job} row is ever left pointing
 * at a deleted {@code artist.id}.
 *
 * <p>Idempotent: re-running {@link #merge(Connection)} against an already-merged database finds no
 * group with more than one row per (owner, normalized name) and does nothing.
 */
public class V13__merge_duplicate_variant_artists extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        merge(context.getConnection());
    }

    /**
     * The actual merge logic, exposed as a standalone static method (rather than only reachable
     * through {@link #migrate(Context)}) so a test can invoke it a second time directly against the
     * same connection to prove idempotency -- Flyway itself will never re-run an already-applied
     * versioned migration, so that has to be exercised out-of-band.
     */
    public static void merge(Connection conn) throws SQLException {
        for (ArtistGroup group : loadDuplicateGroups(conn)) {
            mergeGroup(conn, group);
        }
    }

    // ---- grouping ----

    private static List<ArtistGroup> loadDuplicateGroups(Connection conn) throws SQLException {
        Map<String, List<ArtistRow>> byKey = new LinkedHashMap<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, owner, name, status, created_at FROM artist ORDER BY owner, id")) {
            while (rs.next()) {
                ArtistRow row = new ArtistRow(
                        rs.getLong("id"),
                        rs.getString("owner"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant());
                String key = rs.getString("owner") + "\0" + ArtistNameNormalizer.normalize(rs.getString("name"));
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        List<ArtistGroup> groups = new ArrayList<>();
        for (List<ArtistRow> rows : byKey.values()) {
            if (rows.size() > 1) {
                groups.add(new ArtistGroup(rows));
            }
        }
        return groups;
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "REJECTED" -> 3;
            case "APPROVED", "SEED" -> 2;
            case "PENDING_REVIEW" -> 1;
            default -> 0;
        };
    }

    // ---- per-group merge ----

    private static void mergeGroup(Connection conn, ArtistGroup group) throws SQLException {
        collapseArtistEdges(conn, group);
        collapseJobs(conn, "scan_job", group);
        collapseJobs(conn, "expand_job", group);

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM artist WHERE id = ?")) {
            for (Long loserId : group.loserIds()) {
                ps.setLong(1, loserId);
                ps.executeUpdate();
            }
        }
    }

    // ---- artist_edge repoint + collapse ----

    private static void collapseArtistEdges(Connection conn, ArtistGroup group) throws SQLException {
        List<EdgeRow> edges = loadEdgesTouching(conn, group.owner, group.allIds());
        Map<String, EdgeRow> keepers = new LinkedHashMap<>();
        List<Long> toDelete = new ArrayList<>();

        for (EdgeRow e : edges) {
            long newFrom = group.remap(e.fromId);
            long newTo = group.remap(e.toId);
            if (newFrom == newTo) {
                // Both endpoints collapsed onto the survivor -- a would-be self-loop. Drop it.
                toDelete.add(e.id);
                continue;
            }
            String key = newFrom + "|" + newTo + "|" + e.type + "|" + e.source;
            EdgeRow existing = keepers.get(key);
            if (existing == null) {
                keepers.put(key, new EdgeRow(e.id, newFrom, newTo, e.type, e.source));
            } else {
                // Rows were loaded ordered by created_at then id, so `existing` is the earlier one.
                toDelete.add(e.id);
            }
        }

        deleteByIds(conn, "artist_edge", toDelete);
        for (EdgeRow keeper : keepers.values()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE artist_edge SET from_artist_id = ?, to_artist_id = ? WHERE id = ?")) {
                ps.setLong(1, keeper.fromId);
                ps.setLong(2, keeper.toId);
                ps.setLong(3, keeper.id);
                ps.executeUpdate();
            }
        }
    }

    private static List<EdgeRow> loadEdgesTouching(Connection conn, String owner, List<Long> ids)
            throws SQLException {
        String placeholders = placeholders(ids.size());
        String sql = "SELECT id, from_artist_id, to_artist_id, type, source FROM artist_edge "
                + "WHERE owner = ? AND (from_artist_id IN (" + placeholders + ") "
                + "OR to_artist_id IN (" + placeholders + ")) ORDER BY created_at, id";
        List<EdgeRow> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, owner);
            for (Long id : ids) {
                ps.setLong(idx++, id);
            }
            for (Long id : ids) {
                ps.setLong(idx++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new EdgeRow(rs.getLong("id"), rs.getLong("from_artist_id"),
                            rs.getLong("to_artist_id"), rs.getString("type"), rs.getString("source")));
                }
            }
        }
        return result;
    }

    // ---- scan_job / expand_job repoint + collapse ----

    private static void collapseJobs(Connection conn, String table, ArtistGroup group) throws SQLException {
        List<JobRow> rows = loadJobsTouching(conn, table, group.owner, group.allIds());
        Map<String, List<JobRow>> bySource = new LinkedHashMap<>();
        for (JobRow r : rows) {
            bySource.computeIfAbsent(r.source, k -> new ArrayList<>()).add(r);
        }

        for (List<JobRow> sourceGroup : bySource.values()) {
            JobRow keeper = sourceGroup.stream()
                    .filter(r -> r.artistId == group.survivor.id)
                    .findFirst()
                    .orElseGet(() -> sourceGroup.stream()
                            .min(Comparator.comparing((JobRow r) -> r.nextDueAt).thenComparing(r -> r.id))
                            .orElseThrow());

            for (JobRow r : sourceGroup) {
                if (r.id != keeper.id) {
                    deleteByIds(conn, table, List.of(r.id));
                }
            }
            if (keeper.artistId != group.survivor.id) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + table + " SET artist_id = ? WHERE id = ?")) {
                    ps.setLong(1, group.survivor.id);
                    ps.setLong(2, keeper.id);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static List<JobRow> loadJobsTouching(Connection conn, String table, String owner, List<Long> ids)
            throws SQLException {
        String sql = "SELECT id, artist_id, source, next_due_at FROM " + table
                + " WHERE owner = ? AND artist_id IN (" + placeholders(ids.size()) + ")";
        List<JobRow> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, owner);
            for (Long id : ids) {
                ps.setLong(idx++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp nextDueAt = rs.getTimestamp("next_due_at");
                    result.add(new JobRow(rs.getLong("id"), rs.getLong("artist_id"), rs.getString("source"),
                            nextDueAt == null ? Instant.MAX : nextDueAt.toInstant()));
                }
            }
        }
        return result;
    }

    // ---- shared helpers ----

    private static void deleteByIds(Connection conn, String table, List<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            for (Long id : ids) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }

    // ---- value types ----

    private record ArtistRow(long id, String owner, String status, Instant createdAt) {
    }

    private record EdgeRow(long id, long fromId, long toId, String type, String source) {
    }

    private record JobRow(long id, long artistId, String source, Instant nextDueAt) {
    }

    private static final class ArtistGroup {
        final String owner;
        final ArtistRow survivor;
        final List<ArtistRow> losers;

        ArtistGroup(List<ArtistRow> rows) {
            this.owner = rows.get(0).owner;
            Comparator<ArtistRow> survivorOrder = Comparator
                    .comparingInt((ArtistRow r) -> -statusRank(r.status))
                    .thenComparing(ArtistRow::createdAt)
                    .thenComparing(ArtistRow::id);
            this.survivor = rows.stream().min(survivorOrder).orElseThrow();
            this.losers = rows.stream().filter(r -> r.id != survivor.id).toList();
        }

        List<Long> loserIds() {
            return losers.stream().map(ArtistRow::id).toList();
        }

        List<Long> allIds() {
            List<Long> ids = new ArrayList<>(loserIds());
            ids.add(survivor.id);
            return ids;
        }

        /**
         * @return {@code survivor.id} if {@code id} is one of this group's losers, otherwise
         * {@code id} unchanged.
         */
        long remap(long id) {
            for (ArtistRow loser : losers) {
                if (loser.id == id) {
                    return survivor.id;
                }
            }
            return id;
        }
    }
}
