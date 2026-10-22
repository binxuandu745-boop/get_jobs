package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BossInternSearchPolicyTest {
    @Test
    void studentSearchDoesNotSendMonthlySalaryBucketsThatCouldHideDailyInternships() {
        BossConfig config = new BossConfig();
        config.setExperience(List.of("108"));
        config.setSalary(List.of("0"));
        config.setJobType("0");
        String url = Boss.buildSearchUrl(config, "101280600");
        assertEquals("https://www.zhipin.com/web/geek/jobs?city=101280600&experience=108", url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"125元/天", "125-200元/天", "200-300元/天", "300元/日", "5-10K"})
    void acceptedDailyAndMonthlyPayUseTheirOwnUnits(String salary) {
        assertTrue(SalaryThreshold.matches(salary, 5000, 125));
    }

    @ParameterizedTest
    @ValueSource(strings = {"124元/天", "100-200元/天", "4.5-6K", "面议"})
    void belowMinimumOrUnknownPayIsNotAutomaticallyAccepted(String salary) {
        assertFalse(SalaryThreshold.matches(salary, 5000, 125));
    }
}
