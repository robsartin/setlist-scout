package db.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import db.migration.support.DuplicateArtistMerger;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Issue #267: {@link ArtistNameNormalizer} kept a leading definite article, so
 * {@code The Grateful Dead} and {@code Grateful Dead} were two artists and
 * {@code UNIQUE (owner, normalized_name)} admitted both.
 *
 * <p>Profiled read-only against production 2026-09-10: <b>161 groups across the catalog</b>, of
 * which <b>132 are multi-word and merged here</b>. 36 of those mix an inactive row with an active
 * one, which is why the survivor policy matters far more here than in #266 (2 such pairs).
 *
 * <h2>Why PREFER_ACTIVE, when a leading "The" is visible to the reviewer</h2>
 * {@link DuplicateArtistMerger.SurvivorPolicy#PREFER_ACTIVE} was introduced for #261, where the
 * duplicate characters were <em>invisible</em> — the owner could not have known they were rejecting
 * a row they had already approved. That argument does not transfer: "The Bill Evans Trio" and
 * "Bill Evans Trio" are plainly distinguishable on the review page. So the 36 mixed groups were
 * read individually before choosing, rather than assumed. Every one of them is a single act under
 * two billings — {@code Bill Evans Trio}/{@code The Bill Evans Trio},
 * {@code Marshall Tucker Band}/{@code The Marshall Tucker Band},
 * {@code Glenn Miller Orchestra}/{@code The Glenn Miller Orchestra} — with the active row
 * approved or hand-seeded and the article-variant rejected. The rejection is a judgement about the
 * <em>duplicate</em> ("I already have this one"), not about the artist. Honouring it would delete
 * the approved row and leave the catalog with no Bill Evans Trio at all, which inverts what the
 * owner actually decided. Hence PREFER_ACTIVE.
 *
 * <h2>The 29 single-word groups are deliberately NOT merged</h2>
 * {@code The Beat}/{@code BEAT}, {@code The Herd}/{@code Herd}, {@code The Smile}/{@code Smile} and
 * 26 others are plausibly <em>different acts</em> — the 2024 King Crimson-adjacent supergroup is not
 * the English Beat, and Peter Frampton's band is not the Australian hip-hop group. #266 merged 18
 * pairs every one of which could be verified; that is not true here, and 13 of these 29 involve an
 * active row where a wrong merge would delete a real artist. The normalizer therefore strips only
 * when a word remains, and {@code ArtistNameNormalizerTest} pins that so the rule cannot be widened
 * silently. Widening it later needs its own evidence.
 *
 * <h2>Order matters</h2>
 * Merge first, then re-backfill — the reverse makes both rows of every pair compute the same value
 * and violates the unique constraint before anything merges. Keyed off
 * {@link DuplicateArtistMerger.GroupKey#NORMALIZE_NAME_IN_JAVA}, which recomputes through the fixed
 * normalizer; the stored column would group by the old rule and find nothing.
 *
 * <h2>Acceptance criteria (production, 2026-09-10)</h2>
 * <pre>
 *   multi-word groups collapsed                  : 132
 *   artist rows deleted                          : 132
 *   single-word groups deliberately left alone   : 29  (The Beat / BEAT stay two rows)
 *   groups mixing an inactive and an active row  : 36  (PREFER_ACTIVE keeps the active one)
 *   groups with two or more active rows          : 33  (were being scanned twice)
 *   names normalizing to empty                   : 0
 *   Bruce Springsteen &amp; The E Street Band        : internal "The" retained
 * </pre>
 */
public class V35__strip_leading_article_and_merge_duplicates extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        DuplicateArtistMerger.merge(connection,
                DuplicateArtistMerger.GroupKey.NORMALIZE_NAME_IN_JAVA,
                DuplicateArtistMerger.SurvivorPolicy.PREFER_ACTIVE);

        // Re-normalize EVERY row: any name with a leading article has a stale normalized_name even
        // with no duplicate, and that column is what ArtistNameMatcher's indexed lookup and #250's
        // search compare against.
        try (PreparedStatement read = connection.prepareStatement("SELECT id, name FROM artist");
             PreparedStatement write = connection.prepareStatement(
                     "UPDATE artist SET normalized_name = ? WHERE id = ?");
             ResultSet rows = read.executeQuery()) {
            int batched = 0;
            while (rows.next()) {
                write.setString(1, ArtistNameNormalizer.normalize(rows.getString("name")));
                write.setLong(2, rows.getLong("id"));
                write.addBatch();
                if (++batched % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
        }
    }
}
