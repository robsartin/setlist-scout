package com.robsartin.setlistscout.web;

import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;

/**
 * Flips the app's log level at runtime (no redeploy) via Spring's LoggingSystem -- the same call
 * Actuator's loggers endpoint makes. Ephemeral: resets to the LOG_LEVEL env default on restart.
 * Under /artists so it inherits the authenticated area and existing CSRF form handling.
 */
@Controller
public class LogLevelController {

    private static final String PACKAGE = "com.robsartin.setlistscout";

    private final LoggingSystem loggingSystem;

    public LogLevelController(LoggingSystem loggingSystem) {
        this.loggingSystem = loggingSystem;
    }

    @PostMapping("/artists/log-level")
    public String setLevel(@RequestParam String level) {
        try {
            loggingSystem.setLogLevel(PACKAGE, LogLevel.valueOf(level.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            // Unknown level (e.g. a typo) -- leave the current level unchanged.
        }
        return "redirect:/artists";
    }
}
