package com.robsartin.setlistscout.catalog;

import java.util.Locale;

/**
 * Single source of truth for "do these two spellings refer to the same artist" -- the match form
 * used by {@link ArtistNameMatcher} to catch case/punctuation variants that the DB's {@code
 * (owner, name)} unique constraint (case- and punctuation-SENSITIVE, see {@code
 * ArtistRepositoryTest#uniqueConstraintIsCaseSensitive}) lets through as distinct rows (issue
 * #118: a rejected artist reappearing under a slightly different spelling).
 * <p>
 * Deliberately conservative: only folds case, collapses whitespace, and maps the specific unicode
 * punctuation variants seen in the wild (en/em dash to hyphen, curly quotes to straight) --
 * nothing that would merge genuinely different names ("AC/DC" and "ACDC" stay distinct; this is
 * not a fuzzy-match/edit-distance comparison). Explicitly NOT ASCII-stripping: the issue's own
 * first profiling pass stripped non-ASCII characters and collapsed every all-Hebrew or
 * all-Japanese name to an empty string, inflating a true count of 3 pairs to a false 13 -- this
 * normalizer must preserve non-ASCII text so two different non-Latin names never collide.
 * <p>
 * Public (not package-private): also reused by the {@code db.migration.V13__merge_duplicate_variant_artists}
 * Flyway Java migration (issue #123), which merges the ~57 duplicate-variant artist groups already
 * live in prod. That migration deliberately calls this same method rather than re-implementing the
 * folding rules in SQL, so there is exactly one definition of "same name" for both the live guard
 * and the historical cleanup.
 */
public final class ArtistNameNormalizer {

    private ArtistNameNormalizer() {
    }

    /**
     * @return the match form of {@code name}: trimmed, internal whitespace collapsed to a single
     * space, unicode dashes folded to {@code -}, curly quotes folded to straight quotes, lowercased
     * with {@link Locale#ROOT} (not the default locale, so this is stable across JVMs/deployments
     * regardless of the server's locale -- avoids the Turkish-I class of bugs).
     */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String result = name
                // en dash, em dash, horizontal bar, minus sign -> hyphen-minus
                .replace('–', '-')
                .replace('—', '-')
                .replace('―', '-')
                .replace('−', '-')
                // curly/typographic single quotes and primes -> straight apostrophe
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('‚', '\'')
                .replace('′', '\'')
                // curly/typographic double quotes -> straight double quote
                .replace('“', '"')
                .replace('”', '"')
                .replace('„', '"')
                .replace('″', '"');
        result = result.trim().replaceAll("\\s+", " ");
        return result.toLowerCase(Locale.ROOT);
    }
}
