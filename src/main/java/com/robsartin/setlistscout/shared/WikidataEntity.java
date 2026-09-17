package com.robsartin.setlistscout.shared;

/** One Wikidata entity, as resolved for an artist: its QID and the label/description matched. */
public record WikidataEntity(String qid, String label, String description) {
}
