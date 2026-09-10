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

    @Test
    @DisplayName("issue #157: the real production pair -- spaced vs. unspaced hyphen -- normalizes equal")
    void hyphenSpacingCollapsesForRealProductionPair() {
        assertThat(ArtistNameNormalizer.normalize("Paul Quinichette - John Coltrane Quintet"))
                .isEqualTo(ArtistNameNormalizer.normalize("Paul Quinichette-John Coltrane Quintet"));
    }

    @Test
    @DisplayName("issue #157: any whitespace touching a hyphen collapses away, one-sided or both")
    void hyphenSpacingVariantsAllCollapseToTheSameForm() {
        String unspaced = ArtistNameNormalizer.normalize("Foo-Bar");
        assertThat(ArtistNameNormalizer.normalize("Foo - Bar")).isEqualTo(unspaced);
        assertThat(ArtistNameNormalizer.normalize("Foo- Bar")).isEqualTo(unspaced);
        assertThat(ArtistNameNormalizer.normalize("Foo -Bar")).isEqualTo(unspaced);
    }

    @Test
    @DisplayName("issue #157: word substitution (\"and\" vs \"&\") still does NOT match -- pins the "
            + "conservative philosophy the class doc calls out; hyphen-spacing is not a license for "
            + "general fuzzy matching")
    void wordSubstitutionStillDoesNotMatch() {
        assertThat(ArtistNameNormalizer.normalize("Tom Petty and The Heartbreakers"))
                .isNotEqualTo(ArtistNameNormalizer.normalize("Tom Petty & The Heartbreakers"));
    }

    @Test
    @DisplayName("issue #157: real production non-Latin names stay distinct and non-blank -- the "
            + "hyphen-spacing fix must not regress the ASCII-stripping bug the issue also profiled")
    void nonLatinProductionNamesStayDistinct() {
        String hebrew = ArtistNameNormalizer.normalize("אסף רייז");
        String japanese1 = ArtistNameNormalizer.normalize("灰野敬二");
        String japanese2 = ArtistNameNormalizer.normalize("サイケアウツ");

        assertThat(hebrew).isNotBlank();
        assertThat(japanese1).isNotBlank();
        assertThat(japanese2).isNotBlank();
        assertThat(hebrew).isNotEqualTo(japanese1);
        assertThat(hebrew).isNotEqualTo(japanese2);
        assertThat(japanese1).isNotEqualTo(japanese2);
    }

    // ---- #261: U+2010 HYPHEN and U+2011 NON-BREAKING HYPHEN ----------------------------------

    @Test
    @DisplayName("issue #261: U+2010 HYPHEN folds to the ASCII hyphen -- 33 duplicate artist pairs "
            + "in production exist only because it did not")
    void unicodeHyphenFoldsToAsciiHyphen() {
        assertThat(ArtistNameNormalizer.normalize("Drive\u2010By Truckers"))
                .isEqualTo(ArtistNameNormalizer.normalize("Drive-By Truckers"));
    }

    @Test
    @DisplayName("issue #261: U+2011 NON-BREAKING HYPHEN folds to the ASCII hyphen")
    void nonBreakingHyphenFoldsToAsciiHyphen() {
        assertThat(ArtistNameNormalizer.normalize("Olivia Newton\u2011John"))
                .isEqualTo(ArtistNameNormalizer.normalize("Olivia Newton-John"));
    }

    @Test
    @DisplayName("issue #261: the #157 whitespace collapse applies to the NEW characters too, not "
            + "just the ASCII hyphen -- folding the character without this leaves \"X\u2010 Y\" distinct")
    void whitespaceAroundTheNewHyphensCollapsesToo() {
        String target = ArtistNameNormalizer.normalize("X-Y");
        for (String variant : java.util.List.of(
                "X\u2010 Y", "X \u2010Y", "X \u2010 Y",
                "X\u2011 Y", "X \u2011Y", "X \u2011 Y")) {
            assertThat(ArtistNameNormalizer.normalize(variant))
                    .as("%s should normalize to the same form as X-Y", variant)
                    .isEqualTo(target);
        }
    }

    @Test
    @DisplayName("issue #261 regression guard: the four dashes folded before this change still fold")
    void previouslyFoldedDashesStillFold() {
        String target = ArtistNameNormalizer.normalize("X-Y");
        for (String variant : java.util.List.of("X\u2013Y", "X\u2014Y", "X\u2015Y", "X\u2212Y")) {
            assertThat(ArtistNameNormalizer.normalize(variant))
                    .as("%s should still normalize to the same form as X-Y", variant)
                    .isEqualTo(target);
        }
    }

    @Test
    @DisplayName("issue #261: real production pairs collapse -- these are the rows being scanned twice")
    void realProductionDuplicatePairsCollapse() {
        assertThat(ArtistNameNormalizer.normalize("Go\u2011Go\u2019s"))
                .isEqualTo(ArtistNameNormalizer.normalize("Go-Go's"));
        assertThat(ArtistNameNormalizer.normalize("Blue Note All\u2010Stars"))
                .isEqualTo(ArtistNameNormalizer.normalize("Blue Note All-Stars"));
        assertThat(ArtistNameNormalizer.normalize("Yo\u2011Yo Ma & Kathryn Stott"))
                .isEqualTo(ArtistNameNormalizer.normalize("Yo-Yo Ma & Kathryn Stott"));
    }

    // ---- #266: a hyphen and a space are the same separator ----------------------------------

    @Test
    @DisplayName("issue #266: hyphen and space are the same separator -- 'X Y', 'X-Y' and 'X - Y' "
            + "all reach one form")
    void hyphenAndSpaceAreTheSameSeparator() {
        String target = ArtistNameNormalizer.normalize("X Y");
        for (String variant : java.util.List.of("X-Y", "X - Y", "X- Y", "X -Y",
                "X\u2010Y", "X\u2011Y", "X\u2013Y", "X\u2014Y")) {
            assertThat(ArtistNameNormalizer.normalize(variant))
                    .as("%s should normalize to the same form as 'X Y'", variant)
                    .isEqualTo(target);
        }
    }

    @Test
    @DisplayName("issue #266: the real production pairs collapse -- these are rows being scanned twice")
    void realProductionHyphenSpacePairsCollapse() {
        assertThat(ArtistNameNormalizer.normalize("Yo-Yo Ma"))
                .isEqualTo(ArtistNameNormalizer.normalize("Yo Yo Ma"));
        assertThat(ArtistNameNormalizer.normalize("Jean-Michel Jarre"))
                .isEqualTo(ArtistNameNormalizer.normalize("Jean Michel Jarre"));
        assertThat(ArtistNameNormalizer.normalize("Jasmine Cephas-Jones"))
                .isEqualTo(ArtistNameNormalizer.normalize("Jasmine Cephas Jones"));
        assertThat(ArtistNameNormalizer.normalize("The E-Street Band"))
                .isEqualTo(ArtistNameNormalizer.normalize("The E Street Band"));
    }

    @Test
    @DisplayName("issue #266: this widens the SEPARATOR rule, it does not delete separators -- "
            + "'AC/DC' and 'ACDC' must still differ, or the class's conservative philosophy is gone")
    void doesNotDeleteSeparators() {
        assertThat(ArtistNameNormalizer.normalize("AC/DC"))
                .isNotEqualTo(ArtistNameNormalizer.normalize("ACDC"));
        assertThat(ArtistNameNormalizer.normalize("Yo-Yo Ma"))
                .isNotEqualTo(ArtistNameNormalizer.normalize("YoYo Ma"));
    }

    @Test
    @DisplayName("issue #266: non-Latin names keep a non-empty key of unchanged length -- the "
            + "#118 ASCII-stripping guard, since this edits the same method")
    void nonLatinNamesAreUnaffected() {
        for (String name : java.util.List.of("\u30a2\u30b3\u30fc\u30b9\u30d5\u30a3\u30a2",
                "\u05e9\u05dc\u05d5\u05dd", "\u0417\u0430\u0433\u0430\u0434\u043a\u0430")) {
            assertThat(ArtistNameNormalizer.normalize(name))
                    .as("non-Latin name %s must survive", name)
                    .isNotEmpty()
                    .hasSameSizeAs(name);
        }
    }
}
