package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.TicketmasterService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketmasterShowSourceTest {
    private final TicketmasterService ticketmaster = mock(TicketmasterService.class);
    private final TicketmasterShowSource source = new TicketmasterShowSource(ticketmaster);

    @Test
    void idIsTicketmaster() {
        assertThat(source.id()).isEqualTo("ticketmaster");
    }

    @Test
    void delegatesToTicketmasterWithMappedArgs() {
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(6);
        Show mockShow = mock(Show.class);
        List<Show> expected = List.of(mockShow);
        when(ticketmaster.searchShows(eq("ZZ Top"), eq("78701"), eq(30.26), eq(-97.74), eq(50), eq(start), eq(end)))
                .thenReturn(expected);
        ScanQuery q = new ScanQuery("ZZ Top", null, "78701", 30.26, -97.74, 50, "Austin", "TX", start, end);

        assertThat(source.search(q)).isSameAs(expected);
    }
}
