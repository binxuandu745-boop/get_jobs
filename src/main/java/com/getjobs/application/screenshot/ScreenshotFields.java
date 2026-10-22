package com.getjobs.application.screenshot;

import java.math.BigDecimal;
import java.util.List;

/** Observations from an uploaded screenshot, never instructions or delivery commands. */
public record ScreenshotFields(
        String jobTitle,
        String company,
        String city,
        String salaryText,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String salaryUnit,
        Integer daysPerWeek,
        Integer durationMonths,
        String education,
        List<String> skills,
        List<Integer> graduationYears,
        String description,
        List<String> uncertainFields) {

    public ScreenshotFields {
        skills = skills == null ? List.of() : List.copyOf(skills);
        graduationYears = graduationYears == null ? List.of() : List.copyOf(graduationYears);
        uncertainFields = uncertainFields == null ? List.of() : List.copyOf(uncertainFields);
        salaryUnit = salaryUnit == null ? "UNKNOWN" : salaryUnit;
    }
}
