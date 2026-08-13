package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.events.CandidateDiscovered;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpandUnitRunnerTest {

    private static final String OWNER = "rob@example.com";

    @Mock private ApplicationEventPublisher publisher;
    @Mock private RelationSource lastFmSource;

    @Test
    @DisplayName("should publish one CandidateDiscovered per related name, skipping blanks")
    void shouldPublishCandidatePerRelatedName() {
        lenient().when(lastFmSource.id()).thenReturn("lastfm");
        when(lastFmSource.classification()).thenReturn(ArtistSource.SIMILAR_EXPANSION);
        when(lastFmSource.note("Dawes")).thenReturn("similar to Dawes (via Last.fm)");
        when(lastFmSource.related("Dawes")).thenReturn(List.of("Nickel Creek", ""));

        ExpandUnitRunner runner = new ExpandUnitRunner(List.of(lastFmSource), publisher);

        runner.run(OWNER, 1L, "lastfm", "Dawes");

        ArgumentCaptor<CandidateDiscovered> captor = ArgumentCaptor.forClass(CandidateDiscovered.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new CandidateDiscovered(
                OWNER, "Nickel Creek", "SIMILAR_EXPANSION", "Dawes", "similar to Dawes (via Last.fm)"));
    }

    @Test
    @DisplayName("should no-op when no RelationSource matches the given sourceId")
    void shouldNoOpForUnknownSourceId() {
        lenient().when(lastFmSource.id()).thenReturn("lastfm");

        ExpandUnitRunner runner = new ExpandUnitRunner(List.of(lastFmSource), publisher);

        runner.run(OWNER, 1L, "unknown-source", "Dawes");

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
