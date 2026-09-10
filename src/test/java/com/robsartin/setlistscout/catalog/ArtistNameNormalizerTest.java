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

    // ---- #267: a leading definite article ----------------------------------------------------

    @Test
    @DisplayName("issue #267: a leading 'The' is dropped -- 'The Grateful Dead' and 'Grateful Dead' "
            + "are one band")
    void leadingTheIsDropped() {
        assertThat(ArtistNameNormalizer.normalize("The Grateful Dead"))
                .isEqualTo(ArtistNameNormalizer.normalize("Grateful Dead"));
        assertThat(ArtistNameNormalizer.normalize("The Avett Brothers"))
                .isEqualTo(ArtistNameNormalizer.normalize("Avett Brothers"));
    }

    @Test
    @DisplayName("issue #267: an INTERNAL 'The' survives -- only a string-initial article goes")
    void internalTheSurvives() {
        assertThat(ArtistNameNormalizer.normalize("Bruce Springsteen & The E Street Band"))
                .contains("the e street band");
        assertThat(ArtistNameNormalizer.normalize("Bruce Springsteen & The E Street Band"))
                .isNotEqualTo(ArtistNameNormalizer.normalize("Bruce Springsteen & E Street Band"));
    }

    @Test
    @DisplayName("issue #267: the match is on the WORD 'the' plus a separator, not a 4-char prefix "
            + "-- 56 names in production begin 'the' with no space and must not be truncated")
    void onlyStripsTheAsAWholeWord() {
        for (String name : java.util.List.of("Theremin Ensemble", "Thee Oh Sees", "TheEllenShow",
                "Theatre Of Eternal Music", "Thelonious Monk")) {
            assertThat(ArtistNameNormalizer.normalize(name))
                    .as("%s must not lose its leading characters", name)
                    .isEqualTo(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    @DisplayName("issue #267: 'The Thelonious Monk Quartet' strips correctly -- the next word also "
            + "starting with 'the' must not confuse the rule")
    void stripsWhenTheNextWordAlsoStartsWithThe() {
        assertThat(ArtistNameNormalizer.normalize("The Thelonious Monk Quartet"))
                .isEqualTo(ArtistNameNormalizer.normalize("Thelonious Monk Quartet"));
        assertThat(ArtistNameNormalizer.normalize("The Theatre of Eternal Music"))
                .isEqualTo(ArtistNameNormalizer.normalize("Theatre Of Eternal Music"));
    }

    @Test
    @DisplayName("issue #267: a SINGLE-WORD name keeps its 'The' -- 'The Beat' and 'BEAT' are "
            + "plausibly different acts, so the rule is deliberately narrowed and this pins it")
    void singleWordNamesKeepTheirArticle() {
        assertThat(ArtistNameNormalizer.normalize("The Beat"))
                .isNotEqualTo(ArtistNameNormalizer.normalize("BEAT"));
        assertThat(ArtistNameNormalizer.normalize("The Smile"))
                .isNotEqualTo(ArtistNameNormalizer.normalize("Smile"));
        assertThat(ArtistNameNormalizer.normalize("The Beat")).isEqualTo("the beat");
    }

    @Test
    @DisplayName("issue #267: a name that is only 'The' is left alone, never emptied")
    void bareArticleIsNotEmptied() {
        assertThat(ArtistNameNormalizer.normalize("The")).isEqualTo("the");
        assertThat(ArtistNameNormalizer.normalize("The ")).isEqualTo("the");
    }

    @Test
    @DisplayName("issue #268: a Latin accent folds away -- the three live duplicate pairs reach "
            + "one key each")
    void latinAccentsFold() {
        assertThat(ArtistNameNormalizer.normalize("Céline Dion"))
                .isEqualTo(ArtistNameNormalizer.normalize("Celine Dion"));
        assertThat(ArtistNameNormalizer.normalize("Sinéad O'Connor"))
                .isEqualTo(ArtistNameNormalizer.normalize("Sinead O'Connor"));
        assertThat(ArtistNameNormalizer.normalize("Jorge Calderón"))
                .isEqualTo(ArtistNameNormalizer.normalize("Jorge Calderon"));
        assertThat(ArtistNameNormalizer.normalize("Béla Bartók")).isEqualTo("bela bartok");
    }

    @Test
    @DisplayName("issue #268: the search complaint -- 'beyonce' typed without an accented keyboard "
            + "reaches the same key as 'Beyoncé'")
    void unaccentedTypingFindsAnAccentedArtist() {
        assertThat(ArtistNameNormalizer.normalize("beyonce"))
                .isEqualTo(ArtistNameNormalizer.normalize("Beyoncé"));
    }

    @Test
    @DisplayName("issue #268: a STROKE letter folds -- it has no canonical decomposition, so "
            + "decompose-then-drop-marks alone DELETES it and 'Michał' would fold one letter short")
    void strokeLettersFold() {
        assertThat(ArtistNameNormalizer.normalize("Michał"))
                .isEqualTo(ArtistNameNormalizer.normalize("Michal"));
        assertThat(ArtistNameNormalizer.normalize("Øystein Sevåg"))
                .isEqualTo(ArtistNameNormalizer.normalize("Oystein Sevag"));
        assertThat(ArtistNameNormalizer.normalize("Michał")).isEqualTo("michal");
        assertThat(ArtistNameNormalizer.normalize("Stanisław Lem")).isEqualTo("stanislaw lem");
    }

    /**
     * The assertion this whole issue turns on. A combining mark is dropped ONLY when its base is an
     * ASCII letter. Japanese katakana encodes a voiced sound as base + U+3099/U+309A, both category
     * NON_SPACING_MARK, so an unconditional mark-drop rewrites the word: the violinist
     * {@code ミドリ} becomes {@code ミトリ} and {@code ガンダム} becomes {@code カンタム}. That is the
     * ASCII-stripping bug of #118 in a different costume, and it corrupted 9 real catalog rows in
     * the first implementation profiled for this issue.
     *
     * <p>Every name here is taken verbatim from Rob's production catalog, not invented.
     */
    @Test
    @DisplayName("issue #268: a NON-LATIN name is left EXACTLY as it was -- dropping a Japanese "
            + "dakuten would rewrite the word, not remove an accent")
    void nonLatinNamesAreUntouched() {
        for (String name : java.util.List.of(
                "ミドリ",                       // katakana + dakuten -- would become ミトリ
                "キバオブアキバ",                 // three voiced marks -- would become キハオフアキハ
                "機動戦士Ζガンダム",              // would become 機動戦士ζカンタム
                "サイプレス上野とロベルト吉野",      // handakuten too
                "김지현",                       // Hangul: decomposes to jamo, must recompose
                "방탄소년단",
                "אסף רייז",                    // Hebrew
                "גיא בראונשטיין",
                "J.S. 巴赫",                    // CJK
                "پرویز یاحقی")) {              // Persian
            assertThat(ArtistNameNormalizer.normalize(name))
                    .as("%s must survive normalization unchanged", name)
                    .isEqualTo(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    @DisplayName("issue #268: Greek and Cyrillic diacritics survive -- their base letters are not "
            + "ASCII, so the fold does not reach them")
    void greekAndCyrillicDiacriticsSurvive() {
        assertThat(ArtistNameNormalizer.normalize("Μαρία")).isEqualTo("μαρία");
        assertThat(ArtistNameNormalizer.normalize("Дмитрий")).isEqualTo("дмитрий");
    }

    @Test
    @DisplayName("issue #268: no name normalizes to empty -- #118's ASCII strip collapsed every "
            + "all-Hebrew and all-Japanese name to one colliding key")
    void noNonLatinNameNormalizesToEmpty() {
        for (String name : java.util.List.of("ミドリ", "김지현", "אסף רייז", "J.S. 巴赫", "پرویز یاحقی")) {
            assertThat(ArtistNameNormalizer.normalize(name))
                    .as("%s must not be emptied", name)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("issue #268: Vietnamese tone marks fold, because their bases ARE ASCII letters")
    void vietnameseToneMarksFold() {
        assertThat(ArtistNameNormalizer.normalize("Ai Mà Biết Được")).isEqualTo("ai ma biet duoc");
    }

    @Test
    @DisplayName("issue #268: a name already stored DECOMPOSED reaches the same key as the "
            + "precomposed spelling -- the fold recomposes, so both forms match")
    void decomposedAndPrecomposedInputAgree() {
        assertThat(ArtistNameNormalizer.normalize("Ce\u0301line Dion"))   // e + combining acute
                .isEqualTo(ArtistNameNormalizer.normalize("C\u00e9line Dion"));
        assertThat(ArtistNameNormalizer.normalize("\u30c6\u3099\u30e9"))  // te + dakuten + ra
                .isEqualTo(ArtistNameNormalizer.normalize("\u30c7\u30e9")); // de + ra
    }

    @Test
    @DisplayName("issue #268: sharp-s and the ae/oe ligatures are deliberately NOT folded -- none "
            + "forms a duplicate pair in the catalog, so this stays conservative")
    void ligaturesAndSharpSAreNotFolded() {
        assertThat(ArtistNameNormalizer.normalize("Andreas Kißling")).isEqualTo("andreas kißling");
        assertThat(ArtistNameNormalizer.normalize("Andreas Ihlebæk")).isEqualTo("andreas ihlebæk");
        assertThat(ArtistNameNormalizer.normalize("Lou Pascal Et Ses Chœurs"))
                .isEqualTo("lou pascal et ses chœurs");
    }
}
