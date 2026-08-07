package com.robsartin.setlistscout.repository;

import com.robsartin.setlistscout.domain.SearchSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchSettingsRepository extends JpaRepository<SearchSettings, Long> {
}
