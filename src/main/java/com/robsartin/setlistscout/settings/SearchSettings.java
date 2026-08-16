package com.robsartin.setlistscout.settings;

import jakarta.persistence.*;

/**
 * Per-user search parameters -- one row per owner (the signed-in user's email). Kept in
 * the DB (not application.yml) so each user edits their own location/radius/window from
 * the web UI without a redeploy.
 */
@Entity
public class SearchSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String owner;

    private String postalCode;
    private String city;   // derived from the ZIP geocode, for display
    private String state;  // derived from the ZIP geocode, for display
    private Double latitude;   // geocoded from postalCode; used for Ticketmaster's geoPoint search
                                // and Bandsintown's client-side distance filtering
    private Double longitude;
    private int radiusMiles;
    private int monthsAhead;

    protected SearchSettings() {
        // JPA
    }

    public SearchSettings(String owner, String city, String state, int radiusMiles, int monthsAhead) {
        this.owner = owner;
        this.city = city;
        this.state = state;
        this.radiusMiles = radiusMiles;
        this.monthsAhead = monthsAhead;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public int getRadiusMiles() { return radiusMiles; }
    public void setRadiusMiles(int radiusMiles) { this.radiusMiles = radiusMiles; }
    public int getMonthsAhead() { return monthsAhead; }
    public void setMonthsAhead(int monthsAhead) { this.monthsAhead = monthsAhead; }
}
