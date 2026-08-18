package db.migration.support;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;

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
 * Merges {@code artist} rows that are duplicates of each other under the app's one definition of
 * "same name", repointing every referencing row before deleting the losers.
 *
 * <p>Extracted verbatim (issue #179) from {@code db.migration.V13__merge_duplicate_variant_artists}
 * (issue #123), which is now a thin caller, so that
 * {@code db.migration.V21__unique_artist_normalized_name} can reuse the same careful logic instead
 * of hand-rolling a second merge. The two callers differ in exactly one respect -- how a row's
 * duplicate-group key is derived, see {@link GroupKey} -- and in nothing else.
 *
 * <h2>Survivor selection (per group, scoped to one owner)</h2>
 * <ol>
 *   <li>Highest status rank wins, see {@link #statusRank(String)}: {@code REJECTED} &gt;
 *       {@code REMOVED} &gt; {@code APPROVED}/{@code SEED} (tied) &gt; {@code PENDING_REVIEW}.
 *       This guarantees that if ANY row in a group is {@code REJECTED}, the surviving row is
 *       REJECTED too -- the rank comparison always prefers a REJECTED row over a non-REJECTED one,
 *       so the group's survivor can only be a non-REJECTED row when no REJECTED row exists in the
 *       group. Resurrecting a rejected artist under a merge is exactly the bug #118 fixed; this
 *       must not reintroduce it.</li>
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
 * <p>Idempotent: re-running {@link #merge} against an already-merged database finds no group with
 * more than one row per (owner, key) and does nothing.
 */
public final class DuplicateArtistMerger {

    private DuplicateArtistMerger() {
    }

    /**
     * Where a row's duplicate-group key comes from. The two Flyway migrations that merge duplicates
     * run at different points in the schema's history and therefore cannot both read the same
     * thing -- this is the ONLY difference between them.
     */
    public enum GroupKey {
        /**
         * Normalize {@code artist.name} in Java, via {@link ArtistNameNormalizer#normalize(String)}.
         * What V13 (issue #123) uses, and all it can use: {@code artist.normalized_name} does not
         * exist yet when V13 runs -- V19 (issue #176) adds the column six migrations later.
         */
        NORMALIZE_NAME_IN_JAVA,

        /**
         * Read the stored {@code artist.normalized_name} column. What V21 (issue #179) uses,
         * because that is the literal column pair {@code UNIQUE (owner, normalized_name)} compares:
         * grouping by a freshly recomputed key instead could miss a collision the constraint would
         * then reject, turning a merge that "succeeded" into a failed {@code ALTER TABLE} and an
         * app that refuses to boot.
         */
        STORED_NORMALIZED_NAME
    }

    /**
     * Merge every duplicate group in {@code artist}, keyed per {@code groupKey}.
     *
     * <p>Exposed as a standalone static method (rather than only reachable through a migration's
     * {@code migrate}) so a test can invoke it a second time directly against the same connection
     * to prove idempotency -- Flyway itself will never re-run an already-applied versioned
     * migration, so that has to be exercised out-of-band.
     */
    public static void merge(Connection conn, GroupKey groupKey) throws SQLException {
        for (ArtistGroup group : loadDuplicateGroups(conn, groupKey)) {
            mergeGroup(conn, group);
        }
    }

    // ---- grouping ----

    private static List<ArtistGroup> loadDuplicateGroups(Connection conn, GroupKey groupKey) throws SQLException {
        boolean stored = groupKey == GroupKey.STORED_NORMALIZED_NAME;
        String sql = stored
                ? "SELECT id, owner, name, normalized_name, status, created_at FROM artist ORDER BY owner, id"
                : "SELECT id, owner, name, status, created_at FROM artist ORDER BY owner, id";
        Map<String, List<ArtistRow>> byKey = new LinkedHashMap<>();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                ArtistRow row = new ArtistRow(
                        rs.getLong("id"),
                        rs.getString("owner"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant());
                String key = rs.getString("owner") + "\0" + (stored
                        ? rs.getString("normalized_name")
                        : ArtistNameNormalizer.normalize(rs.getString("name")));
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

    /**
     * Rank order for survivor selection. Only the RELATIVE order matters, and it is unchanged from
     * V13's original for every status V13 can possibly see: {@code REJECTED} &gt;
     * {@code APPROVED}/{@code SEED} &gt; {@code PENDING_REVIEW} &gt; anything else.
     *
     * <p>{@code REMOVED} is new here (issue #179) and cannot change V13's behaviour: {@code REMOVED}
     * is added to {@code artist_status_check} by V14, one migration AFTER V13, so no row V13 ever
     * sees can carry it. V21 runs long after V14 and can, which is why it is ranked deliberately
     * rather than falling through to the {@code default}. It sits just under {@code REJECTED} and
     * above the active statuses for the same reason {@code REJECTED} outranks them: {@code REMOVED}
     * is the owner explicitly taking an artist off their list (see {@code ArtistStatus#REMOVED}),
     * and silently resurrecting it by merging into an active row would be the same class of bug as
     * resurrecting a rejected one. The merged survivor keeps every edge and job the loser had, so
     * nothing is lost by keeping the deactivated row -- and re-adding the name reactivates it
     * (see {@code ArtistSeedService#addSeedIfNew}). Profiled read-only against prod 2026-08-18:
     * zero {@code REMOVED} rows exist and the single remaining duplicate group is REJECTED/REJECTED,
     * so this ranking is a guard against a state that does not exist today, not a live behaviour.
     */
    private static int statusRank(String status) {
        return switch (status) {
            case "REJECTED" -> 4;
            case "REMOVED" -> 3;
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
