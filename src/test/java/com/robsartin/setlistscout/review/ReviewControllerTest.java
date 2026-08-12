package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpansionService;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    private static final String OWNER = "rob@example.com";

    private ArtistRepository artistRepository;
    private ExpansionService expansionService;
    private CurrentUser currentUser;
    private ReviewController controller;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        expansionService = mock(ExpansionService.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        controller = new ReviewController(artistRepository, expansionService, currentUser);
    }

    private static Artist pending(String name, ArtistSource source) {
        return new Artist(name, source, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", "note");
    }

    @Test
    @DisplayName("approveAllPending approves every pending artist")
    void approveAllPendingApprovesEveryone() {
        Artist tribute = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION);
        Artist similar = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).thenReturn(List.of(tribute, similar));

        controller.approveAllPending(null, new ConcurrentModel());

        assertThat(tribute.getStatus()).isEqualTo(ArtistStatus.APPROVED);
        assertThat(similar.getStatus()).isEqualTo(ArtistStatus.APPROVED);
        verify(artistRepository).save(tribute);
        verify(artistRepository).save(similar);
    }

    @Test
    @DisplayName("rejectAllPending rejects every pending artist")
    void rejectAllPendingRejectsEveryone() {
        Artist tribute = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION);
        Artist similar = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).thenReturn(List.of(tribute, similar));

        controller.rejectAllPending(null, new ConcurrentModel());

        assertThat(tribute.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(similar.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        verify(artistRepository).save(tribute);
        verify(artistRepository).save(similar);
    }

    @Test
    @DisplayName("review applies accept/reject/later across the pending batch in one submit")
    void reviewAppliesEachDecision() {
        Artist accept = pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION);
        Artist reject = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION);
        Artist later = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        ReflectionTestUtils.setField(accept, "id", 1L);
        ReflectionTestUtils.setField(reject, "id", 2L);
        ReflectionTestUtils.setField(later, "id", 3L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(accept, reject, later));

        String view = controller.review(Map.of(
                "decision-1", "accept",
                "decision-2", "reject",
                "decision-3", "later"));

        assertThat(view).isEqualTo("redirect:/artists");
        assertThat(accept.getStatus()).isEqualTo(ArtistStatus.APPROVED);
        assertThat(reject.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(later.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        verify(artistRepository).save(accept);
        verify(artistRepository).save(reject);
        verify(artistRepository, never()).save(later);
    }

    @Test
    @DisplayName("an artist with no submitted decision defaults to Later (left pending)")
    void reviewLeavesUndecidedPending() {
        Artist later = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        ReflectionTestUtils.setField(later, "id", 7L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(later));

        controller.review(Map.of()); // nothing submitted for this artist

        assertThat(later.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        verify(artistRepository, never()).save(later);
    }

    @Test
    @DisplayName("unreject moves a rejected artist back to pending review")
    void unrejectMovesBackToPending() {
        Artist a = new Artist("Jackson Browne", ArtistSource.SIMILAR_EXPANSION,
                ArtistStatus.REJECTED, "via", "note");
        when(artistRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(a));

        String view = controller.unreject(9L);

        assertThat(view).isEqualTo("redirect:/artists");
        assertThat(a.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        verify(artistRepository).save(a);
    }

    @Test
    @DisplayName("remove takes an active artist off the list by rejecting it")
    void removeRejectsActiveArtist() {
        Artist a = new Artist("Wilco", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        when(artistRepository.findByIdAndOwner(4L, OWNER)).thenReturn(Optional.of(a));

        String view = controller.remove(4L);

        assertThat(view).isEqualTo("redirect:/artists");
        assertThat(a.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        verify(artistRepository).save(a);
    }
}
