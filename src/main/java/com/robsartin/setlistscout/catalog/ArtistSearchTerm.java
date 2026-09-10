package com.robsartin.setlistscout.catalog;

/**
 * Turns a raw search box query into a SQL {@code LIKE} pattern matched against the stored
 * {@code normalized_name} column (issue #250).
 *
 * <p>Matching goes through {@link ArtistNameNormalizer}, not through {@code lower()} or a raw
 * {@code ILIKE} on {@code name}. That class is this app's one definition of name equality, and it
 * folds more than case: unicode dashes, curly quotes, a hyphen and a space as the same separator
 * (#266, so {@code "Foo - Bar"} finds {@code Foo-Bar}), a leading definite article (#267), and a
 * Latin diacritic (#268, so {@code beyonce} finds {@code Beyoncé} -- which it could not before,
 * leaving the artist unreachable to anyone without an accented keyboard). Approximating any of that
 * in SQL is exactly the drift that turned 3 real duplicate pairs into a false 13 in #118 -- there is
 * one definition of "same name" and this is it.
 *
 * <p>Wildcards are escaped because {@code normalize} deliberately preserves {@code %} and
 * {@code _}: it only folds case, whitespace and specific punctuation. Without escaping, a search
 * for {@code 50%} would match every artist whose name merely starts with "50", and {@code a_b}
 * would match {@code a1b} -- a silently wrong result set rather than an error. The backslash is
 * escaped first, so a query containing one cannot smuggle in an escape sequence of its own.
 */
public final class ArtistSearchTerm {

    /** The escape character the repository's {@code LIKE ... ESCAPE} clause must name. */
    public static final String ESCAPE = "\\";

    private ArtistSearchTerm() {
    }

    /**
     * @return true when {@code raw} asks for a search at all. A blank or absent query means the
     * plain paged list, not a search for the empty string.
     */
    public static boolean isSearch(String raw) {
        return raw != null && !raw.isBlank();
    }

    /**
     * @return a substring {@code LIKE} pattern in {@link ArtistNameNormalizer}'s form. Substring
     * rather than prefix so "petty" finds "Tom Petty" -- the list is already name-ordered, so a
     * prefix match would be nearly redundant with jumping to a letter.
     */
    public static String likePattern(String raw) {
        String normalized = ArtistNameNormalizer.normalize(raw);
        String escaped = normalized
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
