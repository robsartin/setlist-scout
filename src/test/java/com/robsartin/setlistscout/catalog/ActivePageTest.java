package com.robsartin.setlistscout.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActivePage#positionSummary()} is rendered visibly AND announced through {@code #sr-status}
 * (issue #174), so it is the one sentence that has to stay true when a search narrows the list
 * (#250) -- "No active artists." during a search reads as "your list is empty", which is a
 * different and wrong statement.
 */
class ActivePageTest {

    @Test
    @DisplayName("an empty unfiltered list says the list is empty")
    void emptyWithoutASearch() {
        assertThat(page(List.of(), null).positionSummary("active")).isEqualTo("No active artists.");
    }

    @Test
    @DisplayName("an empty filtered list names the query rather than claiming the list is empty")
    void emptyWithASearch() {
        assertThat(page(List.of(), "petty").positionSummary("active"))
                .isEqualTo("No active artists match \"petty\".");
    }

    @Test
    @DisplayName("a filtered page counts the matches shown, not the whole list")
    void filteredCountsMatches() {
        assertThat(page(List.of(artist("Petty Officer"), artist("Tom Petty")), "petty").positionSummary("active"))
                .isEqualTo("Showing 2 artists matching \"petty\", Petty Officer to Tom Petty.");
    }

    @Test
    @DisplayName("a single filtered match reads naturally")
    void singleFilteredMatch() {
        assertThat(page(List.of(artist("Tom Petty")), "petty").positionSummary("active"))
                .isEqualTo("Showing the only active artist matching \"petty\": Tom Petty.");
    }

    @Test
    @DisplayName("without a search the wording is unchanged -- #174's exact sentences still render")
    void unfilteredWordingIsUnchanged() {
        assertThat(page(List.of(artist("Alpha"), artist("Zulu")), null).positionSummary("active"))
                .isEqualTo("Showing 2 artists, Alpha to Zulu.");
        assertThat(page(List.of(artist("Alpha")), null).positionSummary("active"))
                .isEqualTo("Showing the only active artist: Alpha.");
        assertThat(page(List.of(artist("Alpha"), artist("Zulu")), "  ").positionSummary("active"))
                .isEqualTo("Showing 2 artists, Alpha to Zulu.");
    }

    @Test
    @DisplayName("the noun names the list -- the Rejected page says 'rejected', not 'active' (#251)")
    void nounNamesTheList() {
        assertThat(page(List.of(), null).positionSummary("rejected")).isEqualTo("No rejected artists.");
        assertThat(page(List.of(artist("Amanda Shires")), "shires").positionSummary("rejected"))
                .isEqualTo("Showing the only rejected artist matching \"shires\": Amanda Shires.");
    }

    private static ActivePage page(List<Artist> artists, String query) {
        return new ActivePage(artists, false, false, null, null, query);
    }

    private static Artist artist(String name) {
        return new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
    }
}
