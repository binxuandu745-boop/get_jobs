package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.PageDiagnosticResult;
import com.getjobs.application.screenshot.ScreenshotVisionException;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.getjobs.worker.boss.Locators.JOB_LIST_SELECTOR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline traversal regression: no real browser, persistence, AI or message sending. */
class BossIncrementalTraversalTest {
    private static final String LIST_CONTAINERS = String.join(", ",
            "ul.rec-job-list", "ul.job-list-box", ".job-list-box", ".search-job-result",
            "li.job-card-box", "li.job-card-wrapper");
    private static final String END_MARKERS = ":text-is('没有更多了'):visible, :text-is('没有更多职位了'):visible, "
            + ":text-is('没有更多岗位了'):visible, :text-is('已经到底了'):visible";

    private Page page;
    private Boss boss;
    private BossConfig config;
    private Locator cards;
    private Locator endMarker;
    private final List<Locator> cardItems = new ArrayList<>();
    private final List<Locator> cardLinks = new ArrayList<>();
    private final AtomicReference<String> selectedId = new AtomicReference<>();
    private final AtomicReference<Double> documentOrigin = new AtomicReference<>(12345.5);
    private final AtomicReference<List<String>> diagnosticCards = new AtomicReference<>(List.of("first", "second"));

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        Locator ordinary = mock(Locator.class);
        when(ordinary.first()).thenReturn(ordinary);
        when(page.locator(anyString())).thenReturn(ordinary);
        when(page.getByText(anyString())).thenReturn(ordinary);
        AtomicReference<String> currentUrl = new AtomicReference<>("https://www.zhipin.com/web/geek/jobs");
        when(page.url()).thenAnswer(ignored -> currentUrl.get());
        when(page.evaluate(BossListPageDiagnosis.DOCUMENT_ORIGIN_SCRIPT)).thenAnswer(ignored -> documentOrigin.get());
        doAnswer(invocation -> {
            currentUrl.set(invocation.getArgument(0));
            return null;
        }).when(page).navigate(anyString(), any(Page.NavigateOptions.class));

        Locator containers = mock(Locator.class);
        when(containers.first()).thenReturn(containers);
        when(containers.isVisible()).thenReturn(true);
        when(page.locator(LIST_CONTAINERS)).thenReturn(containers);

        cards = mock(Locator.class);
        when(page.locator(JOB_LIST_SELECTOR)).thenReturn(cards);
        endMarker = mock(Locator.class);
        when(page.locator(END_MARKERS)).thenReturn(endMarker);

        Locator selected = mock(Locator.class);
        when(page.locator("a.more-job-btn")).thenReturn(selected);
        when(selected.first()).thenReturn(selected);
        when(selected.count()).thenAnswer(ignored -> selectedId.get() == null ? 0 : 1);
        when(selected.getAttribute("href")).thenAnswer(ignored -> selectedId.get() == null
                ? null : "/job_detail/" + selectedId.get() + ".html");

