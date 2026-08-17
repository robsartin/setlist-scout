package com.robsartin.setlistscout.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistSeedServiceTest {

    private static final String OWNER = "rob@example.com";

    private ArtistRepository artistRepository;
    private ArtistActivationService activationService;
    private ArtistNameMatcher artistNameMatcher;
    private ArtistSeedService service;

    // The view mock must be fully built (mock() + all when(...).thenReturn(...) calls completed)
    // BEFORE the caller's own when(...).thenReturn(...) begins -- see ArtistNameMatcherTest's
    // identical helper for why (Mockito's UnfinishedStubbingException otherwise).
    private static ArtistNameStatusView view(Long id, String name, ArtistStatus status) {
        ArtistNameStatusView v = mock(ArtistNameStatusView.class);
        when(v.getId()).thenReturn(id);
        when(v.getName()).thenReturn(name);
        when(v.getStatus()).thenReturn(status);
        return v;
    }

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        activationService = mock(ArtistActivationService.class);
        artistNameMatcher = mock(ArtistNameMatcher.class);
        service = new ArtistSeedService(artistRepository, activationService, artistNameMatcher);
    }

    @Test
    @DisplayName("adds a trimmed new name as a seed when no existing artist matches")
    void addsATrimmedNewNameAsASeed() {
        when(artistNameMatcher.findExistingMatch(OWNER, "Wilco")).thenReturn(Optional.empty());
        when(artistRepository.insertIfAbsent(eq(OWNER), eq("Wilco"), eq(ArtistNameNormalizer.normalize("Wilco")),
                eq(ArtistSource.SEED_LIST.name()), eq(ArtistStatus.SEED.name()), isNull(), isNull(),
                any(Instant.class))).thenReturn(1);
        Artist resolved = new Artist("Wilco", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        resolved.setOwner(OWNER);
        when(artistRepository.findByOwnerAndName(OWNER, "Wilco")).thenReturn(Optional.of(resolved));

        boolean added = service.addSeedIfNew(OWNER, "  Wilco  ");

        assertThat(added).isTrue();
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService).onSeedCreated(resolved);
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("issue #133: when insertIfAbsent reports the row already existed (a concurrent "
            + "call won the race for this exact brand-new name), this call reports false and never "
            + "fires onSeedCreated for what is, from its own perspective, someone else's insert")
    void raceLoserReportsFalseAndDoesNotFireOnSeedCreated() {
        when(artistNameMatcher.findExistingMatch(OWNER, "Nebraska")).thenReturn(Optional.empty());
        when(artistRepository.insertIfAbsent(eq(OWNER), eq("Nebraska"), eq(ArtistNameNormalizer.normalize("Nebraska")),
                eq(ArtistSource.SEED_LIST.name()), eq(ArtistStatus.SEED.name()), isNull(), isNull(),
                any(Instant.class))).thenReturn(0);

        boolean added = service.addSeedIfNew(OWNER, "Nebraska");

        assertThat(added).as("the race loser did not cause a new seed -- the winner did").isFalse();
        verify(artistRepository, never()).save(any(Artist.class));
        verify(artistRepository, never()).findByOwnerAndName(anyString(), anyString());
        verify(activationService, never()).onSeedCreated(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("skips blank, whitespace-only, null and comment lines without consulting the matcher")
    void skipsBlankAndCommentLines() {
        assertThat(service.addSeedIfNew(OWNER, "   ")).isFalse();
        assertThat(service.addSeedIfNew(OWNER, "")).isFalse();
        assertThat(service.addSeedIfNew(OWNER, null)).isFalse();
        assertThat(service.addSeedIfNew(OWNER, "# a comment")).isFalse();
        verify(artistNameMatcher, never()).findExistingMatch(any(), any());
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).onSeedCreated(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("no-ops when the name matches an already-ACTIVE (SEED) artist for the owner")
    void skipsANameThatMatchesAnActiveSeedArtist() {
        ArtistNameStatusView existing = view(1L, "Dawes", ArtistStatus.SEED);
        when(artistNameMatcher.findExistingMatch(OWNER, "Dawes")).thenReturn(Optional.of(existing));

        assertThat(service.addSeedIfNew(OWNER, "Dawes")).isFalse();
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).onSeedCreated(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("no-ops when the name matches an already-ACTIVE (APPROVED) artist for the owner -- "
            + "does not downgrade an approved artist back to SEED")
    void skipsANameThatMatchesAnActiveApprovedArtist() {
        ArtistNameStatusView existing = view(1L, "Dawes", ArtistStatus.APPROVED);
        when(artistNameMatcher.findExistingMatch(OWNER, "Dawes")).thenReturn(Optional.of(existing));

        assertThat(service.addSeedIfNew(OWNER, "Dawes")).isFalse();
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).onSeedCreated(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("issue #124: manually re-adding a name matching a REJECTED artist reactivates that "
            + "row to SEED through ArtistActivationService, rather than silently no-op-ing or "
            + "creating a duplicate row -- a manual add is explicit user intent that should win over "
            + "a prior rejection")
    void reactivatesARejectedMatchAsSeedThroughActivationService() {
        ArtistNameStatusView existing = view(5L, "Charlie Parker's Re-Boppers", ArtistStatus.REJECTED);
        when(artistNameMatcher.findExistingMatch(OWNER, "Charlie Parker's Re-boppers"))
                .thenReturn(Optional.of(existing));

        boolean added = service.addSeedIfNew(OWNER, "Charlie Parker's Re-boppers");

        assertThat(added).as("the manual re-add is reported as effective").isTrue();
        verify(activationService).changeStatus(5L, OWNER, ArtistStatus.SEED);
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).onSeedCreated(any(Artist.class));
    }

    @Test
    @DisplayName("a name matching a still-PENDING_REVIEW artist is also reactivated to SEED -- an "
            + "explicit manual add fast-tracks a pending candidate the same way it un-rejects one")
    void reactivatesAPendingReviewMatchAsSeedThroughActivationService() {
        ArtistNameStatusView existing = view(9L, "Foo Bar", ArtistStatus.PENDING_REVIEW);
        when(artistNameMatcher.findExistingMatch(OWNER, "Foo Bar")).thenReturn(Optional.of(existing));

        boolean added = service.addSeedIfNew(OWNER, "Foo Bar");

        assertThat(added).isTrue();
        verify(activationService).changeStatus(9L, OWNER, ArtistStatus.SEED);
        verify(artistRepository, never()).save(any(Artist.class));
    }

    @Test
    @DisplayName("two genuinely different names both get added as distinct seeds")
    void twoGenuinelyDifferentArtistsBothGetAdded() {
        when(artistNameMatcher.findExistingMatch(OWNER, "Radiohead")).thenReturn(Optional.empty());
        when(artistNameMatcher.findExistingMatch(OWNER, "Radioheads Tribute Band")).thenReturn(Optional.empty());
        when(artistRepository.insertIfAbsent(eq(OWNER), eq("Radiohead"), eq(ArtistNameNormalizer.normalize("Radiohead")),
                eq(ArtistSource.SEED_LIST.name()), eq(ArtistStatus.SEED.name()), isNull(), isNull(),
                any(Instant.class))).thenReturn(1);
        when(artistRepository.insertIfAbsent(eq(OWNER), eq("Radioheads Tribute Band"),
                eq(ArtistNameNormalizer.normalize("Radioheads Tribute Band")), eq(ArtistSource.SEED_LIST.name()),
                eq(ArtistStatus.SEED.name()), isNull(), isNull(), any(Instant.class))).thenReturn(1);
        Artist radiohead = new Artist("Radiohead", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        radiohead.setOwner(OWNER);
        Artist tribute = new Artist("Radioheads Tribute Band", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        tribute.setOwner(OWNER);
        when(artistRepository.findByOwnerAndName(OWNER, "Radiohead")).thenReturn(Optional.of(radiohead));
        when(artistRepository.findByOwnerAndName(OWNER, "Radioheads Tribute Band")).thenReturn(Optional.of(tribute));

        assertThat(service.addSeedIfNew(OWNER, "Radiohead")).isTrue();
        assertThat(service.addSeedIfNew(OWNER, "Radioheads Tribute Band")).isTrue();

        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService).onSeedCreated(radiohead);
        verify(activationService).onSeedCreated(tribute);
    }

    @Test
    @DisplayName("owner is passed through unchanged to the name matcher (owner-scoped matching)")
    void ownerIsPassedThroughToTheMatcher() {
        when(artistNameMatcher.findExistingMatch(eq(OWNER), eq("Wilco"))).thenReturn(Optional.empty());

        service.addSeedIfNew(OWNER, "Wilco");

        verify(artistNameMatcher).findExistingMatch(OWNER, "Wilco");
    }

    @Test
    @DisplayName("issue #124: an en-dash/hyphen punctuation variant of an existing active artist is "
            + "not duplicated -- proven with the REAL ArtistNameMatcher wired against a mocked "
            + "repository, not a mocked matcher, so the actual normalization is exercised")
    void enDashVariantOfAnExistingActiveArtistIsNotDuplicated() {
        ArtistSeedService realWiredService =
                new ArtistSeedService(artistRepository, activationService, new ArtistNameMatcher(artistRepository));
        ArtistNameStatusView existing = view(3L, "Only Murders In The Building - Cast", ArtistStatus.SEED);
        when(artistRepository.findFirstByOwnerAndNormalizedName(OWNER,
                ArtistNameNormalizer.normalize("Only Murders in the Building – Cast")))
                .thenReturn(Optional.of(existing));

        boolean added = realWiredService.addSeedIfNew(OWNER, "Only Murders in the Building – Cast");

        assertThat(added).as("en-dash variant of an existing SEED artist is a duplicate, not a new row")
                .isFalse();
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }
}
