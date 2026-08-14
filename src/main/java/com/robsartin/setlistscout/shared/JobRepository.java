package com.robsartin.setlistscout.shared;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

/**
 * Derived-finder base shared by scan.ScanJobRepository and expansion.ExpandJobRepository.
 * The native {@code @Query} methods (insertIfAbsent, claimDue, redueAll) stay on the concrete
 * repos -- their SQL hardcodes the table name (scan_job / expand_job), so they can't move here.
 */
@NoRepositoryBean
public interface JobRepository<T extends AbstractJob> extends JpaRepository<T, Long> {
    Optional<T> findByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);
    List<T> findByOwnerAndArtistId(String owner, Long artistId);
    List<T> findByOwner(String owner);
    void deleteByOwnerAndArtistId(String owner, Long artistId);
}
