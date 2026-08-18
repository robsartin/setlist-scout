package com.robsartin.setlistscout.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level contract for {@link ArtistSeedService#addSeedIfNew}, rewritten for #179's write-first
 * shape: the method no longer asks whether a name exists before inserting it. It inserts, and reads
 * only when {@link ArtistRepository#insertIfAbsent} reports the database absorbed the write.
 * <p>
 * A mocked {@code insertIfAbsent} returns Mockito's default {@code 0} unless stubbed, which is
 * exactly the "a row already exists" outcome -- so the duplicate/reactivation cases below need no
 * stub for it, and the genuinely-new cases must stub it to {@code 1}.
 */
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

    /**
     * Stubs "the database absorbed the insert, and THIS is the row it conflicted with" -- the
     * write-first equivalent of the old pre-check stub. {@code insertIfAbsent} needs no stub: an
     * unstubbed mock returns {@code 0}, which is exactly that outcome.
     * <p>
     * The view is built into a local FIRST, never inlined into the {@code when(...)} below: {@link
     * #view} is itself a stubbing call, and starting one while the outer {@code when(...)}'s
     * argument is still being evaluated throws {@code UnfinishedStubbingException}.
     */
    private void stubAbsorbedInsert(String candidateName, Long id, String existingName, ArtistStatus status) {
        ArtistNameStatusView existing = view(id, existingName, status);
        when(artistNameMatcher.findExistingMatch(OWNER, candidateName)).thenReturn(Optional.of(existing));
    }

    /** Stubs a winning insert for {@code name} plus the exact-name resolve that follows it. */
    private Artist stubWinningInsert(String name) {
        when(artistRepository.insertIfAbsent(eq(OWNER), eq(name), eq(ArtistNameNormalizer.normalize(name)),
                eq(ArtistSource.SEED_LIST.name()), eq(ArtistStatus.SEED.name()), isNull(), isNull(),
                any(Instant.class))).thenReturn(1);
        Artist resolved = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        resolved.setOwner(OWNER);
        when(artistRepository.findByOwnerAndName(OWNER, name)).thenReturn(Optional.of(resolved));
        return resolved;
    }

    @Test
    @DisplayName("issue #179: adds a trimmed new name as a seed WITHOUT any pre-check read -- the "
            + "insert itself is the duplicate check, so there is no window for the #133 race to open in")
    void addsATrimmedNewNameAsASeedWithoutReadingFirst() {
        Artist resolved = stubWinningInsert("Wilco");

        boolean added = service.addSeedIfNew(OWNER, "  Wilco  ");

        assertThat(added).isTrue();
        verify(artistNameMatcher, never()).findExistingMatch(anyString(), anyString());
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService).onSeedCreated(resolved);
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("skips blank, whitespace-only, null and comment lines without touching the database")
    void skipsBlankAndCommentLines() {
        assertThat(service.addSeedIfNew(OWNER, "   ")).isFalse();
        assertThat(service.addSeedIfNew(OWNER, "")).isFalse();
        assertThat(service.addSeedIfNew(OWNER, null)).isFalse();
        assertThat(service.addSeedIfNew(OWNER, "# a comment")).isFalse();
        verify(artistRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any());
        verify(artistNameMatcher, never()).findExistingMatch(any(), any());
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).onSeedCreated(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("no-ops when the name belongs to an already-ACTIVE (SEED) artist -- the insert is "
            + "still attempted, and the database absorbing it is what says 'duplicate'")
    void noOpsWhenTheNameBelongsToAnActiveSeedArtist() {
        stubAbsorbedInsert("Dawes", 1L, "Dawes", ArtistStatus.SEED);

        assertThat(service.addSeedIfNew(OWNER, "Dawes")).isFalse();

        verify(artistRepository).insertIfAbsent(eq(OWNER), eq("Dawes"), eq(ArtistNameNormalizer.normalize("Dawes")),
                eq(ArtistSource.SEED_LIST.name()), eq(ArtistStatus.SEED.name()), isNull(), isNull(),
                any(Instant.class));
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).onSeedCreated(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("no-ops when the name belongs to an already-ACTIVE (APPROVED) artist -- does not "
            + "downgrade an approved artist back to SEED")
    void noOpsWhenTheNameBelongsToAnActiveApprovedArtist() {
        stubAbsorbedInsert("Dawes", 1L, "Dawes", ArtistStatus.APPROVED);

        assertThat(service.addSeedIfNew(OWNER, "Dawes")).isFalse();
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).onSeedCreated(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("issue #124: manually re-adding a name belonging to a REJECTED artist reactivates "
            + "that row to SEED through ArtistActivationService, rather than silently no-op-ing or "
            + "creating a duplicate row -- a manual add is explicit user intent that should win over "
            + "a prior rejection")
    void reactivatesARejectedMatchAsSeedThroughActivationService() {
        stubAbsorbedInsert("Charlie Parker's Re-boppers", 5L, "Charlie Parker's Re-Boppers",
                ArtistStatus.REJECTED);

        boolean added = service.addSeedIfNew(OWNER, "Charlie Parker's Re-boppers");

        assertThat(added).as("the manual re-add is reported as effective").isTrue();
        verify(activationService).changeStatus(5L, OWNER, ArtistStatus.SEED);
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).onSeedCreated(any(Artist.class));
    }

    @Test
    @DisplayName("a name belonging to a still-PENDING_REVIEW artist is also reactivated to SEED -- an "
            + "explicit manual add fast-tracks a pending candidate the same way it un-rejects one")
    void reactivatesAPendingReviewMatchAsSeedThroughActivationService() {
        stubAbsorbedInsert("Foo Bar", 9L, "Foo Bar", ArtistStatus.PENDING_REVIEW);

        assertThat(service.addSeedIfNew(OWNER, "Foo Bar")).isTrue();
        verify(activationService).changeStatus(9L, OWNER, ArtistStatus.SEED);
        verify(artistRepository, never()).save(any(Artist.class));
    }

    @Test
    @DisplayName("issue #117: re-adding a name the owner had REMOVED from their list reactivates it "
            + "too -- REMOVED is inactive, so the add is real intent, not a duplicate")
    void reactivatesARemovedMatchAsSeedThroughActivationService() {
        stubAbsorbedInsert("Formerly Seeded Band", 11L, "Formerly Seeded Band", ArtistStatus.REMOVED);

        assertThat(service.addSeedIfNew(OWNER, "Formerly Seeded Band")).isTrue();
        verify(activationService).changeStatus(11L, OWNER, ArtistStatus.SEED);
    }

    @Test
    @DisplayName("issue #133: the race loser -- a concurrent call already committed this exact name, "
            + "so this call's insert is absorbed, it finds the winner's already-active SEED row, "
            + "reports false, and never fires onSeedCreated for what is someone else's insert")
    void raceLoserReportsFalseAndDoesNotFireOnSeedCreated() {
        stubAbsorbedInsert("Nebraska", 3L, "Nebraska", ArtistStatus.SEED);

        boolean added = service.addSeedIfNew(OWNER, "Nebraska");

        assertThat(added).as("the race loser did not cause a new seed -- the winner did").isFalse();
        verify(artistRepository, never()).save(any(Artist.class));
        verify(artistRepository, never()).findByOwnerAndName(anyString(), anyString());
        verify(activationService, never()).onSeedCreated(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("issue #179: losing the race to a RelationDiscovered insert now PROMOTES the "
            + "resulting PENDING_REVIEW row to SEED, instead of leaving the artist the user "
            + "explicitly asked for sitting in the review queue")
    void losingTheRaceToADiscoveryPromotesThatRowToSeed() {
        stubAbsorbedInsert("Nebraska", 4L, "Nebraska", ArtistStatus.PENDING_REVIEW);

        assertThat(service.addSeedIfNew(OWNER, "Nebraska")).isTrue();
        verify(activationService).changeStatus(4L, OWNER, ArtistStatus.SEED);
    }

    @Test
    @DisplayName("issue #179: an absorbed insert that no artist matches is a contradiction between "
            + "the DB constraint and the normalizer -- it fails loudly naming the owner and name, "
            + "rather than silently reporting 'nothing was added'")
    void anAbsorbedInsertWithNoMatchFailsLoudly() {
        when(artistNameMatcher.findExistingMatch(OWNER, "Ghost Band")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addSeedIfNew(OWNER, "Ghost Band"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OWNER)
                .hasMessageContaining("Ghost Band");
    }

    @Test
    @DisplayName("two genuinely different names both get added as distinct seeds")
    void twoGenuinelyDifferentArtistsBothGetAdded() {
        Artist radiohead = stubWinningInsert("Radiohead");
        Artist tribute = stubWinningInsert("Radioheads Tribute Band");

        assertThat(service.addSeedIfNew(OWNER, "Radiohead")).isTrue();
        assertThat(service.addSeedIfNew(OWNER, "Radioheads Tribute Band")).isTrue();

        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService).onSeedCreated(radiohead);
        verify(activationService).onSeedCreated(tribute);
    }

    @Test
    @DisplayName("owner is passed through unchanged to the insert (owner-scoped writes)")
    void ownerIsPassedThroughToTheInsert() {
        stubWinningInsert("Wilco");

        service.addSeedIfNew(OWNER, "Wilco");

        verify(artistRepository).insertIfAbsent(eq(OWNER), eq("Wilco"),
                eq(ArtistNameNormalizer.normalize("Wilco")), eq(ArtistSource.SEED_LIST.name()),
                eq(ArtistStatus.SEED.name()), isNull(), isNull(), any(Instant.class));
    }

    @Test
    @DisplayName("issue #124: an en-dash/hyphen punctuation variant of an existing active artist is "
            + "not duplicated -- proven with the REAL ArtistNameMatcher wired against a mocked "
            + "repository, not a mocked matcher, so the actual normalization is exercised")
    void enDashVariantOfAnExistingActiveArtistIsNotDuplicated() {
        ArtistSeedService realWiredService =
                new ArtistSeedService(artistRepository, activationService, new ArtistNameMatcher(artistRepository));
        ArtistNameStatusView existing = view(3L, "Only Murders In The Building - Cast", ArtistStatus.SEED);
        when(artistRepository.findByOwnerAndNormalizedName(OWNER,
                ArtistNameNormalizer.normalize("Only Murders in the Building – Cast")))
                .thenReturn(Optional.of(existing));

        boolean added = realWiredService.addSeedIfNew(OWNER, "Only Murders in the Building – Cast");

        assertThat(added).as("en-dash variant of an existing SEED artist is a duplicate, not a new row")
                .isFalse();
        verify(artistRepository, never()).save(any(Artist.class));
        verify(activationService, never()).changeStatus(any(), any(), any());
    }
}
