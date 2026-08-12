package com.robsartin.setlistscout.scheduler;

import com.robsartin.setlistscout.domain.SearchSettings;
import com.robsartin.setlistscout.observability.Correlation;
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
    // initialDelayString keeps the first run one interval out instead of firing at startup --
    // fixedRate with no initialDelay runs immediately on boot, so every deploy would otherwise
    // kick off a full scan + expansion of every owner.
    @Scheduled(fixedRateString = "${setlistscout.scan-interval-ms:259200000}",
               initialDelayString = "${setlistscout.scan-initial-delay-ms:259200000}")
    public void scan() {
        for (SearchSettings settings : settingsRepository.findAll()) {
            String owner = settings.getOwner();
            // surfaces new PENDING_REVIEW candidates only
            Correlation.run("expansion", owner, null, () -> expansionService.expandAll(owner));
            // searches SEED + APPROVED artists only
            Correlation.run("scan", owner, null, () -> showAggregationService.scanForShows(owner));
        }
    }
}
