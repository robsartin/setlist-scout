package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.Show;
import java.util.List;

/** A single show-search source (Ticketmaster, Bandsintown, band-site …). Query-only: never writes. */
public interface ShowSource {
    /** Stable source key used in logs and (Phase B) the scan_job.source column. */
    String id();

    List<Show> search(ScanQuery query);
}
