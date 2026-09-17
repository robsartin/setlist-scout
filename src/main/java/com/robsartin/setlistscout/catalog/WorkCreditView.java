package com.robsartin.setlistscout.catalog;

import java.util.List;

/** One film on an artist's page: the work, and every role that artist had on it. */
public record WorkCreditView(String title, Integer releaseYear, List<String> roles) {

    /** "Dune (1984)", or just the title when no year is known. */
    public String titleWithYear() {
        return releaseYear == null ? title : title + " (" + releaseYear + ")";
    }
}
