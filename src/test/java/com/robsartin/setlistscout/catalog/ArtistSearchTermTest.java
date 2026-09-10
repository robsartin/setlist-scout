package com.robsartin.setlistscout.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArtistSearchTermTest {

    @Test
    @DisplayName("a blank, empty or null query is not a search")
    void blankIsNotASearch() {
        assertThat(ArtistSearchTerm.isSearch(null)).isFalse();
        assertThat(ArtistSearchTerm.isSearch("")).isFalse();
        assertThat(ArtistSearchTerm.isSearch("   ")).isFalse();
        assertThat(ArtistSearchTerm.isSearch("petty")).isTrue();
    }

    @Test
    @DisplayName("matches on ArtistNameNormalizer's form, not raw text -- case folds")
    void foldsCase() {
        assertThat(ArtistSearchTerm.likePattern("BeyoncÉ")).isEqualTo("%beyoncé%");
    }

    @Test
    @DisplayName("hyphen spacing folds the same way stored normalized_name does -- 'foo - bar' "
            + "reaches the same form as 'foo-bar' and 'foo bar' (#157, widened by #266). This is "
            + "the assertion a naive lower() would fail.")
    void foldsHyphenSpacingLikeTheNormalizer() {
        assertThat(ArtistSearchTerm.likePattern("Foo - Bar")).isEqualTo("%foo bar%");
        assertThat(ArtistSearchTerm.likePattern("Foo - Bar"))
                .isEqualTo("%" + ArtistNameNormalizer.normalize("Foo-Bar") + "%");
    }

    @Test
    @DisplayName("curly quotes and unicode dashes fold, so a pasted name still finds the row")
    void foldsUnicodePunctuation() {
        assertThat(ArtistSearchTerm.likePattern("Guns ‘n’ Roses")).isEqualTo("%guns 'n' roses%");
        assertThat(ArtistSearchTerm.likePattern("X – Y")).isEqualTo("%x y%");
    }

    @Test
    @DisplayName("LIKE wildcards in the query are escaped -- '%' is a literal percent, not 'match "
            + "anything'. normalize() keeps % and _, so without this a search for '50%' matches "
            + "every artist whose name starts with 50.")
    void escapesLikeWildcards() {
        assertThat(ArtistSearchTerm.likePattern("50%")).isEqualTo("%50\\%%");
        assertThat(ArtistSearchTerm.likePattern("a_b")).isEqualTo("%a\\_b%");
    }

    @Test
    @DisplayName("a literal backslash is escaped first, so it cannot smuggle in an escape sequence")
    void escapesTheEscapeCharacter() {
        assertThat(ArtistSearchTerm.likePattern("a\\b")).isEqualTo("%a\\\\b%");
        assertThat(ArtistSearchTerm.likePattern("a\\%b")).isEqualTo("%a\\\\\\%b%");
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed rather than searched for")
    void trimsSurroundingWhitespace() {
        assertThat(ArtistSearchTerm.likePattern("  petty  ")).isEqualTo("%petty%");
    }
}
