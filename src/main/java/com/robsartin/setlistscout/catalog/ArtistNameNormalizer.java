package com.robsartin.setlistscout.catalog;

import java.util.Locale;

/**
 * Single source of truth for "do these two spellings refer to the same artist" -- the match form
 * used by {@link ArtistNameMatcher}, and, since #179, the form the database itself is unique on:
 * every artist row stores this method's output in {@code normalized_name} under
 * {@code UNIQUE (owner, normalized_name)}. That is what makes case/punctuation variants a
 * constraint violation rather than a second row, which the DB's original {@code (owner, name)}
 * uniqueness (case- and punctuation-SENSITIVE, see
 * {@code ArtistRepositoryTest#caseVariantIsAbsorbedByTheNormalizedNameConstraint}) let through
 * (issue #118: a rejected artist reappearing under a slightly different spelling).
 * <p>
 * Deliberately conservative: only folds case, treats a hyphen and a space as the same separator
 * (issue #266, which subsumes #157 -- {@code "X - Y"}, {@code "X-Y"} and {@code "X Y"} all match),
 * and maps the specific
 * unicode punctuation variants seen in the wild (unicode hyphens and en/em dashes to the ASCII
 * hyphen, curly quotes to straight)
 * -- nothing that would merge genuinely different names ("AC/DC" and "ACDC" stay distinct, and a
 * word substitution like "and" vs "&" still does NOT match; this is not a fuzzy-match/edit-distance
 * comparison). Explicitly NOT ASCII-stripping: the issue's own first profiling pass stripped
 * non-ASCII characters and collapsed every all-Hebrew or all-Japanese name to an empty string,
 * inflating a true count of 3 pairs to a false 13 -- this normalizer must preserve non-ASCII text
 * so two different non-Latin names never collide.
 * <p>
 * Public (not package-private): also reused by the Flyway Java migrations -- {@code
 * db.migration.V13__merge_duplicate_variant_artists} (issue #123), which merged the duplicate-variant
 * artist groups already live in prod, and {@code db.migration.V19__add_artist_normalized_name}
 * (#176), which backfills the stored column. Those deliberately call this same method rather than
 * re-implementing the folding rules in SQL, so there is exactly one definition of "same name" for
 * the live guard, the stored column, and the historical cleanup alike.
 */
public final class ArtistNameNormalizer {

    private ArtistNameNormalizer() {
    }

    /**
     * @return the match form of {@code name}: trimmed, internal whitespace collapsed to a single
     * space, unicode dashes folded to {@code -} and then any whitespace touching a hyphen removed
     * (so {@code "X - Y"} and {@code "X-Y"} reach the same form, issue #157), curly quotes folded
     * to straight quotes, lowercased with {@link Locale#ROOT} (not the default locale, so this is
     * stable across JVMs/deployments regardless of the server's locale -- avoids the Turkish-I
     * class of bugs).
     */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String result = name
                // hyphen, non-breaking hyphen, en dash, em dash, horizontal bar, minus sign
                // -> hyphen-minus. U+2010/U+2011 were missing until #261: 33 duplicate artist
                // pairs existed in production purely because they never folded, 10 of them with
                // both rows active and therefore scanned twice.
                .replace('\u2010', '-')
                .replace('\u2011', '-')
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
        // Issue #266: a hyphen and a space are the same SEPARATOR. "Yo-Yo Ma" and "Yo Yo Ma",
        // "Jean-Michel Jarre" and "Jean Michel Jarre" are one artist each; 18 such pairs existed in
        // production, six of them active rows that were really three artists being scanned twice.
        //
        // Folded to a space rather than to a hyphen so the stored normalized_name stays readable
        // ("yo yo ma", not "yo-yo-ma") -- it is what #250's search matches against and what a
        // human reads when diagnosing a match.
        //
        // This runs BEFORE the whitespace collapse below, which is what makes #157 fall out for
        // free: "X - Y" becomes "X   Y" and collapses to "X Y", the same form "X-Y" reaches. That
        // is why #157's own \s*-\s* regex is gone -- after this line there are no hyphens left
        // for it to match, so keeping it would be dead code, not a safety net.
        //
        // Deliberately a SUBSTITUTION, not a deletion: the token boundary survives, so "AC/DC"
        // still cannot collapse into "ACDC" and "YoYo Ma" stays distinct from "Yo-Yo Ma". That
        // distinction is the class's conservative philosophy and ArtistNameNormalizerTest pins it.
        result = result.replace('-', ' ');
        result = result.trim().replaceAll("\\s+", " ");
        return result.toLowerCase(Locale.ROOT);
    }
}
