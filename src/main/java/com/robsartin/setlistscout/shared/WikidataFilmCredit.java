package com.robsartin.setlistscout.shared;

/** One person-to-film credit from Wikidata: the film, and the role that person had on it. */
public record WikidataFilmCredit(String qid, String title, Integer releaseYear, String role) {
}
