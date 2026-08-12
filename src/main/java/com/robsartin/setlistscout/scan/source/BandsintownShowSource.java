package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandsintownService;
import com.robsartin.setlistscout.scan.Show;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Bandsintown show search behind the {@link ShowSource} port (distance-filtered by the geocoded lat/long). */
@Component
@Order(2)
public class BandsintownShowSource implements ShowSource {

    private final BandsintownService bandsintown;

    public BandsintownShowSource(BandsintownService bandsintown) {
        this.bandsintown = bandsintown;
    }

    @Override
    public String id() {
        return "bandsintown";
    }

    @Override
    public List<Show> search(ScanQuery q) {
        return bandsintown.searchShows(q.artistName(), q.latitude(), q.longitude(), q.radiusMiles(),
                q.windowStart(), q.windowEnd());
    }
}
