package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    private static final String OWNER = "rob@example.com";

    private ArtistRepository artistRepository;
    private ExpandJobRepository expandJobRepository;
    private CurrentUser currentUser;
    private ArtistActivationService activationService;
    private ReviewController controller;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        expandJobRepository = mock(ExpandJobRepository.class);
        currentUser = mock(CurrentUser.class);
        activationService = mock(ArtistActivationService.class);
        when(currentUser.email()).thenReturn(OWNER);
        controller = new ReviewController(artistRepository, expandJobRepository, currentUser, activationService);
    }

    private static Artist pending(String name, ArtistSource source, long id) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", "note");
        ReflectionTestUtils.setField(artist, "id", id);
        return artist;
    }

    @Test
    @DisplayName("approveAllPending delegates to the activation service for every pending artist")
    void approveAllPendingApprovesEveryone() {
        Artist tribute = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION, 1L);
        Artist similar = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION, 2L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).thenReturn(List.of(tribute, similar));

        controller.approveAllPending(null, new ConcurrentModel());

        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.APPROVED);
        verify(activationService).changeStatus(2L, OWNER, ArtistStatus.APPROVED);
    }

    @Test
    @DisplayName("rejectAllPending delegates to the activation service for every pending artist")
    void rejectAllPendingRejectsEveryone() {
        Artist tribute = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION, 1L);
        Artist similar = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION, 2L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).thenReturn(List.of(tribute, similar));

        controller.rejectAllPending(null, new ConcurrentModel());

        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.REJECTED);
        verify(activationService).changeStatus(2L, OWNER, ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("review delegates accept/reject decisions to the activation service and skips later")
    void reviewAppliesEachDecision() {
        Artist accept = pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION, 1L);
        Artist reject = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION, 2L);
        Artist later = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION, 3L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(accept, reject, later));

        String view = controller.review(Map.of(
                "decision-1", "accept",
                "decision-2", "reject",
                "decision-3", "later"));

        assertThat(view).isEqualTo("redirect:/artists");
        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.APPROVED);
        verify(activationService).changeStatus(2L, OWNER, ArtistStatus.REJECTED);
        verify(activationService, never()).changeStatus(eq(3L), any(), any());
    }

    @Test
    @DisplayName("an artist with no submitted decision defaults to Later (left pending)")
    void reviewLeavesUndecidedPending() {
        Artist later = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION, 7L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(later));

        controller.review(Map.of()); // nothing submitted for this artist

        verify(activationService, never()).changeStatus(eq(7L), any(), any());
    }

    @Test
    @DisplayName("unreject delegates to the activation service to move a rejected artist back to pending")
    void unrejectMovesBackToPending() {
        String view = controller.unreject(9L);

        assertThat(view).isEqualTo("redirect:/artists");
        verify(activationService).changeStatus(9L, OWNER, ArtistStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("remove delegates to the activation service to reject an active artist")
    void removeRejectsActiveArtist() {
        String view = controller.remove(4L);

        assertThat(view).isEqualTo("redirect:/artists");
        verify(activationService).changeStatus(4L, OWNER, ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("expandNow re-dues the owner's expand jobs and redirects (no-JS fallback)")
    void expandNowRedirects() {
        String view = controller.expandNow(null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists");
        verify(expandJobRepository).redueAll(eq(OWNER), any(Instant.class));
    }

    @Test
    @DisplayName("expandNow (htmx) re-dues the owner's expand jobs and returns the pending fragment")
    void expandNowHtmxReturnsPendingFragment() {
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).thenReturn(List.of());

        String view = controller.expandNow("hx", new ConcurrentModel());

        assertThat(view).isEqualTo("artists :: pendingSection");
        verify(expandJobRepository).redueAll(eq(OWNER), any(Instant.class));
    }
}
