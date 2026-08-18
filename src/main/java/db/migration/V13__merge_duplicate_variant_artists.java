package db.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import db.migration.support.DuplicateArtistMerger;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Issue #123: merges the 27 duplicate-variant {@code artist} groups (54 rows, 27 to be deleted;
 * read-only profiled against prod 2026-08-14) already live in prod --
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
 * (5,001 artists, 27 groups as profiled 2026-08-14) one-time cleanup.
 *
 * <h2>Where the merge logic lives</h2>
 * In {@link DuplicateArtistMerger} -- survivor selection, {@code artist_edge}/{@code scan_job}/
 * {@code expand_job} repointing, duplicate-edge collapse, self-loop deletion, and the
 * delete-losers-last ordering are all documented there. It was extracted out of this class
 * unchanged by issue #179 so {@code V21__unique_artist_normalized_name} could reuse it rather than
 * hand-roll a second merge before adding {@code UNIQUE (owner, normalized_name)}.
 *
 * <p>This migration keeps {@link DuplicateArtistMerger.GroupKey#NORMALIZE_NAME_IN_JAVA}, the only
 * key it can use: {@code artist.normalized_name} does not exist yet at V13 -- V19 adds it.
 */
public class V13__merge_duplicate_variant_artists extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        merge(context.getConnection());
    }

    /**
     * The actual merge, exposed as a standalone static method (rather than only reachable through
     * {@link #migrate(Context)}) so a test can invoke it a second time directly against the same
     * connection to prove idempotency -- Flyway itself will never re-run an already-applied
     * versioned migration, so that has to be exercised out-of-band.
     */
    public static void merge(Connection conn) throws SQLException {
        DuplicateArtistMerger.merge(conn, DuplicateArtistMerger.GroupKey.NORMALIZE_NAME_IN_JAVA);
    }
}
