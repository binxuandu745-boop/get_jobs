package com.getjobs.worker.manager;

import java.util.Locale;

/** Resolves browser platform selection without consulting configuration storage or the environment. */
public enum BrowserPlatformMode {
    BOSS("boss"),
    LIEPIN("liepin"),
    JOB51("51job"),
    ZHILIAN("zhilian"),
    ALL("all"); // Legacy configuration only; the new selector offers one website at a time.

    private final String value;

    BrowserPlatformMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** An explicit mode overrides the legacy flag; invalid modes must never enable every platform. */
    public static BrowserPlatformMode resolve(String platformMode, boolean legacyBossOnly) {
        if (platformMode == null || platformMode.trim().isEmpty()) {
            return legacyBossOnly ? BOSS : ALL;
        }
        return switch (platformMode.trim().toLowerCase(Locale.ROOT)) {
            case "boss" -> BOSS;
            case "liepin" -> LIEPIN;
            case "51job" -> JOB51;
            case "zhilian" -> ZHILIAN;
            case "all" -> ALL;
            default -> throw new IllegalArgumentException("Browser platform mode must be boss, liepin, 51job, zhilian or legacy all");
        };
    }

    /** Unknown platforms, including legacy aliases, are disabled even in ALL mode. */
    public boolean enables(String platform) {
        if (platform == null) return false;
        return switch (platform.trim().toLowerCase(Locale.ROOT)) {
            case "boss" -> this == BOSS || this == ALL;
            case "liepin" -> this == LIEPIN || this == ALL;
            case "51job" -> this == JOB51 || this == ALL;
            case "zhilian" -> this == ZHILIAN || this == ALL;
            default -> false;
        };
    }
}
