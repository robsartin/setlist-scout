package com.robsartin.setlistscout.repository;

import com.robsartin.setlistscout.domain.Show;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ShowRepository extends JpaRepository<Show, Long> {
    List<Show> findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(String owner, LocalDateTime start, LocalDateTime end);
    boolean existsByOwnerAndArtistNameAndEventDateTimeAndVenueName(String owner, String artistName, LocalDateTime eventDateTime, String venueName);
}
