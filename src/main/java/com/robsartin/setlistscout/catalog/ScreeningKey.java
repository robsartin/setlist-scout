package com.robsartin.setlistscout.catalog;

/**
 * What a screening offers as evidence of which film it is (#289): a normalized title and,
 * sometimes, a year. Deliberately not a {@code Show} -- {@code scan} owns that type, and the
 * matching rule has no business knowing about showtimes or venues.
 */
public record ScreeningKey(String normalizedTitle, Integer releaseYear) {
}
