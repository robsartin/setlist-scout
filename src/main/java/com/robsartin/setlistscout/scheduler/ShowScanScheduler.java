package com.robsartin.setlistscout.scheduler;

import com.robsartin.setlistscout.service.ExpansionService;
import com.robsartin.setlistscout.service.ShowAggregationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ShowScanScheduler {

    private final ExpansionService expansionService;
    private final ShowAggregationService showAggregationService;

    public ShowScanScheduler(ExpansionService expansionService, ShowAggregationService showAggregationService) {
        this.expansionService = expansionService;
        this.showAggregationService = showAggregationService;
    }

    // Every 3 days. Interval is in application.yml (setlistscout.scan-interval-ms) --
    // change it there without touching code.
    @Scheduled(fixedRateString = "${setlistscout.scan-interval-ms:259200000}")
    public void scan() {
        expansionService.expandAll();       // surfaces new PENDING_REVIEW candidates only
        showAggregationService.scanForShows(); // searches SEED + APPROVED artists only
    }
}
