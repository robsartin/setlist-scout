package com.robsartin.setlistscout.scheduler;

import com.robsartin.setlistscout.domain.SearchSettings;
import com.robsartin.setlistscout.repository.SearchSettingsRepository;
import com.robsartin.setlistscout.service.ExpansionService;
import com.robsartin.setlistscout.service.ShowAggregationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Eager despite spring.main.lazy-initialization=true, so its @Scheduled scan registers at startup.
@Lazy(false)
@Component
public class ShowScanScheduler {

    private final ExpansionService expansionService;
    private final ShowAggregationService showAggregationService;
    private final SearchSettingsRepository settingsRepository;

    public ShowScanScheduler(ExpansionService expansionService,
                             ShowAggregationService showAggregationService,
                             SearchSettingsRepository settingsRepository) {
        this.expansionService = expansionService;
        this.showAggregationService = showAggregationService;
        this.settingsRepository = settingsRepository;
    }

    // Every 3 days. Interval is in application.yml (setlistscout.scan-interval-ms) --
    // change it there without touching code. Runs per user (each owner with a settings row).
    @Scheduled(fixedRateString = "${setlistscout.scan-interval-ms:259200000}")
    public void scan() {
        for (SearchSettings settings : settingsRepository.findAll()) {
            String owner = settings.getOwner();
            expansionService.expandAll(owner);       // surfaces new PENDING_REVIEW candidates only
            showAggregationService.scanForShows(owner); // searches SEED + APPROVED artists only
        }
    }
}
