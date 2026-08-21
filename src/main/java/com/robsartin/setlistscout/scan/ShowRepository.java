package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShowRepository extends JpaRepository<Show, Long> {
    /** Every show in the window, hidden or not -- used when the "show hidden" toggle is on. */
    List<Show> findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(String owner, LocalDateTime start, LocalDateTime end);

    /** The default (toggle off) list: only shows nobody has hidden (issue #166). */
    List<Show> findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(String owner, LocalDateTime start, LocalDateTime end);

    /** How many shows in the window are hidden, regardless of the current toggle state -- feeds the toggle's discoverability count. */
    long countByOwnerAndEventDateTimeBetweenAndHiddenAtIsNotNull(String owner, LocalDateTime start, LocalDateTime end);

    boolean existsByOwnerAndArtistNameAndEventDateTimeAndVenueName(String owner, String artistName, LocalDateTime eventDateTime, String venueName);

    /** Owner-scoped lookup for hide/unhide -- absent for a foreign id, never a leak. */
    Optional<Show> findByIdAndOwner(Long id, String owner);

    /** Every show for an owner, unfiltered by window -- {@code VenueScanRunnerTest} (#206) reads this back. */
    List<Show> findByOwnerOrderByEventDateTimeAsc(String owner);

    /**
     * Per-source show counts, venue sources only (#206 Task 6, the {@code /venues} page's
     * "shows contributed" column). A {@code COUNT(*) ... GROUP BY}, never {@code
     * findAll().size()} -- CLAUDE.md/the task brief are explicit that this must scale with the
     * count, not the row set. Grouped by {@code source} (not one owner-wide total) because {@code
     * VenueScanRunner#persist} computes each venue's source as {@code "venue:" + host(calendarUrl)}
     * -- distinct venues get distinct source strings, so {@code VenueController} can attribute
     * each group back to the one venue whose own calendar-URL host produced it.
     */
    @Query("SELECT s.source AS source, COUNT(s) AS showCount FROM Show s "
            + "WHERE s.owner = :owner AND s.source LIKE 'venue:%' GROUP BY s.source")
    List<VenueSourceCount> countByOwnerGroupedByVenueSource(@Param("owner") String owner);

    /**
     * Add one show, idempotently (#206). {@code ON CONFLICT (owner, artist_name, event_date_time,
     * venue_name) DO NOTHING} against {@code show_event}'s existing unique constraint
     * ({@code show_event_owner_artist_name_event_date_time_venue_name_key}, V1/V2) -- a DB-level
     * upsert, never an {@code existsBy} pre-check-then-save (per CLAUDE.md: idempotent writes rely
     * on the constraint itself, not a read-then-write that can race). Unlike
     * {@code scan.ScanUnitRunner#persistNew}, which predates this rule, {@code VenueScanRunner}
     * (#206 Task 3) uses this instead of that read-then-write pattern.
     * <p>
     * {@code @Transactional} directly on this interface method -- mirrors
     * {@code ScanJobRepository#redueAll}'s exact rationale: this is a {@code @Modifying} native
     * query, which needs an ambient transaction to execute, and its caller ({@code
     * VenueScanRunner#run}) deliberately holds no transaction open across its own earlier,
     * potentially-slow scrape call (ADR-0024) -- so this method is self-transactional instead of
     * relying on the caller to supply one.
     *
     * @return 1 if this call inserted the show, 0 if it already existed for this owner
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO show_event
                (owner, artist_name, event_date_time, venue_name, venue_city, price, source, ticket_url, kind, discovered_at)
            VALUES
                (:owner, :artistName, :eventDateTime, :venueName, :venueCity, :price, :source, :ticketUrl, :kind, :discoveredAt)
            ON CONFLICT (owner, artist_name, event_date_time, venue_name) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("owner") String owner,
                        @Param("artistName") String artistName,
                        @Param("eventDateTime") LocalDateTime eventDateTime,
                        @Param("venueName") String venueName,
                        @Param("venueCity") String venueCity,
                        @Param("price") BigDecimal price,
                        @Param("source") String source,
                        @Param("ticketUrl") String ticketUrl,
                        @Param("kind") String kind,
                        @Param("discoveredAt") Instant discoveredAt);
}
