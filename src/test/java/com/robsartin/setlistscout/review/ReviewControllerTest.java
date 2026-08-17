package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.catalog.CandidateGroupCount;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.service.TestAppProperties;
import com.robsartin.setlistscout.shared.AdminGuard;
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
        AdminGuard adminGuard = new AdminGuard(currentUser, TestAppProperties.withKeys());
        controller = new ReviewController(artistRepository, expandJobRepository, currentUser, activationService,
                adminGuard);
    }

    private static Artist pending(String name, ArtistSource source, long id) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", "note");
        ReflectionTestUtils.setField(artist, "id", id);
        return artist;
    }

    /** Test double for the grouped-count projection, matching CandidateGroupsTest's own pattern. */
    private record GroupCountRow(String via, ArtistSource source, long count) implements CandidateGroupCount {
        @Override public String getVia() { return via; }
        @Override public ArtistSource getSource() { return source; }
        @Override public long getCount() { return count; }
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
    @DisplayName("approveAllPending announces how many candidates it approved")
    void approveAllPendingAnnouncesCount() {
        Artist a1 = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION, 1L);
        Artist a2 = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION, 2L);
        Artist a3 = pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION, 3L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(a1, a2, a3));

        ConcurrentModel model = new ConcurrentModel();
        controller.approveAllPending(null, model);

        ActionOutcome outcome = (ActionOutcome) model.asMap().get("outcome");
        assertThat(outcome.message()).isEqualTo("Approved all 3 remaining candidates.");
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
    @DisplayName("rejectAllPending announces how many candidates it rejected")
    void rejectAllPendingAnnouncesCount() {
        Artist a1 = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION, 1L);
        Artist a2 = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION, 2L);
        Artist a3 = pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION, 3L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(a1, a2, a3));

        ConcurrentModel model = new ConcurrentModel();
        controller.rejectAllPending(null, model);

        ActionOutcome outcome = (ActionOutcome) model.asMap().get("outcome");
        assertThat(outcome.message()).isEqualTo("Rejected all 3 remaining candidates.");
    }

    @Test
    @DisplayName("approve delegates to the activation service and redirects (no-JS fallback)")
    void approveRedirectsToCandidates() {
        String view = controller.approve(1L, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates");
        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.APPROVED);
    }

    @Test
    @DisplayName("approve (no-JS fallback) URL-encodes a via with spaces into the redirect Location")
    void approveRedirectUrlEncodesViaWithSpaces() {
        // Unlike approveRedirectsToCandidates above, the mocked repo here returns a non-empty
        // group -- CandidateGroups.resolve then picks it as current, giving actionResult's
        // resolvedVia != null branch a real value to URLEncoder.encode into the redirect.
        when(artistRepository.countByStatusGroupedByViaAndSource(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(new GroupCountRow("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION, 1)));

        String view = controller.approve(1L, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates?via=Tom+Petty+and+the+Heartbreakers");
        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.APPROVED);
    }

    @Test
    @DisplayName("approve (htmx) delegates to the activation service and returns the Candidates app fragment")
    void approveHtmxReturnsCandidatesAppFragment() {
        String view = controller.approve(1L, "hx", new ConcurrentModel());

        assertThat(view).isEqualTo("candidates :: candidatesApp");
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
        when(artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                OWNER, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION))
                .thenReturn(List.of(member1, member2));

        String view = controller.reviewGroup("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION,
                "approve", null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates");
        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.APPROVED);
        verify(activationService).changeStatus(2L, OWNER, ArtistStatus.APPROVED);
    }

    @Test
    @DisplayName("reviewGroup announces how many rows changed and which relation type")
    void reviewGroupAnnouncesCountAndRelationType() {
        Artist member1 = pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION, 1L);
        Artist member2 = pending("Benmont Tench", ArtistSource.MEMBER_EXPANSION, 2L);
        when(artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                OWNER, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION))
                .thenReturn(List.of(member1, member2));

        ConcurrentModel model = new ConcurrentModel();
        controller.reviewGroup("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION,
                "approve", null, model);

        ActionOutcome outcome = (ActionOutcome) model.asMap().get("outcome");
        assertThat(outcome.message()).isEqualTo("Approved 2 Members from Tom Petty and the Heartbreakers.");
    }

    @Test
    @DisplayName("reviewGroup (htmx) rejects every pending row in that group and returns the Candidates app fragment")
    void reviewGroupHtmxRejectsGroupAndReturnsFragment() {
        Artist member1 = pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION, 1L);
        when(artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                OWNER, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION))
                .thenReturn(List.of(member1));

        String view = controller.reviewGroup("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION,
                "reject", "hx", new ConcurrentModel());

        assertThat(view).isEqualTo("candidates :: candidatesApp");
        verify(activationService).changeStatus(1L, OWNER, ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("reviewGroup with a malformed decision does not mutate anything and still returns a result")
    void reviewGroupWithMalformedDecisionDoesNothing() {
        String view = controller.reviewGroup("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION,
                "bogus", null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates");
        verify(activationService, org.mockito.Mockito.never()).changeStatus(any(), any(), any());
        // Not verifyNoInteractions: actionResult now always re-resolves the current group (for
        // auto-advance) via populateCandidates, which legitimately reads countByStatusGroupedByViaAndSource
        // even on a no-op decision. What still must never happen is the mutation loop's own query.
        verify(artistRepository, org.mockito.Mockito.never()).findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                any(), any(), any(), any());
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
        String view = controller.expandNow(null, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists/candidates");
        verify(expandJobRepository).redueAll(eq(OWNER), any(Instant.class));
    }

    @Test
    @DisplayName("expandNow (htmx) re-dues the owner's expand jobs and returns the Candidates app fragment")
    void expandNowHtmxReturnsCandidatesAppFragment() {
        String view = controller.expandNow(null, "hx", new ConcurrentModel());

        assertThat(view).isEqualTo("candidates :: candidatesApp");
        verify(expandJobRepository).redueAll(eq(OWNER), any(Instant.class));
    }

    @Test
    @DisplayName("focus falls back to the group anchor when the chosen successor is no longer pending")
    void focusDowngradesWhenTheSuccessorVanishes() {
        Artist acted = pending("Alpha Centauri", ArtistSource.MEMBER_EXPANSION, 1L);
        Artist successor = pending("Bravo Company", ArtistSource.MEMBER_EXPANSION, 2L);
        Artist other = pending("Charlie Watts", ArtistSource.MEMBER_EXPANSION, 3L);
        when(artistRepository.findByIdAndOwner(1L, OWNER)).thenReturn(java.util.Optional.of(acted));
        when(artistRepository.countByStatusGroupedByViaAndSource(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(new GroupCountRow("Tom Petty and the Heartbreakers",
                        ArtistSource.MEMBER_EXPANSION, 1)));
        when(artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                OWNER, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers",
                ArtistSource.MEMBER_EXPANSION))
                // First call is the pre-mutation successor lookup: Bravo follows Alpha. Second is
                // the render, by which point another tab has decided Bravo -- so the successor the
                // response would point autofocus at isn't there any more.
                .thenReturn(List.of(acted, successor), List.of(other));

        ConcurrentModel model = new ConcurrentModel();
        controller.approve(1L, "hx", model);

        ActionOutcome outcome = (ActionOutcome) model.asMap().get("outcome");
        assertThat(outcome.focusesAnchor()).as("downgraded from ROW so no autofocus dangles").isTrue();
        assertThat(outcome.focusesRow(2L, "approve")).isFalse();
        assertThat(outcome.message()).isEqualTo("Approved Alpha Centauri.");
    }
}
