package com.getjobs.application.screenshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotEvaluatorTest {
    private final ScreenshotFieldParser parser = new ScreenshotFieldParser();
    private final ScreenshotEvaluator evaluator = new ScreenshotEvaluator();

    @Test
    void matchesOnlyWhenObservedFieldsMeetAllExplicitRules() {
        var fields = fields("Java开发实习生", "150-200元/天", "150", "200", "DAY", List.of(2028), List.of());
        var result = evaluator.evaluate(fields, ScreenshotRules.defaults());
        assertEquals("MATCH", result.decision());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void uploadedExampleWithNoGraduationYearPassesThatMissingConditionWithWarning() {
        var fields = fields("Java开发实习生", "150-200元/天", "150", "200", "DAY", List.of(), List.of());
        var result = evaluator.evaluate(fields, ScreenshotRules.defaults());
        assertEquals("MATCH", result.decision());
        assertTrue(result.warnings().contains("毕业届次缺失，按符合处理"));
    }

    @ParameterizedTest
    @CsvSource({"150-200元/天,150000,200000,DAY", "150-200元/天,150,200,MONTH",
            "150-200元/天,150,300,DAY", "5-8K,5,8,MONTH"})
    void salaryTextAndStructuredContradictionsNeverBecomeMatch(String raw, String min, String max, String unit) {
        var result = evaluator.evaluate(fields("Java实习生", raw, min, max, unit,
                List.of(2028), List.of()), ScreenshotRules.defaults());
        assertEquals("MISMATCH", result.decision());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("不一致")));
    }

    @ParameterizedTest
    @CsvSource({"120-200元/天,120,200,DAY", "4-6K,4000,6000,MONTH"})
    void comparesAdvertisedLowerBoundNotTheUpperBound(String raw, String min, String max, String unit) {
        var result = evaluator.evaluate(fields("Java实习生", raw, min, max, unit,
                List.of(2028), List.of()), ScreenshotRules.defaults());
        assertEquals("MISMATCH", result.decision());
    }

    @ParameterizedTest
    @CsvSource({"5-8K,5000,8000,MONTH", "0.5-0.8万/月,5000,8000,MONTH",
            "125元/天,125,125,DAY", "150—200元/日,150,200,DAY", "5-8K·13薪,5000,8000,MONTH"})
    void acceptsKnownUnitsWithoutConvertingDailySalaryIntoMonthly(String raw, String min, String max, String unit) {
        var result = evaluator.evaluate(fields("Java实习生", raw, min, max, unit,
                List.of(2028), List.of()), ScreenshotRules.defaults());
        assertEquals("MATCH", result.decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10-15万/年", "30元/小时", "含奖金150元", "150到?元/天", "无法看清"})
    void unsupportedOrAmbiguousSalaryIsRejectedNotGuessed(String raw) {
        assertEquals("MISMATCH", evaluator.evaluate(fields("Java实习生", raw, "150", "200", "DAY",
                List.of(2028), List.of()), ScreenshotRules.defaults()).decision());
    }

    @Test
    void rejectsModelMarkedUnclearObservationsRatherThanTreatingThemAsAbsent() {
        var fields = fields("Java实习生", "150-200元/天", "150", "200", "DAY",
                List.of(2028), List.of("salaryMin"));
        assertEquals("MISMATCH", evaluator.evaluate(fields, ScreenshotRules.defaults()).decision());
    }

    @Test
    void javascriptFrontendTitleIsNotEvidenceOfJavaBackendWork() {
        var fields = fields("JavaScript前端实习生", "150-200元/天", "150", "200", "DAY",
                List.of(2028), List.of());
        assertEquals("MISMATCH", evaluator.evaluate(fields, ScreenshotRules.defaults()).decision());
    }

    @Test
    void modelMarkedUncertaintyIsItselfARejectionReason() {
        var fields = fields("行政实习生", "100-120元/天", "100", "120", "DAY",
                List.of(2027), List.of("jobTitle", "salaryText", "graduationYears"));
        var result = evaluator.evaluate(fields, ScreenshotRules.defaults());
        assertEquals("MISMATCH", result.decision());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("识别不清")));
    }

    @Test
    void clearlyWrongRoleOrGraduationYearCanBeRejectedWithOtherWarningsPreserved() {
        assertEquals("MISMATCH", evaluator.evaluate(fields("行政助理实习生", "150-200元/天", "150", "200",
                "DAY", List.of(), List.of()), ScreenshotRules.defaults()).decision());
        assertEquals("MISMATCH", evaluator.evaluate(fields("Java实习生", "150-200元/天", "150", "200",
                "DAY", List.of(2027), List.of()), ScreenshotRules.defaults()).decision());
    }

    @Test
    void noInternshipMentionPassesTheAbsentConditionRatherThanInventingAFormalEmploymentRequirement() {
        var result = evaluator.evaluate(fields("Java开发工程师", "150-200元/天", "150", "200",
                "DAY", List.of(2028), List.of()), ScreenshotRules.defaults());
        assertEquals("MATCH", result.decision());
        assertTrue(result.warnings().contains("实习标识缺失，按符合处理"));
    }

    @Test
    void cityAndGraduationRestrictionsAreExplicitlyOptional() {
        ScreenshotRules tianjin = new ScreenshotRules(List.of("java"), true, new BigDecimal("125"),
                new BigDecimal("5000"), List.of("天津市"), null);
        var fields = fields("Java实习生", "150-200元/天", "150", "200", "DAY", List.of(), List.of());
        assertEquals("MATCH", evaluator.evaluate(fields, tianjin).decision());
        ScreenshotRules shanghai = new ScreenshotRules(List.of("java"), true, new BigDecimal("125"),
                new BigDecimal("5000"), List.of("上海"), null);
        assertEquals("MISMATCH", evaluator.evaluate(fields, shanghai).decision());
    }

    @Test
    void emptyObservationIsUnreadableMismatchAndNeverInvokesExternalActions() {
        assertEquals("MISMATCH", evaluator.evaluate(parser.parse("{}"), ScreenshotRules.defaults()).decision());
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(null, ScreenshotRules.defaults()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"company\":\"测试公司\"}",
            "{\"jobTitle\":null,\"company\":\"测试公司\"}",
            "{\"jobTitle\":\" \",\"skills\":[\"Java\"]}",
            "{\"jobTitle\":\"Java实习生\"}",
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":null,\"salaryMin\":null,\"salaryMax\":null,\"salaryUnit\":\"UNKNOWN\"}",
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":\"150-200元/天\",\"salaryMin\":null,\"salaryMax\":200,\"salaryUnit\":\"DAY\"}",
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":\"150-200元/天\",\"salaryMin\":150,\"salaryMax\":200}",
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":\"150-200\",\"salaryMin\":150,\"salaryMax\":200,\"salaryUnit\":\"UNKNOWN\"}"
    })
    void partialInformationPassesMissingConditionsWithoutInventingOriginalFields(String json) {
        var observation = parser.parse(json);
        var result = evaluator.evaluate(observation, ScreenshotRules.defaults());
        assertEquals("MATCH", result.decision());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("缺失，按符合处理")));
        assertEquals(observation, parser.parse(json));
    }

    @Test
    void missingConstrainedCityPassesButAnExplicitWrongCityStillRejects() {
        ScreenshotRules cityRule = new ScreenshotRules(List.of("java"), true, new BigDecimal("125"),
                new BigDecimal("5000"), List.of("天津"), 2028);
        var observation = parser.parse("{\"jobTitle\":\"Java实习生\"}");
        var result = evaluator.evaluate(observation, cityRule);
        assertEquals("MATCH", result.decision());
        assertTrue(result.warnings().contains("工作城市缺失，按符合处理"));
        assertNull(observation.city());
        var wrongCity = parser.parse("{\"jobTitle\":\"Java实习生\",\"city\":\"上海\"}");
        assertEquals("MISMATCH", evaluator.evaluate(wrongCity, cityRule).decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"面议", "薪资面议", "待定", "薪资待定", "保密", "薪酬保密", "暂未提供"})
    void explicitlyUndisclosedSalaryCountsAsMissingAmountNotRecognitionFailure(String raw) {
        var fields = new ScreenshotFields("Java实习生", null, null, raw, null, null, "UNKNOWN",
                null, null, null, null, null, null, null);
        var result = evaluator.evaluate(fields, ScreenshotRules.defaults());
        assertEquals("MATCH", result.decision());
        assertTrue(result.warnings().contains("薪资金额下限缺失，按符合处理"));
    }

    @Test
    void undisclosedSalaryCannotJustifyInventedStructuredNumbers() {
        var fields = fields("Java实习生", "薪资面议", "150", "200", "DAY", List.of(2028), List.of());
        var result = evaluator.evaluate(fields, ScreenshotRules.defaults());
        assertEquals("MISMATCH", result.decision());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("存在矛盾")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"salaryUnit\":\"UNKNOWN\"}",
            "{\"jobTitle\":null,\"city\":\" \",\"skills\":[],\"graduationYears\":[]}",
            "{\"uncertainFields\":[\"jobTitle\"]}"})
    void anEntirelyEmptyExtractionCannotMatchJustBecauseEveryConditionIsAbsent(String json) {
        var result = evaluator.evaluate(parser.parse(json), ScreenshotRules.defaults());
        assertEquals("MISMATCH", result.decision());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("无法识别")));
    }

    @Test
    void malformedSalaryDoesNotBecomeMissingJustBecauseNumericFieldsAreNull() {
        var observation = parser.parse("{\"jobTitle\":\"Java实习生\",\"salaryText\":\"含奖金150元\"}");
        assertEquals("MISMATCH", evaluator.evaluate(observation, ScreenshotRules.defaults()).decision());
    }

    @Test
    void clearLowSalaryStillRejectsWhenGraduationYearAndCityAreAbsent() {
        var observation = parser.parse("""
                {"jobTitle":"Java实习生","salaryText":"100-120元/天",
                 "salaryMin":100,"salaryMax":120,"salaryUnit":"DAY"}
                """);
        var result = evaluator.evaluate(observation, ScreenshotRules.defaults());
        assertEquals("MISMATCH", result.decision());
        assertTrue(result.reasons().contains("岗位日薪下限低于最低日薪要求"));
        assertTrue(result.warnings().contains("毕业届次缺失，按符合处理"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":\"100-120元/天\",\"salaryUnit\":\"DAY\"}",
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":\"100-120元/天\",\"salaryMin\":100}",
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":\"100-120元/天\"}"
    })
    void explicitLowSalaryInSourceStillRejectsWhenStructuredAmountOrUnitWasOmitted(String json) {
        var observation = parser.parse(json);
        var result = evaluator.evaluate(observation, ScreenshotRules.defaults());
        assertEquals("MISMATCH", result.decision());
        assertTrue(result.reasons().contains("岗位日薪下限低于最低日薪要求"));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("结构化薪资")));
        assertEquals(observation, parser.parse(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":\"150-200元/天\",\"salaryUnit\":\"DAY\"}",
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":\"150-200元/天\",\"salaryMin\":150}",
            "{\"jobTitle\":\"Java实习生\",\"salaryText\":\"150-200元/天\"}"
    })
    void adequateSourceSalaryPassesWithMissingStructuredFieldsAndWithoutFillingThem(String json) {
        var observation = parser.parse(json);
        var result = evaluator.evaluate(observation, ScreenshotRules.defaults());
        assertEquals("MATCH", result.decision());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("结构化薪资")));
        assertEquals(observation, parser.parse(json));
    }

    @Test
    void missingSourceAndStructuredUnitsDoNotTriggerAnInventedSalaryConversion() {
        var observation = parser.parse("{\"jobTitle\":\"Java实习生\",\"salaryText\":\"100-120\"}");
        var result = evaluator.evaluate(observation, ScreenshotRules.defaults());
        assertEquals("MATCH", result.decision());
        assertTrue(result.warnings().contains("薪资原文计薪单位缺失，按符合处理"));
        assertEquals("UNKNOWN", observation.salaryUnit());
        assertNull(observation.salaryMin());
    }

    @Test
    void explicitRejectionOfInternshipDoesNotCountAsAnInternshipKeywordMatch() {
        var observation = parser.parse("{\"jobTitle\":\"Java开发（非实习）\"}");
        assertEquals("MISMATCH", evaluator.evaluate(observation, ScreenshotRules.defaults()).decision());
    }

    private ScreenshotFields fields(String title, String raw, String min, String max, String unit,
                                    List<Integer> years, List<String> uncertain) {
        return new ScreenshotFields(title, null, "天津", raw, new BigDecimal(min), new BigDecimal(max), unit,
                4, 3, "本科", List.of("Java", "MySQL"), years, null, uncertain);
    }
}
