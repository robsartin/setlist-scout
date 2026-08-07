package com.robsartin.setlistscout.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Single-row table holding the live search parameters. Deliberately kept in the DB
 * (not application.yml) so it can be edited from the web UI without a redeploy --
 * e.g. tightening the radius from "Texas-wide" down to "near Austin only".
 */
@Entity
public class SearchSettings {

    @Id
    private Long id = 1L; // pinned singleton row

    private String city;
    private String state;
    private int radiusMiles;
    private int monthsAhead;

    protected SearchSettings() {
        // JPA
    }

    public SearchSettings(String city, String state, int radiusMiles, int monthsAhead) {
        this.id = 1L;
        this.city = city;
        this.state = state;
        this.radiusMiles = radiusMiles;
        this.monthsAhead = monthsAhead;
    }

    public Long getId() { return id; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public int getRadiusMiles() { return radiusMiles; }
    public void setRadiusMiles(int radiusMiles) { this.radiusMiles = radiusMiles; }
    public int getMonthsAhead() { return monthsAhead; }
    public void setMonthsAhead(int monthsAhead) { this.monthsAhead = monthsAhead; }
}
