package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScanJobRepository extends JpaRepository<ScanJob, Long> {
    Optional<ScanJob> findByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);
    List<ScanJob> findByOwnerAndArtistId(String owner, Long artistId);
    List<ScanJob> findByOwner(String owner);
    void deleteByOwnerAndArtistId(String owner, Long artistId);
    boolean existsByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);
}