        doAnswer(call -> {
            BooleanSupplier condition = call.getArgument(0);
            assertTrue(condition.getAsBoolean(), "The mocked page must reach the requested condition");
            return null;
        }).when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));

        BossService service = mock(BossService.class);
        when(service.existsBossJobByEncryptId(anyString())).thenReturn(true);
        boss = spy(new Boss(service, mock(AiService.class)));
        doNothing().when(boss).appendRawJson(anyString());
        boss.setPage(page);
        boss.setShouldStopCallback(() -> false);
        boss.setProgressCallback((message, current, total) -> { });
        ReflectionTestUtils.setField(boss, "requiredRoleTerms", List.of("java", "后端"));
        ReflectionTestUtils.setField(boss, "detailResponseTimeoutMs", 1000);

        config = new BossConfig();
        config.setKeywords(List.of("Java实习"));
        config.setCityCode(List.of("101010100"));
        config.setDebugger(false);
        config.setFilterDeadHR(false);
        boss.setConfig(config);
    }

    @Test
    void finishesLoadedCardsInOrderWithoutPreclickingSecondOrPreloadingMore() {
        addCards("first-job", "second-job");
        AtomicInteger processed = new AtomicInteger();
        when(endMarker.count()).thenAnswer(ignored -> processed.get() == 2 ? 1 : 0);

        try (MockedConstruction<BossJobDetailSession> sessions = sessionMocks(processed);
             MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            assertEquals(0, boss.execute());

            var order = inOrder(cardItems.get(0), cardItems.get(1));
            order.verify(cardItems.get(0)).click();
            order.verify(cardItems.get(1)).click();
            verify(cardItems.get(0), times(1)).click();
            verify(cardItems.get(1), times(1)).click();
            verify(sessions.constructed().get(0), times(2))
                    .await(anyString(), any(Runnable.class), any(Runnable.class), anyInt());
        }
        verify(page, never()).evaluate(anyString());
        verify(page, never()).context();
    }

    @Test
    void alreadySelectedFirstCardIsReadWithoutSwitchingAwayOrClickingItAgain() {
        addCards("first-job");
        selectedId.set("first-job");
        when(endMarker.count()).thenReturn(1);

        try (MockedConstruction<BossJobDetailSession> sessions = sessionMocks(new AtomicInteger());
             MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            boss.execute();
            verify(sessions.constructed().get(0))
                    .await(eq("first-job"), any(Runnable.class), any(Runnable.class), anyInt());
        }
        verify(cardItems.get(0), never()).click();
        verify(page, never()).evaluate(anyString());
    }

    @Test
    void overlappingKeywordsWithDifferentQueryStringsOpenTheJobOnlyOnce() {
        addCards("same-job");
        when(cardLinks.get(0).getAttribute("href")).thenReturn(
                "/job_detail/same-job.html?source=first", "/job_detail/same-job.html?source=second");
        config.setKeywords(List.of("Java实习", "Java后端实习"));
        when(endMarker.count()).thenReturn(1);
        AtomicInteger processed = new AtomicInteger();

        try (MockedConstruction<BossJobDetailSession> sessions = sessionMocks(processed);
             MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            boss.execute();
            assertEquals(2, sessions.constructed().size());
            verify(sessions.constructed().get(0))
                    .await(eq("same-job"), any(Runnable.class), any(Runnable.class), anyInt());
            verify(sessions.constructed().get(1), never())
                    .await(anyString(), any(Runnable.class), any(Runnable.class), anyInt());
        }
        assertEquals(1, processed.get());
        verify(cardItems.get(0), times(1)).click();
    }

    @Test
    void eachExecuteResetsOnlyTheInMemoryVisitLedger() {
        addCards("same-job");
        when(endMarker.count()).thenReturn(1);
        AtomicInteger processed = new AtomicInteger();

        try (MockedConstruction<BossJobDetailSession> sessions = sessionMocks(processed);
             MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            boss.execute();
            boss.execute();
            assertEquals(2, sessions.constructed().size());
            for (BossJobDetailSession session : sessions.constructed()) {
                verify(session).await(eq("same-job"), any(Runnable.class), any(Runnable.class), anyInt());
            }
        }
        assertEquals(2, processed.get());
    }

    @Test
    void explicitEndStopsWithoutScrollingOrWaitingForAnotherBatch() {
        when(endMarker.count()).thenReturn(1);

        assertFalse(boss.loadNextJobBatch("Java实习", 2));

        verify(page, never()).evaluate(anyString());
        verify(page, never()).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
    }

    @Test
    void lateFinalBatchIsProcessedEvenWhenTheEndMarkerHasAlreadyAppeared() {
        when(cards.count()).thenReturn(3);
        when(endMarker.count()).thenReturn(1);

        assertTrue(boss.loadNextJobBatch("Java实习", 2));

        verify(page, never()).evaluate(anyString());
        verify(page, never()).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
    }

    @Test
    void unidentifiedCardCannotReachProcessingWhenReturnedDetailsDoNotMatchVisibleSelection() {
        addCards("card-without-link");
        when(cardLinks.get(0).count()).thenReturn(0);
        selectedId.set("other-job");
        JSONObject root = new JSONObject().put("zpData", new JSONObject().put("jobInfo",
                new JSONObject().put("encryptId", "returned-job").put("jobName", "Java实习生")));
        var detail = new BossJobDetailReader.Detail(root.toString(), root);
        doThrow(new TimeoutError("mock selection never matched"))
                .when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));

        try (MockedStatic<BossJobDetailAwaiter> awaited = mockStatic(BossJobDetailAwaiter.class);
             MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            awaited.when(() -> BossJobDetailAwaiter.await(eq(page), any(Runnable.class),
                    any(Runnable.class), anyInt())).thenReturn(detail);
            BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, boss::execute);
            assertTrue(failure.getMessage().contains("页面尚未确认切换"));
        }
        verify(boss, never()).appendRawJson(anyString());
        verify(page, never()).context();
    }

    @Test
    void missingIdentifiersInBothCardAndResponseAreSkippedWithoutGuessingAJob() {
        addCards("card-without-link");
        when(cardLinks.get(0).count()).thenReturn(0);
        JSONObject root = new JSONObject().put("zpData", new JSONObject().put("jobInfo",
                new JSONObject().put("jobName", "Java实习生").put("postDescription", "整理文档")));
        var detail = new BossJobDetailReader.Detail(root.toString(), root);
        List<String> progress = new ArrayList<>();
        boss.setProgressCallback((message, current, total) -> progress.add(message));
        doReturn(false).when(boss).loadNextJobBatch(anyString(), anyInt());

        try (MockedStatic<BossJobDetailAwaiter> awaited = mockStatic(BossJobDetailAwaiter.class);
             MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            awaited.when(() -> BossJobDetailAwaiter.await(eq(page), any(Runnable.class),
                    any(Runnable.class), anyInt())).thenReturn(detail);
            assertEquals(0, boss.execute());
        }
        assertTrue(progress.stream().anyMatch(message -> message.contains("岗位标识缺失")),
                "the skip must be reported as a data condition, not as a pause");
        verify(boss, never()).appendRawJson(anyString());
        verify(page, never()).context();
    }

    @Test
    void consecutiveMissingIdentifiersNeverPauseTheWholeTask() {
        addCards("card-a", "card-b", "card-c");
        for (Locator link : cardLinks) when(link.count()).thenReturn(0);
        JSONObject root = new JSONObject().put("zpData", new JSONObject().put("jobInfo",
                new JSONObject().put("jobName", "Java实习生")));
        var detail = new BossJobDetailReader.Detail(root.toString(), root);
        List<String> progress = new ArrayList<>();
        boss.setProgressCallback((message, current, total) -> progress.add(message));
        doReturn(false).when(boss).loadNextJobBatch(anyString(), anyInt());

        try (MockedStatic<BossJobDetailAwaiter> awaited = mockStatic(BossJobDetailAwaiter.class);
             MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            awaited.when(() -> BossJobDetailAwaiter.await(eq(page), any(Runnable.class),
                    any(Runnable.class), anyInt())).thenReturn(detail);
            assertEquals(0, boss.execute());
        }
        assertEquals(3, progress.stream().filter(message -> message.contains("岗位标识缺失")).count());
        verify(boss, never()).appendRawJson(anyString());
        verify(page, never()).context();
    }

    @Test
    void nextBatchScrollsOnceThenWaitsReadOnlyUntilNewCardsAppear() {
        AtomicInteger count = new AtomicInteger(2);
        when(cards.count()).thenAnswer(ignored -> count.get());
        doAnswer(ignored -> {
            count.set(3);
            return null;
        }).when(page).evaluate("() => window.scrollTo(0, document.body.scrollHeight)");

        assertTrue(boss.loadNextJobBatch("Java实习", 2));

        verify(page, times(1)).evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
        verify(page, times(1)).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void changedFiltersWithANewEndMarkerPauseInsteadOfFinishingTheKeyword() {
        String initial = "https://www.zhipin.com/web/geek/jobs?city=101&jobType=chosen";
        String changed = "https://www.zhipin.com/web/geek/jobs?city=101&jobType=other";
        AtomicReference<String> current = new AtomicReference<>(initial);
        when(page.url()).thenAnswer(ignored -> current.get());
        when(cards.count()).thenReturn(2);
        when(endMarker.count()).thenAnswer(ignored -> changed.equals(current.get()) ? 1 : 0);
        doAnswer(invocation -> {
            current.set(changed);
            ((BooleanSupplier) invocation.getArgument(0)).getAsBoolean();
            return null;
        }).when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));

        try (BossJobDetailSession session = new BossJobDetailSession(page)) {
            session.beginNavigation();
            session.bindSearchContext(initial);
            ReflectionTestUtils.setField(boss, "detailSession", session);

            BossTaskPausedException failure = assertThrows(BossTaskPausedException.class,
                    () -> boss.loadNextJobBatch("Java实习", 2));

            assertTrue(failure.getMessage().contains("搜索筛选条件已改变"));
        } finally {
            ReflectionTestUtils.setField(boss, "detailSession", null);
        }
        verify(page, times(1)).evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void nextBatchTimeoutPausesWithoutRepeatedScrollingOrNavigation() {
        when(cards.count()).thenReturn(2);
        doThrow(new TimeoutError("mocked slow next batch"))
                .when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));

        BossTaskPausedException failure = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        verify(page, times(1)).evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
        verify(page, times(1)).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void exhaustedDiagnosisFinishesCurrentSearchWithoutAnotherScrollOrSend() {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        List<String> progress = new ArrayList<>();
        boss.setProgressCallback((message, current, total) -> progress.add(message));

        assertFalse(boss.loadNextJobBatch("Java实习", 2));

        verify(diagnosis, times(1)).diagnose(eq(page), any(Runnable.class));
        assertTrue(progress.stream().anyMatch(text -> text.contains("没有更多岗位")));
        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void safetyDiagnosisPausesBeforeConsideringAnExhaustedOrGrowingList() {
        BossListPageDiagnosis diagnosis = diagnosisWithListChange(PageDiagnosticResult.State.SECURITY_VERIFICATION,
                List.of("first", "second", "third"));

        BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, stopped.getReason());
        verify(diagnosis, times(1)).diagnose(eq(page), any(Runnable.class));
        verifyOneScrollWithoutNavigationOrSend();
    }

    @ParameterizedTest
    @EnumSource(value = PageDiagnosticResult.State.class, names = {"LOADING", "UNKNOWN"})
    void loadingOrUnknownDiagnosisPausesInsteadOfFinishingTheSearch(PageDiagnosticResult.State state) {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(state);

        BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, stopped.getReason());
        verify(diagnosis, times(1)).diagnose(eq(page), any(Runnable.class));
        verifyOneScrollWithoutNavigationOrSend();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_CONFIG", "INVALID_CONFIG", "VISION_AUTH_FAILED", "VISION_TIMEOUT",
            "PAGE_DIAGNOSIS_TIMEOUT", "INVALID_PAGE_DIAGNOSIS", "PAGE_DIAGNOSIS_DOCUMENT_CHANGED"})
    void failedOrUnconfiguredDiagnosisPausesWithSafeReason(String code) {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        doThrow(new ScreenshotVisionException(code, "upstream private secret"))
                .when(diagnosis).diagnose(eq(page), any(Runnable.class));

        BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, stopped.getReason());
        assertFalse(stopped.getMessage().contains("secret"));
        verify(diagnosis, times(1)).diagnose(eq(page), any(Runnable.class));
        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void disabledDiagnosisDoesNotCaptureOrCallTheModel() {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        ReflectionTestUtils.setField(boss, "listPageDiagnosisEnabled", false);

        BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertTrue(stopped.getMessage().contains("截图诊断未启用"));
        verifyNoInteractions(diagnosis);
        verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void missingDiagnosisComponentRetainsTheOriginalProtectivePause() {
        timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        ReflectionTestUtils.setField(boss, "listPageDiagnosis", null);

        assertThrows(BossTaskPausedException.class, () -> boss.loadNextJobBatch("Java实习", 2));

        verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void genuinelyAppendedCardsWinOverAnOldExhaustedScreenshot() {
        diagnosisWithListChange(PageDiagnosticResult.State.EXHAUSTED, List.of("first", "second", "third"));

        assertTrue(boss.loadNextJobBatch("Java实习", 2));

        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void sameSizedReplacementListIsNeverTreatedAsExhausted() {
        diagnosisWithListChange(PageDiagnosticResult.State.EXHAUSTED, List.of("new-first", "new-second"));

        BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertTrue(stopped.getMessage().contains("列表已刷新或替换"));
        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void replacedListWithMoreCardsIsNotMistakenForAppendedCards() {
        diagnosisWithListChange(PageDiagnosticResult.State.EXHAUSTED, List.of("other-first", "second", "third"));

        assertThrows(BossTaskPausedException.class, () -> boss.loadNextJobBatch("Java实习", 2));

        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void listShrinkingBeforeDiagnosisPreventsUsingAnyScreenshotResult() {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        diagnosticCards.set(List.of("first"));

        BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertTrue(stopped.getMessage().contains("列表已刷新或缩短"));
        verifyNoInteractions(diagnosis);
        verify(page, never()).evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
        verify(page, never()).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        verify(page, never()).context();
    }

    @Test
    void sameUrlReloadDuringLoadWaitPausesBeforeRequestingDiagnosis() {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        doAnswer(ignored -> {
            documentOrigin.set(67890.0);
            throw new TimeoutError("mocked page reloaded at the same URL");
        }).when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));

        BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertTrue(stopped.getMessage().contains("页面已刷新"));
        verifyNoInteractions(diagnosis);
        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void sameSizedListReplacementDuringLoadWaitPausesBeforeRequestingDiagnosis() {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        doAnswer(ignored -> {
            diagnosticCards.set(List.of("replacement-first", "replacement-second"));
            throw new TimeoutError("mocked list replaced while waiting");
        }).when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));

        BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertTrue(stopped.getMessage().contains("列表已刷新或替换"));
        verifyNoInteractions(diagnosis);
        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void changedSearchFiltersDuringDiagnosisPropagateTheSearchPause() {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        String initial = "https://www.zhipin.com/web/geek/jobs?city=101&jobType=chosen";
        AtomicReference<String> current = new AtomicReference<>(initial);
        when(page.url()).thenAnswer(ignored -> current.get());
        doAnswer(call -> {
            current.set("https://www.zhipin.com/web/geek/jobs?city=102&jobType=chosen");
            ((Runnable) call.getArgument(1)).run();
            return new PageDiagnosticResult(PageDiagnosticResult.State.EXHAUSTED, List.of("没有更多岗位了"));
        }).when(diagnosis).diagnose(eq(page), any(Runnable.class));

        try (BossJobDetailSession session = new BossJobDetailSession(page)) {
            session.beginNavigation();
            session.bindSearchContext(initial);
            ReflectionTestUtils.setField(boss, "detailSession", session);
            BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                    () -> boss.loadNextJobBatch("Java实习", 2));
            assertTrue(stopped.getMessage().contains("搜索筛选条件已改变"));
        } finally {
            ReflectionTestUtils.setField(boss, "detailSession", null);
        }
        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void cancellationDuringDiagnosisPropagatesWithoutTurningIntoNormalExhaustion() {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        AtomicBoolean stopped = new AtomicBoolean();
        boss.setShouldStopCallback(stopped::get);
        doAnswer(call -> {
            stopped.set(true);
            ((Runnable) call.getArgument(1)).run();
            return new PageDiagnosticResult(PageDiagnosticResult.State.EXHAUSTED, List.of("没有更多岗位了"));
        }).when(diagnosis).diagnose(eq(page), any(Runnable.class));

        assertThrows(CancellationException.class, () -> boss.loadNextJobBatch("Java实习", 2));

        verifyOneScrollWithoutNavigationOrSend();
    }

    @Test
    void safetyPageAppearingWhileDiagnosisRunsOverridesItsExhaustedResult() {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(PageDiagnosticResult.State.EXHAUSTED);
        doAnswer(call -> {
            when(page.url()).thenReturn("https://www.zhipin.com/web/user/safe/verify-slider");
            return new PageDiagnosticResult(PageDiagnosticResult.State.EXHAUSTED, List.of("没有更多岗位了"));
        }).when(diagnosis).diagnose(eq(page), any(Runnable.class));

        BossTaskPausedException stopped = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, stopped.getReason());
        verifyOneScrollWithoutNavigationOrSend();
    }

    private BossListPageDiagnosis timedOutDiagnosis(PageDiagnosticResult.State state) {
        BossListPageDiagnosis diagnosis = mock(BossListPageDiagnosis.class);
        ReflectionTestUtils.setField(boss, "listPageDiagnosisEnabled", true);
        ReflectionTestUtils.setField(boss, "listPageDiagnosis", diagnosis);
        when(cards.count()).thenReturn(2);
        when(cards.evaluateAll(Boss.LIST_SNAPSHOT_SCRIPT)).thenAnswer(ignored -> diagnosticCards.get());
        doThrow(new TimeoutError("mocked slow next batch"))
                .when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        when(diagnosis.diagnose(eq(page), any(Runnable.class))).thenAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            List<String> evidence = switch (state) {
                case EXHAUSTED -> List.of("没有更多岗位了");
                case SECURITY_VERIFICATION -> List.of("安全验证");
                case LOADING -> List.of("加载中");
                case UNKNOWN -> List.of();
            };
            return new PageDiagnosticResult(state, evidence);
        });
        return diagnosis;
    }

    private BossListPageDiagnosis diagnosisWithListChange(PageDiagnosticResult.State state, List<String> after) {
        BossListPageDiagnosis diagnosis = timedOutDiagnosis(state);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            diagnosticCards.set(after);
            return new PageDiagnosticResult(state, state == PageDiagnosticResult.State.SECURITY_VERIFICATION
                    ? List.of("安全验证") : List.of("没有更多岗位了"));
        }).when(diagnosis).diagnose(eq(page), any(Runnable.class));
        return diagnosis;
    }

    private void verifyOneScrollWithoutNavigationOrSend() {
        verify(page, times(1)).evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
        verify(page, times(1)).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page, never()).reload();
        verify(page, never()).context();
        verify(cards, never()).nth(anyInt());
    }

    @Test
    void securityVerificationPreventsLoadingAnotherBatch() {
        when(page.url()).thenReturn("https://www.zhipin.com/web/user/safe/verify-slider");

        BossTaskPausedException failure = assertThrows(BossTaskPausedException.class,
                () -> boss.loadNextJobBatch("Java实习", 2));

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(page, never()).evaluate(anyString());
        verify(page, never()).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
    }

    @Test
    void cancellationPreventsEvenProbingThePageForAnotherBatch() {
        boss.setShouldStopCallback(() -> true);

        assertThrows(CancellationException.class, () -> boss.loadNextJobBatch("Java实习", 2));

        verifyNoInteractions(page);
    }

    private void addCards(String... ids) {
        when(cards.count()).thenReturn(ids.length);
        for (int index = 0; index < ids.length; index++) {
            Locator card = mock(Locator.class);
            Locator link = mock(Locator.class);
            when(cards.nth(index)).thenReturn(card);
            when(card.locator("a[href*='/job_detail/']")).thenReturn(link);
            when(link.count()).thenReturn(1);
            when(link.first()).thenReturn(link);
            when(link.getAttribute("href")).thenReturn("/job_detail/" + ids[index] + ".html");
            cardItems.add(card);
            cardLinks.add(link);
        }
    }

    private MockedConstruction<BossJobDetailSession> sessionMocks(AtomicInteger processed) {
        return mockConstruction(BossJobDetailSession.class, (session, context) ->
                when(session.await(anyString(), any(Runnable.class), any(Runnable.class), anyInt()))
                        .thenAnswer(call -> {
                            // No next-batch scroll may precede completion of the current cards.
                            verify(page, never()).evaluate(anyString());
                            String id = call.getArgument(0);
                            Runnable select = call.getArgument(1);
                            select.run();
                            selectedId.set(id);
                            processed.incrementAndGet();
                            JSONObject job = new JSONObject().put("encryptId", id)
                                    .put("jobName", "行政助理").put("salaryDesc", "5-8K")
                                    .put("postDescription", "行政文档整理");
                            JSONObject root = new JSONObject().put("zpData", new JSONObject()
                                    .put("jobInfo", job)
                                    .put("brandComInfo", new JSONObject().put("brandName", "测试公司")));
                            return new BossJobDetailReader.Detail(root.toString(), root);
                        }));
    }
}
