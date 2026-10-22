package com.getjobs.worker.boss;

import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.json.JSONObject;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.getjobs.worker.boss.Locators.JOB_LIST_SELECTOR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Full loop regression using mocks only: no browser, real database, raw log, or messages. */
class BossSafetyStopTest {
    private Page page;
    private BossService service;
    private AiService ai;
    private Boss boss;
    private Locator cards;
    private Locator first;
    private AtomicReference<String> currentUrl;
    private AtomicReference<Consumer<Response>> responseListener;
    private AtomicReference<Consumer<Request>> requestListener;
    private Supplier<Response> awaitedResponse;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        service = mock(BossService.class);
        ai = mock(AiService.class);
        boss = spy(new Boss(service, ai));
        doNothing().when(boss).appendRawJson(anyString());
        boss.setPage(page);
        boss.setShouldStopCallback(() -> false);
        boss.setProgressCallback((message, current, total) -> { });
        BossConfig config = new BossConfig();
        config.setCityCode(List.of("101010100", "101020100"));
        config.setKeywords(List.of("Java", "后端"));
        config.setDebugger(true);
        config.setFilterDeadHR(false);
        boss.setConfig(config);
        ReflectionTestUtils.setField(boss, "maxPreviewJobs", 5);
        ReflectionTestUtils.setField(boss, "requiredRoleTerms", List.of("Java", "后端", "服务端"));

