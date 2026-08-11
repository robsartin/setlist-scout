package com.robsartin.setlistscout.repository;

import com.robsartin.setlistscout.domain.SearchSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SearchSettingsRepository extends JpaRepository<SearchSettings, Long> {
    Optional<SearchSettings> findByOwner(String owner);
}
