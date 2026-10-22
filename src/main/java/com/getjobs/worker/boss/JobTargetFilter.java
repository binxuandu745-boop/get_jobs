package com.getjobs.worker.boss;

import java.util.List;
import java.util.Locale;

/** Verifies the role and internship requirement instead of trusting search recommendations. */
public final class JobTargetFilter {
    private JobTargetFilter() {}

    public static boolean matches(String title, String description, List<String> roleTerms,
                                  boolean requireInternship) {
        String name = title == null ? "" : title.toLowerCase(Locale.ROOT);
        List<String> terms = roleTerms == null ? List.of() : roleTerms.stream()
                .filter(term -> term != null && !term.isBlank()).toList();
        if (!terms.isEmpty() && terms.stream()
                .noneMatch(term -> name.contains(term.toLowerCase(Locale.ROOT)))) return false;
        return !requireInternship || name.contains("实习")
                || (description != null && description.contains("实习"));
    }
}
