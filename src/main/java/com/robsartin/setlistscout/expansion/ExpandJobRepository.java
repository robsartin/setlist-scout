package com.robsartin.setlistscout.expansion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExpandJobRepository extends JpaRepository<ExpandJob, Long> {
    Optional<ExpandJob> findByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);
    List<ExpandJob> findByOwnerAndArtistId(String owner, Long artistId);
    List<ExpandJob> findByOwner(String owner);
    void deleteByOwnerAndArtistId(String owner, Long artistId);
    boolean existsByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);
}
