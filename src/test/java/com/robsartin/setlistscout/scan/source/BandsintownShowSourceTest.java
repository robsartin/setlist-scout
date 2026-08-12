package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandsintownService;
import com.robsartin.setlistscout.scan.Show;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BandsintownShowSourceTest {
    private final BandsintownService bandsintown = mock(BandsintownService.class);
    private final BandsintownShowSource source = new BandsintownShowSource(bandsintown);

    @Test
    void idIsBandsintown() {
        assertThat(source.id()).isEqualTo("bandsintown");
    }

    @Test
    void delegatesWithLatLongRadiusWindow() {
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(6);
        Show mockShow = mock(Show.class);
        List<Show> expected = List.of(mockShow);
        when(bandsintown.searchShows(eq("ZZ Top"), eq(30.26), eq(-97.74), eq(50), eq(start), eq(end)))
                .thenReturn(expected);
        ScanQuery q = new ScanQuery("ZZ Top", null, "78701", 30.26, -97.74, 50, "Austin", start, end);

        assertThat(source.search(q)).isSameAs(expected);
    }
}
