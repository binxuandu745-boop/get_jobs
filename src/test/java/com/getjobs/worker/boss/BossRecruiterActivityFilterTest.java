package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class BossRecruiterActivityFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"在线", "当前在线", "刚刚活跃", "今日活跃", "今天活跃", " 今日活跃 "})
    void acceptsExplicitRecentActivity(String status) {
        assertFalse(BossRecruiterActivityFilter.rejects(true, status));
    }

    @ParameterizedTest
    @ValueSource(strings = {"本月活跃", "2月内活跃", "3日内活跃", "本周活跃", "半年前活跃", "", "  ", "不在线", "非今日活跃", "未知"})
    void rejectsOtherActivityOrMissingText(String status) {
        assertTrue(BossRecruiterActivityFilter.rejects(true, status));
    }

    @Test
    void missingStatusRejectedOnlyWhenEnabled() {
        assertTrue(BossRecruiterActivityFilter.rejects(true, null));
        assertFalse(BossRecruiterActivityFilter.rejects(false, null));
        assertFalse(BossRecruiterActivityFilter.rejects(null, "本月活跃"));
    }
}
