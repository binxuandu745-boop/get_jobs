package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.BossScreenshotSettingsService;
import com.getjobs.application.screenshot.ScreenshotAnalysisResult;
import com.getjobs.application.screenshot.ScreenshotEvaluation;
import com.getjobs.application.screenshot.ScreenshotFields;
import com.getjobs.application.screenshot.ScreenshotRules;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Search filters bind list readiness and any later screenshot approval to the same search. */
class BossSearchWindowTest {
    private static final String SEARCH = "https://www.zhipin.com/web/geek/jobs?city=101010100&jobType=1901&query=Java";
    private static final String OTHER_CITY = "https://www.zhipin.com/web/geek/jobs?city=101020100&jobType=1901&query=Java";
    private static final String OTHER_TYPE = "https://www.zhipin.com/web/geek/jobs?city=101010100&jobType=1902&query=Java";
    private static final String TRACKED = "https://www.zhipin.com/web/geek/jobs?query=Java&ka=search_list&jobType=1901&city=101010100&lid=trace-id";
    private static final String LIST_CONTAINERS = "ul.rec-job-list, ul.job-list-box, .job-list-box, .search-job-result, li.job-card-box, li.job-card-wrapper";
    private Boss boss;
    private Page listPage;
    private AtomicReference<String> currentUrl;
    private BossService records;
    private AiService greeting;

    @BeforeEach
    void setUp() {
        records = mock(BossService.class);
        greeting = mock(AiService.class);
        boss = new Boss(records, greeting);
        listPage = usablePage();
        currentUrl = new AtomicReference<>(SEARCH);
        when(listPage.url()).thenAnswer(ignored -> currentUrl.get());
        boss.setPage(listPage);
        boss.setShouldStopCallback(() -> false);
        ReflectionTestUtils.setField(boss, "actionDelayMinMs", 1);
        ReflectionTestUtils.setField(boss, "actionDelayMaxMs", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {OTHER_CITY, OTHER_TYPE})
    void navigationTimeoutAtSamePathWithDifferentFiltersIsNotAcceptedAsLanded(String wrongSearch) {
        currentUrl.set(wrongSearch);
        doThrow(new TimeoutError("simulated navigation timeout"))
                .when(listPage).navigate(eq(SEARCH), any(Page.NavigateOptions.class));

        try (MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class)) {
            assertThrows(TimeoutError.class, () -> invoke("navigateToJobList", new Class<?>[]{String.class}, SEARCH));
        }

        verify(listPage, times(2)).navigate(eq(SEARCH), any(Page.NavigateOptions.class));
        verify(listPage, never()).waitForLoadState(any(), any(Page.WaitForLoadStateOptions.class));
        verifyNoInteractions(records, greeting);
    }

    @Test
    void trackingAndParameterOrderChangesStillAcceptSuccessfulLandingAfterTimeout() throws Throwable {
        currentUrl.set(TRACKED);
        doThrow(new TimeoutError("simulated navigation timeout"))
                .when(listPage).navigate(eq(SEARCH), any(Page.NavigateOptions.class));

        try (MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class)) {
            invoke("navigateToJobList", new Class<?>[]{String.class}, SEARCH);
        }

