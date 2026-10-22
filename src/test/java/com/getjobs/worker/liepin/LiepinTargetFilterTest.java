package com.getjobs.worker.liepin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.getjobs.worker.liepin.LiepinTargetFilter.Decision.ACCEPT;
import static com.getjobs.worker.liepin.LiepinTargetFilter.Decision.REJECT;
import static com.getjobs.worker.liepin.LiepinTargetFilter.Decision.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LiepinTargetFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Java开发工程师", "java实习生", "JAVA开发", "初级后端开发", "服务端研发",
            "算法服务后端工程师-27秋招", "Java开发工程师（应届生可投）"
    })
    void defaultRolesDoNotRequireAnInternshipMarker(String title) {
        assertEquals(ACCEPT, LiepinTargetFilter.evaluate(title, null, "4.5-6K").decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"行政实习", "房产实习", "前端开发", "游戏美术", "招聘专员"})
    void descriptionRoleMentionsDoNotQualifyAnUnrelatedTitle(String title) {
        assertEquals(REJECT, LiepinTargetFilter.evaluate(title,
                "与Java后端和服务端团队合作。", "6-8K").decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4500元/月", "4500-6000/月", "4500月薪", "4.5-6K", "4.5k-6k",
            "0.45-0.6万", "5-10K·13薪", "5~10K", "5至10K", "5-10K（13薪）"})
    void monthlyFormatsAtOrAboveThe4500LowerBoundPass(String salary) {
        assertEquals(ACCEPT, LiepinTargetFilter.evaluate("Java开发", null, salary).decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4499元/月", "4000-8000元/月", "4-10K", "0.4-0.8万"})
    void monthlySalaryUsesTheAdvertisedLowerBound(String salary) {
        assertEquals(REJECT, LiepinTargetFilter.evaluate("Java开发", null, salary).decision());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "岗位", "公司", "职位", "岗位名称", "未知", "待确认"})
    void missingOrPlaceholderTitlesRemainUnknown(String title) {
        assertEquals(UNKNOWN, LiepinTargetFilter.evaluate(title, "Java后端实习", "8-10K").decision());
    }

    @Test
    void disablingRoleFilteringDoesNotMakePlaceholderTitlesValid() {
        assertEquals(UNKNOWN, evaluate("岗位", null, "8-10K", List.of(), false, 0, 0).decision());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "面议", "4500", "4500-6000", "4500元", "20-30万/年",
            "50元/小时", "年薪20万", "https://www.liepin.com/job/4500.shtml",
            "4500k.shtml", "4500 https://www.liepin.com/月薪", "5-10K/周"})
    void missingAmbiguousAndNonMonthlyOrDailySalaryRemainsUnknown(String salary) {
        LiepinTargetFilter.Evaluation result = LiepinTargetFilter.evaluate("Java开发", null, salary);
        assertEquals(UNKNOWN, result.decision());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void disablingThresholdsStillRequiresARecognizableSalaryUnit() {
        assertEquals(UNKNOWN, evaluate("Java开发", null, "4500", List.of("Java"), false, 0, 0).decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4000-8K/月", "4K-5000元/月", "5K-0.8万/月", "5000-4000元/月",
            "8-5K", "300-200元/天"})
    void ambiguousMixedScaleOrReversedSalaryRangesRemainUnknown(String salary) {
        assertEquals(UNKNOWN, evaluate("Java开发", null, salary,
                List.of("Java"), false, 4500, 200).decision());
    }

    @Test
    void dailyOffersRequireAnExplicitIndependentMinimum() {
        assertEquals(REJECT, evaluate("Java实习", null, "5000元/天",
                List.of("Java"), false, 4500, 0).decision());
        assertEquals(ACCEPT, evaluate("Java实习", null, "200-300元/天",
                List.of("Java"), false, 4500, 200).decision());
        assertEquals(REJECT, evaluate("Java实习", null, "199-300元/日",
                List.of("Java"), false, 4500, 200).decision());
        assertEquals(REJECT, evaluate("Java实习", null, "4000元/月",
                List.of("Java"), false, 4500, 200).decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"125元/天", "125-200元/天", "150元/天", "300-400元/天"})
    void defaultDailyMinimumIs125IndependentlyOfMonthlyMinimum(String salary) {
        assertEquals(ACCEPT, LiepinTargetFilter.evaluate("Java开发实习生", null, salary).decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"120元/天", "120-150元/天", "100-200元/天"})
    void defaultDailyMinimumChecksLowerBoundNotUpperBound(String salary) {
        assertEquals(REJECT, LiepinTargetFilter.evaluate("Java开发实习生", null, salary).decision());
    }

    @Test
    void explicitInternshipRequirementChecksTheTitleAndDescription() {
        assertEquals(REJECT, evaluate("Java开发", "全职开发岗位", "6-8K",
                List.of("Java"), true, 4500, 0).decision());
        assertEquals(ACCEPT, evaluate("Java实习生", null, "6-8K",
                List.of("Java"), true, 4500, 0).decision());
        assertEquals(ACCEPT, evaluate("Java开发", "招聘Java实习生", "6-8K",
                List.of("Java"), true, 4500, 0).decision());
        assertEquals(REJECT, evaluate("行政实习", "与Java团队合作", "6-8K",
                List.of("Java"), true, 4500, 0).decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"不接受实习生，仅招聘全职", "不招实习", "不招聘实习生", "不提供实习岗位",
            "非实习岗位", "实习生勿投"})
    void explicitNegativeInternshipMarkersDoNotSatisfyTheEnabledRequirement(String description) {
        assertEquals(REJECT, evaluate("Java开发", description, "6-8K",
                List.of("Java"), true, 4500, 0).decision());
        assertEquals(ACCEPT, evaluate("Java开发", description, "6-8K",
                List.of("Java"), false, 4500, 0).decision());
    }

    @Test
    void negativeInternshipMarkersInTheTitleAreAlsoCheckedWhenRequired() {
        assertEquals(REJECT, evaluate("Java开发（非实习）", null, "6-8K",
                List.of("Java"), true, 4500, 0).decision());
    }

    @Test
    void roleTermsAndMonthlyMinimumRemainConfigurable() {
        assertEquals(ACCEPT, evaluate("Python开发", null, "7-8K",
                List.of("Python"), false, 7000, 0).decision());
        assertEquals(REJECT, evaluate("Java开发", null, "7-8K",
                List.of("Python"), false, 7000, 0).decision());
        assertEquals(REJECT, evaluate("Python开发", null, "6-8K",
                List.of("Python"), false, 7000, 0).decision());
    }

    private static LiepinTargetFilter.Evaluation evaluate(String title, String description, String salary,
                                                         List<String> roleTerms, boolean requireInternship,
                                                         int monthlyMinimum, int dailyMinimum) {
        return LiepinTargetFilter.evaluate(title, description, salary, roleTerms,
                requireInternship, monthlyMinimum, dailyMinimum);
    }
}
