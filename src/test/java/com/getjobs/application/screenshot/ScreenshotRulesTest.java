package com.getjobs.application.screenshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotRulesTest {
    @Test
    void defaultsAreSeparateFromBrowserOrDeliveryConfiguration() {
        var defaults = ScreenshotRules.defaults();
        assertEquals(new BigDecimal("125"), defaults.minimumDailySalary());
        assertEquals(new BigDecimal("5000"), defaults.minimumMonthlySalary());
        assertEquals(2028, defaults.graduationYear());
        assertTrue(defaults.requireInternship());
        assertTrue(defaults.cities().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1000001", "0.001"})
    void invalidAmountsAreRejected(String amount) {
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotRules(List.of("Java"), true,
                new BigDecimal(amount), BigDecimal.ZERO, List.of(), null));
    }

    @Test
    void rejectsMissingOrOverlongRulesRatherThanSilentlyWeakeningThem() {
        assertThrows(IllegalArgumentException.class, () -> rules(null));
        assertThrows(IllegalArgumentException.class, () -> rules(List.of()));
        assertThrows(IllegalArgumentException.class, () -> rules(List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> rules(List.of("x".repeat(81))));
        assertThrows(IllegalArgumentException.class, () -> rules(Collections.nCopies(21, "java")));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotRules(List.of("java"), true,
                null, BigDecimal.ZERO, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotRules(List.of("java"), true,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), 2101));
    }

    @Test
    void acceptsExplicitZeroThresholdAndCopiesInputLists() {
        ArrayList<String> terms = new ArrayList<>(List.of(" Java ", "Java"));
        ScreenshotRules rules = rules(terms);
        terms.add("other");
        assertEquals(List.of("Java"), rules.roleTerms());
        assertThrows(UnsupportedOperationException.class, () -> rules.roleTerms().add("SQL"));
    }

    @Test
    void evaluationStatusMustBeKnownAndOutputListsCannotBeChanged() {
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotEvaluation("SEND", List.of(), List.of()));
        var result = new ScreenshotEvaluation("ERROR", List.of("识别失败"), List.of());
        assertThrows(UnsupportedOperationException.class, () -> result.reasons().add("发送"));
    }

    private ScreenshotRules rules(List<String> terms) {
        return new ScreenshotRules(terms, true, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null);
    }
}
