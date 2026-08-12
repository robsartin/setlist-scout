package com.robsartin.setlistscout.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SearchSettingsRepository extends JpaRepository<SearchSettings, Long> {
    Optional<SearchSettings> findByOwner(String owner);
}
