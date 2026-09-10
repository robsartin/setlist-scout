package com.robsartin.setlistscout.catalog;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

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
     * @return the match form of {@code name}: unicode dashes folded to {@code -} and every hyphen
     * then folded to a space (so {@code "X-Y"}, {@code "X - Y"} and {@code "X Y"} all reach the
     * same form, issue #266, subsuming #157), trimmed with internal whitespace collapsed to a
     * single space, curly quotes folded to straight quotes, lowercased with {@link Locale#ROOT}
     * (not the default locale, so this is stable across JVMs/deployments regardless of the
     * server's locale -- avoids the Turkish-I class of bugs), and finally a leading definite
     * article dropped when the name has another word after it (issue #267).
     */
    /**
     * Latin letters whose diacritic is a STROKE through the glyph rather than a mark beside it.
     * These have no canonical decomposition, so {@link Normalizer.Form#NFD} leaves them whole and
     * the mark-dropping loop below never sees them -- {@code Michał} would keep its {@code ł} and
     * never meet {@code Michal}. Mapped explicitly, before decomposition.
     *
     * <p>The same list Rob's segue project arrived at independently in
     * {@code com.robsartin.segue.seed.Names}, for the same reason.
     */
    private static final Map<Character, Character> STROKE_LETTERS = Map.of(
            'ł', 'l', 'Ł', 'L',
            'ø', 'o', 'Ø', 'O',
            'đ', 'd', 'Đ', 'D',
            'ħ', 'h', 'Ħ', 'H');

    /**
     * Issue #268: fold a Latin diacritic into its base letter, so {@code Céline Dion} and
     * {@code Celine Dion} are one artist (44 such groups existed in production, 8 of them with both
     * rows active) and so {@code /artists?q=beyonce} finds {@code Beyoncé} -- today it finds
     * nothing, and anyone without an accented keyboard cannot reach the artist at all.
     *
     * <h2>Why this is NOT the ASCII strip #118 banned</h2>
     * The class doc above is emphatic that stripping non-ASCII characters collapsed every all-Hebrew
     * and all-Japanese name to an empty, colliding key. That prohibition stands. This decomposes and
     * drops a combining mark, which is a different operation -- but only if it is applied with the
     * one restriction below, and the first implementation profiled for #268 got this wrong.
     *
     * <h2>The restriction: only when the base is an ASCII letter</h2>
     * Dropping <em>every</em> {@link Character#NON_SPACING_MARK} corrupts Japanese. Katakana encodes
     * a voiced sound as base + U+3099/U+309A (dakuten/handakuten), both of which ARE non-spacing
     * marks: {@code バ} is {@code ハ} + U+3099. An unconditional drop rewrites the word rather than
     * removing an accent --
     * <pre>
     *   ミドリ (Midori, the violinist)  ->  ミトリ (Mitori)
     *   ガンダム (Gundam)               ->  カンタム (Kantamu)
     * </pre>
     * -- and it corrupted 9 real catalog rows when measured. Tracking whether the current base is an
     * ASCII letter confines the fold to Latin script: {@code é} loses its acute, {@code バ} keeps its
     * dakuten, and Greek, Cyrillic, Hebrew, Arabic and CJK are never touched.
     *
     * <h2>Why it recomposes at the end</h2>
     * Hangul decomposes into conjoining jamo, which are NOT marks and so survive the filter -- but
     * the key would silently change from 3 precomposed characters to 8 decomposed ones, and no
     * Korean keyboard produces that form. {@code 김지현} would become unfindable: the same complaint
     * this issue exists to fix, inverted. The closing {@link Normalizer.Form#NFC} restores it, and
     * as a bonus canonicalizes the handful of rows already stored decomposed.
     *
     * <h2>NFD, not NFKD</h2>
     * Measured across all 38,666 production rows, NFD and NFKD produce identical keys except for two
     * ({@code Challenge™} -> {@code ChallengeTM}, {@code Ten…} -> {@code Ten...}), neither of which
     * merges with anything. The compatibility mappings therefore buy nothing here while widening the
     * transformation, so this uses canonical decomposition only.
     *
     * <p>Deliberately does NOT fold {@code ß}, {@code æ} or {@code œ}: NFD does not decompose them,
     * none of the five catalog names carrying one has a duplicate, and inventing
     * {@code ß}&#8594;{@code ss} would be a word substitution rather than a diacritic fold -- the
     * same line this class already refuses to cross for {@code and}/{@code &}.
     */
    private static String foldDiacritics(String value) {
        StringBuilder mapped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            mapped.append(STROKE_LETTERS.getOrDefault(c, c));
        }

        String decomposed = Normalizer.normalize(mapped, Normalizer.Form.NFD);
        StringBuilder folded = new StringBuilder(decomposed.length());
        boolean baseIsAsciiLetter = false;
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK) {
                if (!baseIsAsciiLetter) {
                    folded.appendCodePoint(cp);
                }
            } else {
                baseIsAsciiLetter = cp < 128 && Character.isLetter(cp);
                folded.appendCodePoint(cp);
            }
        }
        return Normalizer.normalize(folded, Normalizer.Form.NFC);
    }

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
        result = foldDiacritics(result);

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
        String lower = result.toLowerCase(Locale.ROOT);

        // Issue #267: drop a leading definite article. "The Grateful Dead" and "Grateful Dead" are
        // one band, and 132 such groups existed in production. Rob's segue project reached the same
        // conclusion independently for the same reason.
        //
        // Three deliberate narrowings, each pinned by a test:
        //
        //  1. Matches the WORD "the" plus its separator, never a four-character prefix. 56 names in
        //     production begin with "the" and no space -- Thee Oh Sees, TheEllenShow, Theatre Of
        //     Eternal Music, Thelonious Monk -- and truncating those would invent new artists.
        //  2. Only a STRING-INITIAL article. "Bruce Springsteen & The E Street Band" keeps its
        //     "The"; stripping internal ones would merge it with a different billing.
        //  3. Only when a word REMAINS. "The Beat"/"BEAT", "The Smile"/"Smile" and "The Herd"/"Herd"
        //     are plausibly different acts, so single-word names keep their article. 29 such groups
        //     exist and are deliberately left unmerged -- unlike #266's 18 pairs, they could not be
        //     verified one by one. Widening this later is a decision with its own evidence, not a
        //     tidy-up.
        //
        // A name that is only "the" therefore survives untouched rather than normalizing to empty,
        // which would collide every such row onto one key.
        if (lower.startsWith("the ")) {
            String withoutArticle = lower.substring(4).trim();
            if (withoutArticle.contains(" ")) {
                return withoutArticle;
            }
        }
        return lower;
    }
}