        currentUrl = new AtomicReference<>("https://www.zhipin.com/web/geek/jobs");
        when(page.url()).thenAnswer(ignored -> currentUrl.get());
        doAnswer(invocation -> {
            currentUrl.set(invocation.getArgument(0));
            return null;
        }).when(page).navigate(anyString(), any(Page.NavigateOptions.class));
        Locator ordinary = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(ordinary);
        when(ordinary.first()).thenReturn(ordinary);
        when(ordinary.isVisible()).thenReturn(true);
        Locator verification = mock(Locator.class);
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        when(page.getByText("没有找到相关职位")).thenReturn(mock(Locator.class));
        cards = mock(Locator.class);
        first = mock(Locator.class);
        when(page.locator(JOB_LIST_SELECTOR)).thenReturn(cards);
        when(cards.count()).thenReturn(3);
        when(cards.nth(0)).thenReturn(first);
        Locator second = mock(Locator.class);
        when(cards.nth(1)).thenReturn(second);
        Locator unknownJobLinks = mock(Locator.class);
        when(first.locator("a[href*='/job_detail/']")).thenReturn(unknownJobLinks);
        when(second.locator("a[href*='/job_detail/']")).thenReturn(unknownJobLinks);
        responseListener = new AtomicReference<>();
        requestListener = new AtomicReference<>();
        awaitedResponse = () -> null;
        doAnswer(invocation -> {
            responseListener.set(invocation.getArgument(0));
            return null;
        }).when(page).onResponse(any());
        doAnswer(invocation -> {
            requestListener.set(invocation.getArgument(0));
            return null;
        }).when(page).onRequest(any());
        doAnswer(invocation -> {
            Response response = awaitedResponse.get();
            if (response != null) {
                requestListener.get().accept(response.request());
                responseListener.get().accept(response);
            }
            BooleanSupplier condition = invocation.getArgument(0);
            if (!condition.getAsBoolean()) throw new TimeoutError("simulated detail wait timeout");
            return null;
        }).when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
    }

    @Test
    void absentDetailStopsAllLaterJobsKeywordsAndCities() {
        // The bounded wait expires without a response, not a detected security challenge.
        var failure = executeUntilPaused();
        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        verifyOnlyFirstDetailAttempt();
        verifyNoInteractions(service, ai);
        verify(boss, never()).appendRawJson(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"zpData\":{}}", "<html>验证页</html>", ""})
    void invalidDetailIsNotPersistedOrMarkedAsFiltered(String body) {
        stubDetail(body);
        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE,
                executeUntilPaused().getReason());
        verifyOnlyFirstDetailAttempt();
        verifyNoInteractions(service, ai);
        verify(boss, never()).appendRawJson(anyString());
    }

    @Test
    void verificationAfterResponseWaitFailureStopsInsteadOfContinuing() {
        awaitedResponse = () -> {
            currentUrl.set("https://www.zhipin.com/web/passport/zp/verify.html");
            throw new TimeoutError("simulated response timeout");
        };
        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION,
                executeUntilPaused().getReason());
        verifyOnlyFirstDetailAttempt();
        verifyNoInteractions(service, ai);
    }

    @Test
    void verificationAfterFilteredJobCannotBeSkippedByContinue() {
        stubDetail("""
                {"zpData":{"jobInfo":{"jobName":"行政实习","salaryDesc":"6-9K",
                "encryptId":"test-id"},"brandComInfo":{"brandName":"Test company"}}}
                """);
        doAnswer(ignored -> {
            currentUrl.set("https://www.zhipin.com/web/passport/zp/verify.html");
            return null;
        }).when(service).insertBossJob(any(BossJobDataEntity.class));
        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION,
                executeUntilPaused().getReason());
        verifyOnlyFirstDetailAttempt();
        verify(service).insertBossJob(argThat(job -> "已过滤".equals(job.getDeliveryStatus())));
        verifyNoInteractions(ai);
    }

    @Test
    void existingVerificationStopsBeforeAnyNavigationOrClick() {
        currentUrl.set("https://www.zhipin.com/web/passport/zp/verify.html");
        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION,
                executeUntilPaused().getReason());
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page, never()).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        verifyNoInteractions(service, ai);
    }

    @Test
    void filteredJobStillWaitsThreeToFiveSecondsBeforeNextDetailRequest() {
        Response response = detailResponse("""
                {"zpData":{"jobInfo":{"jobName":"行政实习","salaryDesc":"6-9K",
                "encryptId":"test-id"},"brandComInfo":{"brandName":"Test company"}}}
                """);
        AtomicInteger waitedAfterPersistence = new AtomicInteger();
        AtomicBoolean firstJobPersisted = new AtomicBoolean();
        doAnswer(invocation -> {
            if (firstJobPersisted.get()) {
                waitedAfterPersistence.addAndGet(((Double) invocation.getArgument(0)).intValue());
            }
            return null;
        }).when(page).waitForTimeout(anyDouble());
        doAnswer(ignored -> {
            firstJobPersisted.set(true);
            return null;
        }).when(service).insertBossJob(any(BossJobDataEntity.class));
        AtomicInteger detailRequests = new AtomicInteger();
        awaitedResponse = () -> {
            if (detailRequests.incrementAndGet() == 1) return response;
            assertTrue(waitedAfterPersistence.get() >= 3000);
            assertTrue(waitedAfterPersistence.get() <= 5000);
            return null;
        };

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE,
                executeUntilPaused().getReason());
        assertEquals(2, detailRequests.get());
        verify(service, times(1)).insertBossJob(argThat(job -> "已过滤".equals(job.getDeliveryStatus())));
        verify(page, never()).context();
        verifyNoInteractions(ai);
    }

    @Test
    void stopDuringActionWaitPreventsAnyJobClick() {
        AtomicBoolean stopRequested = new AtomicBoolean();
        boss.setShouldStopCallback(stopRequested::get);
        doAnswer(ignored -> {
            stopRequested.set(true);
            return null;
        }).when(page).waitForTimeout(anyDouble());

        try (MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            assertEquals(0, boss.execute());
        }
        verify(page, times(1)).waitForTimeout(anyDouble());
        verify(page, never()).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        verify(first, never()).click();
        verifyNoInteractions(service, ai);
    }

    @Test
    void verificationDuringActionWaitStopsBeforeAnyJobClick() {
        doAnswer(ignored -> {
            currentUrl.set("https://www.zhipin.com/web/passport/zp/verify.html");
            return null;
        }).when(page).waitForTimeout(anyDouble());

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION,
                executeUntilPaused().getReason());
        verify(page, times(1)).waitForTimeout(anyDouble());
        verify(page, never()).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        verify(first, never()).click();
        verifyNoInteractions(service, ai);
    }

    @Test
    void slowDetailResponseCanRecoverWithoutPausingOrRepeatingTheJobClick() {
        BossConfig config = (BossConfig) ReflectionTestUtils.getField(boss, "config");
        config.setCityCode(List.of("101010100"));
        config.setKeywords(List.of("Java"));
        when(cards.count()).thenReturn(1);
        Response response = detailResponse("""
                {"zpData":{"jobInfo":{"jobName":"Java开发实习生","salaryDesc":"6-9K",
                "encryptId":"test-id"},"brandComInfo":{"brandName":"Test company"}}}
                """);
        doAnswer(invocation -> {
            BooleanSupplier condition = invocation.getArgument(0);
            assertFalse(condition.getAsBoolean());
            assertFalse(condition.getAsBoolean());
            assertFalse(condition.getAsBoolean());
            requestListener.get().accept(response.request());
            responseListener.get().accept(response);
            assertTrue(condition.getAsBoolean());
            return null;
        }).when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));

        try (MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            assertDoesNotThrow(boss::execute);
        }

        verify(first, times(1)).click();
        verify(page).waitForCondition(any(BooleanSupplier.class),
                argThat(options -> Double.valueOf(60_000).equals(options.timeout)));
        verify(page, times(2)).offResponse(any());
        verify(service, times(1)).insertBossJob(any(BossJobDataEntity.class));
        verifyNoInteractions(ai);
        verify(page, never()).context();
    }

    @Test
    void staleFrameDuringStepWaitDoesNotBecomeAProtectivePauseWhenPageRecovers() {
        doThrow(new IllegalStateException("Execution context was destroyed"))
                .doNothing().when(page).waitForTimeout(anyDouble());

        assertDoesNotThrow(() -> boss.pauseBeforeAction(page, "read-only test"));

        verify(page, atLeast(2)).waitForTimeout(anyDouble());
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verifyNoInteractions(service, ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE", "SALARY", "ROLE_BLACKLIST", "HR_INACTIVE", "COMPANY_BLACKLIST", "RECRUITER_BLACKLIST"})
    void everyRejectedJobSkipsProcessingDelayButKeepsItsPreClickDelay(String rejection) {
        BossConfig config = useSingleJobSearch();
        JSONObject job = new JSONObject().put("jobName", "Java后端实习生")
                .put("salaryDesc", "6-9K").put("encryptId", "test-id");
        JSONObject brand = new JSONObject().put("brandName", "Test company");
        JSONObject recruiter = new JSONObject().put("title", "HR").put("activeTimeDesc", "3天前活跃");
        switch (rejection) {
            case "ROLE" -> job.put("jobName", "行政实习生");
            case "SALARY" -> {
                ReflectionTestUtils.setField(boss, "minimumMonthlySalary", 5000);
                job.put("salaryDesc", "4-6K");
            }
            case "ROLE_BLACKLIST" -> ReflectionTestUtils.setField(boss, "blackJobs", Set.of("后端"));
            case "HR_INACTIVE" -> {
                config.setFilterDeadHR(true);
                recruiter.put("activeTimeDesc", "1年前活跃");
            }
            case "COMPANY_BLACKLIST" -> ReflectionTestUtils.setField(boss, "blackCompanies", Set.of("Test"));
            case "RECRUITER_BLACKLIST" -> ReflectionTestUtils.setField(boss, "blackRecruiters", Set.of("HR"));
            default -> throw new AssertionError("Unexpected test case");
        }
        stubDetail(new JSONObject().put("zpData", new JSONObject().put("jobInfo", job)
                .put("brandComInfo", brand).put("bossInfo", recruiter)).toString());
        List<String> stages = trackActionStages();
        AtomicInteger waitedMillis = new AtomicInteger();
        doAnswer(invocation -> {
            waitedMillis.addAndGet(((Double) invocation.getArgument(0)).intValue());
            return null;
        }).when(page).waitForTimeout(anyDouble());

        try (MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            assertEquals(0, boss.execute());
        }

        assertEquals(List.of("打开下一个岗位"), stages);
        assertTrue(waitedMillis.get() >= 3000 && waitedMillis.get() <= 5000);
        assertEquals(0, boss.getPreviewMatchedCount());
        verify(first, times(1)).click();
        verify(service).insertBossJob(argThat(entity -> "已过滤".equals(entity.getDeliveryStatus())));
        verify(page, never()).context();
        verifyNoInteractions(ai);
    }

    @Test
    void matchingJobStillKeepsItsProcessingDelayAfterFiltering() {
        useSingleJobSearch();
        stubDetail("""
                {"zpData":{"jobInfo":{"jobName":"Java后端实习生","salaryDesc":"6-9K",
                "encryptId":"test-id"},"brandComInfo":{"brandName":"Test company"}}}
                """);
        List<String> stages = trackActionStages();

        try (MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            assertDoesNotThrow(boss::execute);
        }

        assertEquals(List.of("打开下一个岗位", "筛选通过，继续处理岗位"), stages);
        assertEquals(1, boss.getPreviewMatchedCount());
        verifyNoInteractions(ai);
        verify(page, never()).context();
    }

    private BossConfig useSingleJobSearch() {
        BossConfig config = (BossConfig) ReflectionTestUtils.getField(boss, "config");
        config.setCityCode(List.of("101010100"));
        config.setKeywords(List.of("Java"));
        when(cards.count()).thenReturn(1);
        return config;
    }

    private List<String> trackActionStages() {
        List<String> stages = new ArrayList<>();
        doAnswer(invocation -> {
            stages.add(invocation.getArgument(1));
            return invocation.callRealMethod();
        }).when(boss).pauseBeforeAction(any(Page.class), anyString());
        return stages;
    }

    private void stubDetail(String body) {
        Response response = detailResponse(body);
        awaitedResponse = () -> response;
    }

    private Response detailResponse(String body) {
        Response response = mock(Response.class);
        Request request = mock(Request.class);
        when(response.url()).thenReturn("https://www.zhipin.com/wapi/zpgeek/job/detail.json");
        when(response.request()).thenReturn(request);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn("https://www.zhipin.com/wapi/zpgeek/job/detail.json");
        when(response.status()).thenReturn(200);
        when(response.text()).thenReturn(body);
        return response;
    }

    private BossTaskPausedException executeUntilPaused() {
        try (MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            return assertThrows(BossTaskPausedException.class, boss::execute);
        }
    }

    private void verifyOnlyFirstDetailAttempt() {
        verify(page, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page, times(1)).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        verify(first, times(1)).click();
        verify(page, times(2)).offResponse(any());
        verify(cards, never()).nth(2);
        verify(page, never()).context();
    }

}
