package com.robsartin.setlistscout.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link ArtistNameNormalizer}, the single source of truth for "do these two
 * spellings refer to the same artist" used by {@link ArtistNameMatcher} (issue #118: rejected
 * artists reappearing under a case/punctuation variant slip past the DB's case- and
 * punctuation-SENSITIVE {@code (owner, name)} unique constraint). Every pair here is one of the 3
 * confirmed live pairs from the issue, or a case the issue's own caveat called out (its first
 * profiling pass stripped non-ASCII and collapsed all-Hebrew/all-Japanese names to an empty
 * string -- this normalizer must NOT do that).
 */
class ArtistNameNormalizerTest {

    @Test
    @DisplayName("case differences collapse to the same match form")
    void caseDiffersCollapses() {
        assertThat(ArtistNameNormalizer.normalize("Charlie Parker's Re-Boppers"))
                .isEqualTo(ArtistNameNormalizer.normalize("Charlie Parker's Re-boppers"));
        assertThat(ArtistNameNormalizer.normalize("Walfredo de los Reyes, Jr."))
                .isEqualTo(ArtistNameNormalizer.normalize("Walfredo De Los Reyes, Jr."));
    }

    @Test
    @DisplayName("en-dash and hyphen collapse to the same match form")
    void enDashAndHyphenCollapse() {
        assertThat(ArtistNameNormalizer.normalize("Only Murders In The Building - Cast"))
                .isEqualTo(ArtistNameNormalizer.normalize("Only Murders in the Building – Cast"));
    }

    @Test
    @DisplayName("em-dash also collapses to a hyphen")
    void emDashCollapses() {
        assertThat(ArtistNameNormalizer.normalize("Foo - Bar"))
                .isEqualTo(ArtistNameNormalizer.normalize("Foo — Bar"));
    }

    @Test
    @DisplayName("curly quotes collapse to straight quotes")
    void curlyQuotesCollapse() {
        assertThat(ArtistNameNormalizer.normalize("Guns N' Roses"))
                .isEqualTo(ArtistNameNormalizer.normalize("Guns N’ Roses"));
        assertThat(ArtistNameNormalizer.normalize("\"Weird Al\" Yankovic"))
                .isEqualTo(ArtistNameNormalizer.normalize("“Weird Al” Yankovic"));
    }

    @Test
    @DisplayName("internal whitespace runs collapse to a single space, and ends are trimmed")
    void whitespaceCollapses() {
        assertThat(ArtistNameNormalizer.normalize("  Foo   Bar  "))
                .isEqualTo(ArtistNameNormalizer.normalize("Foo Bar"));
    }

    @Test
    @DisplayName("non-ASCII names are preserved, not stripped to empty (the issue's own inflated-count bug)")
    void nonAsciiNamesPreserved() {
        String hebrew = ArtistNameNormalizer.normalize("אבג");
        String japanese = ArtistNameNormalizer.normalize("あいう");

        assertThat(hebrew).isNotBlank();
        assertThat(japanese).isNotBlank();
        assertThat(hebrew).isNotEqualTo(japanese);
    }

    @Test
    @DisplayName("genuinely different names stay different after normalization")
    void differentNamesStayDifferent() {
        assertThat(ArtistNameNormalizer.normalize("Radiohead"))
                .isNotEqualTo(ArtistNameNormalizer.normalize("Radioheads"));
        assertThat(ArtistNameNormalizer.normalize("The National"))
                .isNotEqualTo(ArtistNameNormalizer.normalize("National Park Radio"));
    }
}
