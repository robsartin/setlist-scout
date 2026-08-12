package com.robsartin.setlistscout.shared.events;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventRecordsTest {

	@Test
	void artistActivatedExposeComponentsCorrectly() {
		var event = new ArtistActivated("owner", 5L, "Dawes");

		assertThat(event.owner()).isEqualTo("owner");
		assertThat(event.artistId()).isEqualTo(5L);
		assertThat(event.name()).isEqualTo("Dawes");
	}

	@Test
	void artistDeactivatedExposeComponentsCorrectly() {
		var event = new ArtistDeactivated("owner", 5L);

		assertThat(event.owner()).isEqualTo("owner");
		assertThat(event.artistId()).isEqualTo(5L);
	}

	@Test
	void settingsChangedExposeComponentsCorrectly() {
		var event = new SettingsChanged("owner");

		assertThat(event.owner()).isEqualTo("owner");
	}

	@Test
	void candidateDiscoveredExposeComponentsCorrectly() {
		var event = new CandidateDiscovered("o", "n", "MEMBER_EXPANSION", "via", "note");

		assertThat(event.owner()).isEqualTo("o");
		assertThat(event.name()).isEqualTo("n");
		assertThat(event.sourceType()).isEqualTo("MEMBER_EXPANSION");
		assertThat(event.discoveredVia()).isEqualTo("via");
		assertThat(event.note()).isEqualTo("note");
	}
}
