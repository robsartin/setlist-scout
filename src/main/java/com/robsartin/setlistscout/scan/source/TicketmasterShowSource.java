package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.TicketmasterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ticketmaster show search behind the {@link ShowSource} port. On by default
 * ({@code matchIfMissing = true}) -- {@code setlistscout.sources.ticketmaster=false} opts this
 * source out with zero effect on the other 7 (issue #139).
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "setlistscout.sources.ticketmaster", havingValue = "true", matchIfMissing = true)
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
        return ticketmaster.searchShows(q.artistName(), q.postalCode(), q.latitude(), q.longitude(),
                q.radiusMiles(), q.windowStart(), q.windowEnd());
    }
}
