package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
