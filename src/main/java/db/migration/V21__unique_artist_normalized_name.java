package db.migration;

import db.migration.support.DuplicateArtistMerger;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue #179: finishes what V19 (#176) deliberately left undone -- promotes
 * {@code artist.normalized_name}'s plain index to {@code UNIQUE (owner, normalized_name)}, after
 * merging the duplicate rows that would otherwise block it.
 *
 * <p>V19 shipped the column and a NON-unique index because the whole performance fix needed
 * nothing more, and a unique constraint needs a careful merge first (repointing
 * {@code artist_edge}/{@code scan_job}/{@code expand_job} before deleting anything). With the
 * constraint in place, the #118 duplicate-variant guard and the #133 insert race stop being
 * application-layer concerns: {@code ArtistSeedService#addSeedIfNew} drops its read-then-write
 * pre-check entirely and relies on {@code ON CONFLICT (owner, normalized_name) DO NOTHING}, so a
 * duplicate variant is impossible rather than compensated for after the fact.
 *
 * <h2>Why a second merge, when V13 already merged</h2>
 * V13 (#123) ran on 2026-08-14 and used the normalizer as it stood then. #157 subsequently taught
 * {@code ArtistNameNormalizer} to fold whitespace touching a hyphen, which made two rows that V13
 * had correctly left alone into duplicates of each other. A from-scratch database never sees this
 * (V13 runs with today's normalizer, so it catches the pair itself and this migration finds
 * nothing); only the live database, where V13 ran with the older rules, still carries it.
 *
 * <h2>Expected effect on production (read-only profiled 2026-08-18)</h2>
 * Exactly ONE colliding group exists, for owner {@code rob.sartin@gmail.com}, normalized key
 * {@code "paul quinichette-john coltrane quintet"}:
 * <ul>
 *   <li>id 5601 {@code "Paul Quinichette-John Coltrane Quintet"} REJECTED, created 2026-08-14 --
 *       the survivor (both rows are REJECTED, so the status rank ties and the older
 *       {@code created_at} wins; it is also the lower id).</li>
 *   <li>id 5942 {@code "Paul Quinichette - John Coltrane Quintet"} REJECTED, created 2026-08-15 --
 *       deleted.</li>
 * </ul>
 * Acceptance criteria, as row counts:
 * <ul>
 *   <li>{@code artist}: 15,453 &rarr; 15,452 (exactly one row deleted, and it is REJECTED, so no
 *       artist the owner tracks disappears and no rejected artist is resurrected).</li>
 *   <li>{@code artist_edge}: 24,496 &rarr; 24,496 (unchanged). Three edges touch the pair -- id
 *       1369 (3127&rarr;5601, MEMBER_EXPANSION/discogs), id 6628 (5493&rarr;5942,
 *       SIMILAR_EXPANSION/lastfm) and id 15685 (5944&rarr;5942, SIMILAR_EXPANSION/lastfm). The two
 *       pointing at 5942 are repointed to 5601. Neither becomes a self-loop (5493 and 5944 are not
 *       the survivor) and neither collides with an existing edge under {@code artist_edge_unique}
 *       (they keep distinct {@code from_artist_id}s), so nothing is collapsed or deleted: 5942's
 *       last referencing rows are repointed BEFORE it is deleted, which {@code
 *       artist_edge_to_fkey} would hard-fail on if the ordering were ever wrong.</li>
 *   <li>{@code scan_job} and {@code expand_job}: unchanged -- zero rows reference either id.</li>
 *   <li>Every other owner: untouched. No other {@code (owner, normalized_name)} group has more
 *       than one row.</li>
 * </ul>
 *
 * <h2>Failing loudly</h2>
 * A migration that throws means the app refuses to boot, so {@link #verifyNoCollisions} runs after
 * the merge and, if anything is somehow still colliding, names every offending row -- owner,
 * normalized key, and each row's id, name and status -- rather than letting the {@code ALTER TABLE}
 * fail with Postgres's own message, which reports only the first duplicate key value and no ids.
 * An unresolvable collision is a state to fix by hand, and it cannot be fixed without knowing which
 * rows it is.
 */
public class V21__unique_artist_normalized_name extends BaseJavaMigration {

    /** Named to match V1's {@code artist_owner_name_key}, the constraint this one supersedes in strength. */
    static final String UNIQUE_CONSTRAINT = "artist_owner_normalized_name_key";

    /** V19's non-unique index, made redundant by the constraint above (same columns, same order). */
    static final String SUPERSEDED_INDEX = "idx_artist_owner_normalized_name";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        // Keyed on the STORED column, not a freshly recomputed one: that is the literal pair the
        // constraint below compares, so the merge and the constraint can never disagree.
        DuplicateArtistMerger.merge(connection, DuplicateArtistMerger.GroupKey.STORED_NORMALIZED_NAME);
        verifyNoCollisions(connection);

        if (!uniqueConstraintExists(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE artist ADD CONSTRAINT " + UNIQUE_CONSTRAINT
                        + " UNIQUE (owner, normalized_name)");
            }
        }

        // Only after the unique constraint is in place: it indexes the same columns in the same
        // order, so every owner-scoped normalized-name lookup stays indexed, and if the ALTER above
        // had failed this migration's whole transaction would roll back with V19's index intact.
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS " + SUPERSEDED_INDEX);
        }
    }

    /**
     * Throws naming every remaining {@code (owner, normalized_name)} collision, or returns quietly
     * when there are none.
     * <p>
     * Public rather than private so a test can drive it directly against a database that still has
     * collisions -- the merge above resolves every collision it can see, so the only way to prove
     * this guard reports what it should is to call it before the merge runs (same reason
     * {@code V13__merge_duplicate_variant_artists#merge} is public).
     */
    public static void verifyNoCollisions(Connection connection) throws SQLException {
        List<String> collisions = findCollisions(connection);
        if (collisions.isEmpty()) {
            return;
        }
        throw new FlywayException(
                "artist rows still collide on (owner, normalized_name) after the merge, so UNIQUE ("
                        + "owner, normalized_name) cannot be added. Resolve these by hand and re-deploy:\n"
                        + String.join("\n", collisions));
    }

    /** One line per colliding group: the owner, the normalized key, and each row's id/name/status. */
    public static List<String> findCollisions(Connection connection) throws SQLException {
        String sql = """
                SELECT owner,
                       normalized_name,
                       string_agg(id || ' "' || name || '" ' || status, ', ' ORDER BY id) AS rows
                  FROM artist
                 GROUP BY owner, normalized_name
                HAVING count(*) > 1
                 ORDER BY owner, normalized_name
                """;
        List<String> collisions = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                collisions.add("  owner=" + rs.getString("owner")
                        + " normalized_name=\"" + rs.getString("normalized_name")
                        + "\" rows=[" + rs.getString("rows") + "]");
            }
        }
        return collisions;
    }

    /**
     * Looks the constraint up by COLUMN SIGNATURE rather than by name, the same technique V15 uses:
     * makes this re-runnable against a database that already has an equivalent constraint under a
     * different (e.g. Hibernate-generated) name, instead of failing on a duplicate.
     */
    private static boolean uniqueConstraintExists(Connection connection) throws SQLException {
        String sql = """
                SELECT count(*)
                  FROM pg_constraint con
                 WHERE con.contype = 'u'
                   AND con.conrelid = 'artist'::regclass
                   AND (
                       SELECT array_agg(attr.attname::text ORDER BY attr.attname)
                         FROM pg_attribute attr
                        WHERE attr.attrelid = con.conrelid
                          AND attr.attnum = ANY (con.conkey)
                   ) = ARRAY['normalized_name', 'owner']::text[]
                """;
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
