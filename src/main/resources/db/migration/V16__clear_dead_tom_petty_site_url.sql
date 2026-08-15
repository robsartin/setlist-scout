-- Issue #147: production's cached `artist.official_site_url` for "Tom petty" is a dead 1996
-- Wayback Machine snapshot of Warner Bros' old tompetty page. MusicBrainz's "official homepage"
-- relation apparently still points at it, and `ScanUnitRunner#resolveSiteUrl` caches the
-- official-site URL once and reuses it forever (no re-resolution or staleness check) -- so every
-- band-site scan for this artist has been timing out (SocketTimeoutException), contributing zero
-- shows from that source for an artist whose tribute acts very much have real upcoming shows.
--
-- Scoped to the immediate data fix only: this is a narrow, targeted UPDATE for the one known-dead
-- value, not the general "N consecutive failures clears the cache" staleness-detection mechanism
-- the issue explicitly deferred as a separate, bigger design question.
--
-- Clears (NULLs) the cached URL so `resolveSiteUrl` re-resolves it via MusicBrainz on the artist's
-- next scan (`if (url == null) { ... musicBrainz.findOfficialHomepage(...) ... }`) -- it may or may
-- not find a better one; the point is only to stop being permanently stuck on a URL that is
-- provably dead.
--
-- Deliberately narrow: matches on the exact dead URL value AND the artist name (case-insensitive),
-- not just "any artist named Tom petty" -- a name-only match risks clearing a URL that happens to
-- be fine for some other "Tom petty" row (e.g. a different owner's already-correctly-resolved
-- artist). A safe no-op anywhere this specific URL isn't cached; the `official_site_url = '...'`
-- comparison is NULL-safe (a NULL cached URL never equals the literal, so rows with no cached URL
-- are untouched without needing an explicit IS NOT NULL guard). Never deletes the row or touches
-- any other column.
UPDATE artist
   SET official_site_url = NULL
 WHERE lower(name) = lower('Tom petty')
   AND official_site_url = 'https://web.archive.org/web/19961222160524/http://www.wbr.com/tompetty/';
