package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobTargetFilterTest {
    private static final List<String> ROLE_TERMS = List.of("Java", "后端", "服务端");

    @ParameterizedTest
    @ValueSource(strings = {
            "Java实习", "java实习", "JAVA实习", "jAvA开发实习",
            "后端实习", "Java后端开发实习生", "服务端实习"
    })
    void matchingInternshipRolesPassRegardlessOfJavaCase(String title) {
        assertTrue(JobTargetFilter.matches(title, null, ROLE_TERMS, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "房产实习", "艺术实习", "行政实习", "Java全职", "Java开发工程师", "未知实习"
    })
    void unrelatedRolesAndNonInternshipJavaJobsAreRejected(String title) {
        assertFalse(JobTargetFilter.matches(title, null, ROLE_TERMS, true));
    }

    @Test
    void javaTitleWithInternshipInDescriptionPasses() {
        assertTrue(JobTargetFilter.matches("Java开发", "招聘在校生，提供后端实习岗位。", ROLE_TERMS, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Java开发工程师", "初级JAVA开发 保险 银保通 双休 加班费",
            "Python、Java开发工程师（应届生可投）", "14k！java开发岗",
            "Java开发工程师（接受无经验+16薪）", "Java开发工程师（公司内勤·应届生）",
            "算法服务后端工程师-27秋招", "初级后端开发工程师（任何编程语言）",
            "服务端研发工程师", "Java全职"
    })
    void targetRolesWithoutInternshipMarkerPassWhenRequirementIsDisabled(String title) {
        assertTrue(JobTargetFilter.matches(title, "开发岗位，未注明是否接受在校生。", ROLE_TERMS, false));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "房产实习", "行政实习", "游戏开发实习生", "前端开发", "未知"})
    void disablingInternshipRequirementDoesNotDisableRoleFiltering(String title) {
        assertFalse(JobTargetFilter.matches(title, "与Java后端团队合作。", ROLE_TERMS, false));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "未知", "工程师"})
    void nullEmptyAndUnknownTitlesAreRejectedWhenTargetIsConfigured(String title) {
        assertFalse(JobTargetFilter.matches(title, null, ROLE_TERMS, true));
    }

    @Test
    void roleMentionInDescriptionDoesNotQualifyAnUnrelatedTitle() {
        assertFalse(JobTargetFilter.matches("行政实习", "与Java后端和服务端团队合作。", ROLE_TERMS, true));
    }

    @Test
    void unconfiguredRoleAndInternshipRequirementsPreserveGeneralBehavior() {
        assertTrue(JobTargetFilter.matches("行政职位", null, List.of(), false));
        assertTrue(JobTargetFilter.matches("未知", null, null, false));
        assertTrue(JobTargetFilter.matches(null, null, null, false));
    }

    @Test
    void nullAndBlankRoleTermsAreIgnoredForGeneralBehavior() {
        assertTrue(JobTargetFilter.matches("通用职位", null, Arrays.asList(null, "", "  "), false));
    }
}
