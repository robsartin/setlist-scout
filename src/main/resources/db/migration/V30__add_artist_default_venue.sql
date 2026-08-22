-- Issue #218: per-artist default venue name + city, used by the band-site scan path (BandSiteShowSource)
-- only when a scraped show has no venue of its own -- e.g. Austin Symphony Orchestra's own
-- season-announcement page names no hall anywhere. Nullable: most artists never need this.
--
-- Both columns, not just a name: BandSiteShowSource#withinRange geocodes venueCity to filter shows
-- by real distance, and treats a null city the same as "no match" (falls through to a city-name
-- compare that's also false for null). A default that supplied only a name would leave city null,
-- so every defaulted show would extract successfully and then silently vanish at the distance
-- filter -- the same shape as issue #211. Deliberately a general per-artist mechanism, not a
-- hardcoded "ASO -> Long Center": editable from the artists page like official_site_url already is.
ALTER TABLE artist ADD COLUMN default_venue_name varchar(255);
ALTER TABLE artist ADD COLUMN default_venue_city varchar(255);
