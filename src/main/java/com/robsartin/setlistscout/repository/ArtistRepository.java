package com.robsartin.setlistscout.repository;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ArtistRepository extends JpaRepository<Artist, Long> {
    Optional<Artist> findByNameIgnoreCase(String name);
    List<Artist> findByStatus(ArtistStatus status);
    List<Artist> findByStatusIn(List<ArtistStatus> statuses);
    boolean existsByNameIgnoreCase(String name);
}
