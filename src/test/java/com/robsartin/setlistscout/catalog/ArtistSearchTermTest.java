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
    @DisplayName("matches on ArtistNameNormalizer's form, not raw text -- case folds, and since "
            + "#268 the accent folds with it")
    void foldsCase() {
        assertThat(ArtistSearchTerm.likePattern("BeyoncÉ")).isEqualTo("%beyonce%");
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

    @Test
    @DisplayName("issue #268: a query typed without an accented keyboard reaches the accented "
            + "artist -- 'beyonce' and 'beyonce\u0301' build the same pattern, and it is the "
            + "pattern V36 backfills the column to")
    void unaccentedQueryFindsAnAccentedArtist() {
        assertThat(ArtistSearchTerm.likePattern("beyonce")).isEqualTo("%beyonce%");
        assertThat(ArtistSearchTerm.likePattern("Beyonc\u00e9")).isEqualTo("%beyonce%");
        assertThat(ArtistSearchTerm.likePattern("celine"))
                .isEqualTo(ArtistSearchTerm.likePattern("c\u00e9line"));
    }

    @Test
    @DisplayName("issue #268: a non-Latin query is NOT mangled -- searching for the Japanese "
            + "spelling still builds a pattern containing it verbatim")
    void nonLatinQueryIsNotMangled() {
        assertThat(ArtistSearchTerm.likePattern("\u30df\u30c9\u30ea")).isEqualTo("%\u30df\u30c9\u30ea%");
        assertThat(ArtistSearchTerm.likePattern("\uae40\uc9c0\ud604")).isEqualTo("%\uae40\uc9c0\ud604%");
    }
}
