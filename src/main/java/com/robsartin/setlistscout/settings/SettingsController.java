package com.robsartin.setlistscout.settings;

import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SettingsController {

    private final SettingsService settingsService;
    private final CurrentUser currentUser;

    public SettingsController(SettingsService settingsService, CurrentUser currentUser) {
        this.settingsService = settingsService;
        this.currentUser = currentUser;
    }

    @PostMapping("/settings")
    public String updateSettings(@RequestParam String postalCode,
                                  @RequestParam int radiusMiles,
                                  @RequestParam int monthsAhead) {
        settingsService.updateSettings(currentUser.email(), postalCode, radiusMiles, monthsAhead);
        return "redirect:/";
    }
}
