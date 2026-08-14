package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.events.RelationDiscovered;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpandUnitRunnerTest {

    private static final String OWNER = "rob@example.com";

    @Mock private ApplicationEventPublisher publisher;
    @Mock private RelationSource lastFmSource;
    @Mock private TransactionTemplate transactionTemplate;

    /** Runs the publish callback synchronously so the mocked publisher sees the event. */
    private void runCallbacksInline() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("should publish one RelationDiscovered per related name, skipping blanks")
    void shouldPublishRelationPerRelatedName() {
        when(lastFmSource.id()).thenReturn("lastfm");
        when(lastFmSource.classification()).thenReturn(ArtistSource.SIMILAR_EXPANSION);
        when(lastFmSource.note("Dawes")).thenReturn("similar to Dawes (via Last.fm)");
        when(lastFmSource.related("Dawes")).thenReturn(List.of("Nickel Creek", ""));
        runCallbacksInline();

        ExpandUnitRunner runner = new ExpandUnitRunner(List.of(lastFmSource), publisher, transactionTemplate);

        runner.run(OWNER, 1L, "lastfm", "Dawes");

        ArgumentCaptor<RelationDiscovered> captor = ArgumentCaptor.forClass(RelationDiscovered.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new RelationDiscovered(
                OWNER, 1L, "Dawes", "Nickel Creek", "SIMILAR_EXPANSION", "lastfm", "similar to Dawes (via Last.fm)"));
    }

    @Test
    @DisplayName("should no-op when no RelationSource matches the given sourceId")
    void shouldNoOpForUnknownSourceId() {
        lenient().when(lastFmSource.id()).thenReturn("lastfm");

        ExpandUnitRunner runner = new ExpandUnitRunner(List.of(lastFmSource), publisher, transactionTemplate);

        runner.run(OWNER, 1L, "unknown-source", "Dawes");

        verify(publisher, never()).publishEvent(any());
    }
}
