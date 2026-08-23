package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.events.ArtistSiteUrlChanged;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Issue #248: {@code ShowRetirementListener} turns {@link ArtistSiteUrlChanged} into exactly one
 * scoped delete -- {@code owner + artistId + "band-site:" + host(oldUrl)} -- against {@link
 * ShowRepository}, reusing {@code BandSiteScraperService#domainOf} so the derived source string is
 * guaranteed to match whatever that same method produced when the stale rows were originally
 * scraped (never a second, hand-rolled URL-to-host derivation that could drift from it).
 */
class ShowRetirementListenerTest {

    @Test
    void derivesTheBandSiteSourceForTheOldUrlsHostAndDeletesOnlyThatOwnerArtistAndSource() {
        ShowRepository showRepository = mock(ShowRepository.class);
        ShowRetirementListener listener = new ShowRetirementListener(showRepository);

        listener.onArtistSiteUrlChanged(
                new ArtistSiteUrlChanged("rob.sartin@gmail.com", 37957L, "https://www.austinsymphony.org/"));

        verify(showRepository).deleteByOwnerAndArtistIdAndSourceAndHiddenAtIsNull(
                "rob.sartin@gmail.com", 37957L, "band-site:www.austinsymphony.org");
        verifyNoMoreInteractions(showRepository);
    }

    @Test
    void aBareHostOldUrlWithNoPathStillDerivesTheSameSource() {
        ShowRepository showRepository = mock(ShowRepository.class);
        ShowRetirementListener listener = new ShowRetirementListener(showRepository);

        listener.onArtistSiteUrlChanged(
                new ArtistSiteUrlChanged("owner", 5L, "https://example.com"));

        verify(showRepository).deleteByOwnerAndArtistIdAndSourceAndHiddenAtIsNull(
                "owner", 5L, "band-site:example.com");
    }
}
