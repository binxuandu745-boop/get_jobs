package com.getjobs.application.service;

import com.getjobs.worker.manager.BrowserPlatformMode;
import org.springframework.stereotype.Service;

/** Persists the explicit browser mode without loading unrelated configuration values. */
@Service
public class BrowserModePreferenceStore {
    private static final String CONFIG_KEY = "BROWSER_PLATFORM_MODE";
    private static final String VALID_MODES_MESSAGE = "Browser platform mode must be boss, liepin, 51job, zhilian or legacy all";

    private final ConfigService configService;

    public BrowserModePreferenceStore(ConfigService configService) {
        this.configService = configService;
    }

    /** Returns null when no mode has been saved, so the caller can apply its startup default. */
    public String readSavedMode() {
        final String savedMode;
        try {
            savedMode = configService.getConfigValue(CONFIG_KEY);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Unable to read saved browser platform mode: "
                    + failure.getClass().getSimpleName());
        }

        if (savedMode == null || savedMode.isBlank()) {
            return null;
        }
        try {
            return normalizeExplicitMode(savedMode);
        } catch (IllegalArgumentException invalidMode) {
            throw new IllegalStateException("Saved browser platform mode must be boss, liepin, 51job, zhilian or legacy all");
        }
    }

    /** Saves an explicit supported mode; validation happens before any configuration access. */
    public void save(String mode) {
        String normalizedMode = normalizeExplicitMode(mode);
        final boolean saved;
        try {
            saved = configService.updateConfig(CONFIG_KEY, normalizedMode);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Unable to save browser platform mode: "
                    + failure.getClass().getSimpleName());
        }
        if (!saved) {
            throw new IllegalStateException("Unable to save browser platform mode");
        }
    }

    private static String normalizeExplicitMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException(VALID_MODES_MESSAGE);
        }
        try {
            return BrowserPlatformMode.resolve(mode, false).value();
        } catch (IllegalArgumentException invalidMode) {
            throw new IllegalArgumentException(VALID_MODES_MESSAGE);
        }
    }
}
