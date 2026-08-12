package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.TicketmasterService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Ticketmaster show search behind the {@link ShowSource} port. */
@Component
@Order(1)
public class TicketmasterShowSource implements ShowSource {

    private final TicketmasterService ticketmaster;

    public TicketmasterShowSource(TicketmasterService ticketmaster) {
        this.ticketmaster = ticketmaster;
    }

    @Override
    public String id() {
        return "ticketmaster";
    }

    @Override
    public List<Show> search(ScanQuery q) {
        return ticketmaster.searchShows(q.artistName(), q.postalCode(), q.radiusMiles(),
                q.windowStart(), q.windowEnd());
    }
}