        verify(listPage, times(1)).navigate(eq(SEARCH), any(Page.NavigateOptions.class));
        verify(listPage).waitForLoadState(any(), any(Page.WaitForLoadStateOptions.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleVisibleListOrEmptyMarkerCannotSatisfyDifferentSearchFilters(boolean emptyResult) {
        currentUrl.set(OTHER_CITY);
        ReflectionTestUtils.setField(boss, "expectedSearchUrl", SEARCH);
        visibleListAndEmptyMarker(emptyResult);

        assertThrows(RuntimeException.class, () -> invoke("waitForJobList", new Class<?>[0]));

        verifyNoInteractions(records, greeting);
    }

    @Test
    void trackedEquivalentSearchCanUseVisibleList() throws Throwable {
        currentUrl.set(TRACKED);
        ReflectionTestUtils.setField(boss, "expectedSearchUrl", SEARCH);
        visibleListAndEmptyMarker(false);

        assertEquals(true, invoke("waitForJobList", new Class<?>[0]));

        verify(listPage, never()).waitForTimeout(500);
    }

    @Test
    void changedSearchFiltersWhileVisionRunsCannotUseMatchingResultToCommunicate() throws Exception {
        String jobId = "bound-job";
        String detailUrl = "https://www.zhipin.com/job_detail/" + jobId + ".html";
        Page detailPage = usablePage();
        when(detailPage.url()).thenReturn(detailUrl);
        Locator more = selfLocator();
        when(listPage.locator("a.more-job-btn")).thenReturn(more);
        when(more.count()).thenReturn(1);
        when(more.getAttribute("href")).thenReturn("/job_detail/" + jobId + ".html");
        BrowserContext context = mock(BrowserContext.class);
        when(listPage.context()).thenReturn(context);
        when(context.newPage()).thenReturn(detailPage);
        Locator chat = selfLocator();
        when(detailPage.locator("a.btn-startchat, a.op-btn-chat")).thenReturn(chat);
        when(chat.count()).thenReturn(1);
        when(chat.textContent()).thenReturn("立即沟通");
        Locator input = selfLocator();
        when(detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area")).thenReturn(input);
        when(input.count()).thenReturn(1);
        when(input.isVisible()).thenReturn(true);
        when(input.evaluate("el => el.tagName.toLowerCase()")).thenReturn("textarea");
        Locator send = selfLocator();
        when(detailPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send")).thenReturn(send);
        when(send.count()).thenReturn(1);
        Locator close = selfLocator();
        when(detailPage.locator("i.icon-close")).thenReturn(close);
        BossConfig config = new BossConfig();
        config.setDebugger(false);
        config.setEnableAI(false);
        config.setSendImgResume(false);
        config.setFilterDeadHR(false);
        config.setSayHi("test greeting");
        boss.setConfig(config);
        BossScreenshotScreening screening = mock(BossScreenshotScreening.class);
        ReflectionTestUtils.setField(boss, "screenshotScreening", screening);
        ReflectionTestUtils.setField(boss, "screenshotSettings",
                new BossScreenshotSettingsService.Settings(true, ScreenshotRules.defaults()));
        when(screening.analyze(eq(detailPage), eq(jobId), any(), any())).thenAnswer(ignored -> {
            currentUrl.set(OTHER_TYPE);
            return matchingResult();
        });
        JSONObject root = new JSONObject("""
                {"zpData":{"jobInfo":{"encryptId":"bound-job","encryptUserId":"test-hr"}}}
                """);
        try (BossJobDetailSession session = new BossJobDetailSession(listPage, true);
             MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class)) {
            session.beginNavigation();
            session.bindSearchContext(SEARCH);
            ReflectionTestUtils.setField(boss, "detailSession", session);

            assertThrows(BossTaskPausedException.class, () -> invoke("processScreenshotJob",
                    new Class<?>[]{String.class, BossJobDetailReader.Detail.class}, "Java",
                    new BossJobDetailReader.Detail(root.toString(), root)));
        }

        verify(screening, times(1)).analyze(eq(detailPage), eq(jobId), any(), any());
        verify(chat, never()).click();
        verify(send, never()).click();
        verifyNoInteractions(greeting);
        assertTrue(boss.getResultList().isEmpty());
    }

    private void visibleListAndEmptyMarker(boolean emptyResult) {
        Locator empty = selfLocator();
        when(empty.isVisible()).thenReturn(emptyResult);
        when(listPage.getByText("没有找到相关职位")).thenReturn(empty);
        Locator visible = selfLocator();
        when(visible.isVisible()).thenReturn(true);
        when(listPage.locator(LIST_CONTAINERS)).thenReturn(visible);
    }

    private Object invoke(String methodName, Class<?>[] parameters, Object... arguments) throws Throwable {
        Method method = Boss.class.getDeclaredMethod(methodName, parameters);
        method.setAccessible(true);
        try {
            return method.invoke(boss, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static ScreenshotAnalysisResult matchingResult() {
        ScreenshotFields fields = new ScreenshotFields("Java实习", "测试公司", "北京", "200元/天",
                new BigDecimal("200"), new BigDecimal("200"), "DAY", null, null, "本科", List.of("Java"),
                List.of(2028), "Java岗位描述", List.of());
        return new ScreenshotAnalysisResult("bound-search-result", "COMPLETE", "2026-10-22T00:00:00Z",
                "boss-bound-job-1.png", "hash", fields,
                new ScreenshotEvaluation("MATCH", List.of(), List.of()), null, null, "mock-vision");
    }

    private static Page usablePage() {
        Page page = mock(Page.class);
        Locator verification = selfLocator();
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        return page;
    }

    private static Locator selfLocator() {
        Locator locator = mock(Locator.class);
        when(locator.first()).thenReturn(locator);
        return locator;
    }
}
