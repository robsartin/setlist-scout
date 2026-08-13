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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    @DisplayName("approve delegates to the activation service and redirects (no-JS fallback)")
    void approveRedirectsToCandidates() {
        String view = controller.approve(1L, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates");
        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.APPROVED);
    }

    @Test
    @DisplayName("approve (htmx) delegates to the activation service and returns a bare row-removal fragment")
    void approveHtmxReturnsRowDoneFragment() {
        String view = controller.approve(1L, "hx", new ConcurrentModel());

        assertThat(view).isEqualTo("candidates :: rowDone");
        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.APPROVED);
    }

    @Test
    @DisplayName("reject delegates to the activation service and redirects (no-JS fallback)")
    void rejectRedirectsToCandidates() {
        String view = controller.reject(2L, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates");
        verify(activationService).changeStatus(2L, OWNER, ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("reviewGroup approves every pending row in that (via, type) group and redirects")
    void reviewGroupApprovesGroupAndRedirects() {
        Artist member1 = pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION, 1L);
        Artist member2 = pending("Benmont Tench", ArtistSource.MEMBER_EXPANSION, 2L);
        when(artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource(
                OWNER, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION))
                .thenReturn(List.of(member1, member2));

        String view = controller.reviewGroup("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION,
                "approve", null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates");
        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.APPROVED);
        verify(activationService).changeStatus(2L, OWNER, ArtistStatus.APPROVED);
    }

    @Test
    @DisplayName("reviewGroup (htmx) rejects every pending row in that group and returns the global bar fragment")
    void reviewGroupHtmxRejectsGroupAndReturnsFragment() {
        Artist member1 = pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION, 1L);
        when(artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource(
                OWNER, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION))
                .thenReturn(List.of(member1));

        String view = controller.reviewGroup("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION,
                "reject", "hx", new ConcurrentModel());

        assertThat(view).isEqualTo("candidates :: globalBar");
        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("reviewGroup with a malformed decision does not mutate anything and still returns a result")
    void reviewGroupWithMalformedDecisionDoesNothing() {
        String view = controller.reviewGroup("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION,
                "bogus", null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates");
        verify(activationService, org.mockito.Mockito.never()).changeStatus(any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(artistRepository);
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
    @DisplayName("expandNow re-dues the owner's expand jobs and redirects to Candidates (no-JS fallback)")
    void expandNowRedirects() {
        String view = controller.expandNow(null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates");
        verify(expandJobRepository).redueAll(eq(OWNER), any(Instant.class));
    }

    @Test
    @DisplayName("expandNow (htmx) re-dues the owner's expand jobs and returns the Candidates global bar fragment")
    void expandNowHtmxReturnsGlobalBarFragment() {
        String view = controller.expandNow("hx", new ConcurrentModel());

        assertThat(view).isEqualTo("candidates :: globalBar");
        verify(expandJobRepository).redueAll(eq(OWNER), any(Instant.class));
    }
}
