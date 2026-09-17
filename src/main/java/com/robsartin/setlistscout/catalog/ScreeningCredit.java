package com.robsartin.setlistscout.catalog;

import java.util.List;

/** Why a screening is worth seeing: a person the owner follows, and what they did on the film. */
public record ScreeningCredit(String artistName, List<String> roles) {
}
