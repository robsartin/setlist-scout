package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.shared.AdminGuard;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Issue #229, Part A.1: {@code otherOwnerEmails} used to exclude the ADMIN's own address
 * unconditionally -- correct only while the admin was the sole possible caller of {@code
 * SharedScanController#create}. Now that any signed-in, allow-listed user can create a shared
 * scan, that exclusion must track the CALLER instead, or a non-admin would be offered "pair with
 * yourself" in the dropdown and never offered the admin at all.
 * <p>
 * Deliberately a plain Mockito unit test, not a {@code @SpringBootTest}/Testcontainers one: none
 * of {@code otherOwnerEmails}' inputs (allow-listed emails, the caller's email) touch a database,
 * so a full context is unnecessary weight for what this needs to prove. {@code
 * SharedScanControllerTest} separately confirms the same fix end-to-end through the rendered
 * page, once #229's Part A also drops the template's {@code isAdmin} gate on the create form.
 * <p>
 * {@code otherOwnerEmails} has a second consumer this fix must not break: {@code shows.html}'s
 * and {@code candidates.html}'s admin cross-account "scan/expand for" dropdowns (#136). Both
 * templates gate that dropdown's whole container on {@code isAdmin}, so the caller there is
 * always the admin -- and for an admin caller, "exclude the caller" and "exclude the admin" are
 * the same list ({@link #excludesAdminWhenAdminIsTheCaller} below pins this). No separate model
 * attribute is needed.
 */
@ExtendWith(MockitoExtension.class)
class NavModelAdviceTest {

    private static final String ADMIN = "rob.sartin@gmail.com";
    private static final String DAVID = "davidbuley01@gmail.com";
    private static final String SPENCER = "spencerwon@gmail.com";

    @Mock private ArtistRepository artistRepository;
    @Mock private CurrentUser currentUser;
    @Mock private AdminGuard adminGuard;

    private NavModelAdvice advice;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties(
                new AppProperties.Auth(List.of(ADMIN, DAVID, SPENCER), ADMIN, ADMIN),
                null, null);
        advice = new NavModelAdvice(artistRepository, currentUser, appProperties, adminGuard);
    }

    @Test
    @DisplayName("excludes the caller, not the admin -- a non-admin caller is still offered the admin")
    void excludesCallerIncludesAdminForNonAdminCaller() {
        when(currentUser.email()).thenReturn(DAVID);

        assertThat(advice.otherOwnerEmails()).containsExactlyInAnyOrder(ADMIN, SPENCER);
    }

    @Test
    @DisplayName("still excludes the admin when the admin themselves is the caller -- the two "
            + "consumers gated on isAdmin (shows.html, candidates.html) never see a behaviour change")
    void excludesAdminWhenAdminIsTheCaller() {
        when(currentUser.email()).thenReturn(ADMIN);

        assertThat(advice.otherOwnerEmails()).containsExactlyInAnyOrder(DAVID, SPENCER);
    }
}
