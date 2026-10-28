package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.BossScreenshotSettingsService;
import com.getjobs.application.screenshot.ScreenshotAnalysisResult;
import com.getjobs.application.screenshot.ScreenshotEvaluation;
import com.getjobs.application.screenshot.ScreenshotFields;
import com.getjobs.application.screenshot.ScreenshotRules;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.getjobs.worker.boss.Locators.JOB_LIST_SELECTOR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** All browser, database, model and wait operations are mocks; no live application is sent. */
class BossRoundLimitTest {
    private Boss boss;
    private BossConfig config;
    private BossService records;
    private Page page;
    private Page detailPage;
    private Locator cards;
    private Locator input;
    private Locator send;
    private AtomicReference<String> currentUrl;
    private AtomicReference<String> selectedId;
    private AtomicInteger navigation;
    private RuntimeException detailFailure;
    private final List<String> inspected = new ArrayList<>();
    private final List<Integer> deliveredCounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        records = mock(BossService.class);
        boss = spy(new Boss(records, mock(AiService.class)));
        doNothing().when(boss).appendRawJson(anyString());
        config = new BossConfig();
        config.setKeywords(List.of("Java实习"));
        config.setCityCode(List.of("101010100"));
        config.setDebugger(false);
        config.setEnableAI(false);
        config.setSendImgResume(false);
        config.setFilterDeadHR(false);
        config.setSayHi("test greeting");
        boss.setConfig(config);
        boss.setShouldStopCallback(() -> false);
        boss.setProgressCallback((message, current, total) -> { });
        boss.setDeliveryCountCallback(deliveredCounts::add);
        ReflectionTestUtils.setField(boss, "requiredRoleTerms", List.of("java", "后端"));
        ReflectionTestUtils.setField(boss, "detailResponseTimeoutMs", 1000);
        ReflectionTestUtils.setField(boss, "maxPreviewJobs", 2);

        currentUrl = new AtomicReference<>("https://www.zhipin.com/web/geek/jobs");
        selectedId = new AtomicReference<>();
        navigation = new AtomicInteger();
        page = usablePage();
        when(page.url()).thenAnswer(ignored -> currentUrl.get());
        Locator emptyText = locator();
        when(page.getByText(anyString())).thenReturn(emptyText);
        doAnswer(call -> {
            currentUrl.set(call.getArgument(0));
            selectedId.set(null);
            navigation.incrementAndGet();
            return null;
        }).when(page).navigate(anyString(), any(Page.NavigateOptions.class));
        doAnswer(call -> {
            assertTrue(((BooleanSupplier) call.getArgument(0)).getAsBoolean());
            return null;
        }).when(page).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
        Locator containers = locator();
        when(containers.isVisible()).thenReturn(true);
        when(page.locator("ul.rec-job-list, ul.job-list-box, .job-list-box, .search-job-result, li.job-card-box, li.job-card-wrapper"))
                .thenReturn(containers);
        Locator end = locator();
        when(end.count()).thenReturn(1);
        when(page.locator(":text-is('没有更多了'):visible, :text-is('没有更多职位了'):visible, :text-is('没有更多岗位了'):visible, :text-is('已经到底了'):visible"))
                .thenReturn(end);
        cards = locator();
        when(page.locator(JOB_LIST_SELECTOR)).thenReturn(cards);
        Locator more = locator();
        when(more.count()).thenReturn(1);
        when(more.getAttribute("href")).thenAnswer(ignored -> selectedId.get() == null ? null
                : "/job_detail/" + selectedId.get() + ".html");
        when(page.locator("a.more-job-btn")).thenReturn(more);
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        detailPage = usablePage();
        when(detailPage.url()).thenAnswer(ignored -> "https://www.zhipin.com/job_detail/" + selectedId.get() + ".html");
        when(context.newPage()).thenReturn(detailPage);
        Locator chat = locator();
        when(chat.count()).thenReturn(1);
        when(chat.textContent()).thenReturn("立即沟通");
        when(detailPage.locator("a.btn-startchat, a.op-btn-chat")).thenReturn(chat);
        input = locator();
        when(input.count()).thenReturn(1);
        when(input.isVisible()).thenReturn(true);
        when(input.evaluate("el => el.tagName.toLowerCase()")).thenReturn("textarea");
        when(detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area"))
                .thenReturn(input);
        send = locator();
        when(send.count()).thenReturn(1);
        when(detailPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send"))
                .thenReturn(send);
        boss.setPage(page);
    }

    @Test
    void filteredJobsAndAnUnreadyInputDoNotConsumeTheSuccessfulLimit() {
        addCards("filtered", "no-input", "success-a", "success-b", "unopened-tail");
        when(input.count()).thenAnswer(ignored -> "no-input".equals(selectedId.get()) ? 0 : 1);
        boss.setSuccessfulDeliveryLimit(2);

        assertEquals(2, execute());

        assertEquals(List.of("filtered", "no-input", "success-a", "success-b"), inspected);
        assertEquals(List.of(1, 2), deliveredCounts);
        verify(send, times(2)).click();
        verify(page, never()).evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
    }

    @Test
    void limitOfFiftyStopsBeforeReadingTheFiftyFirstSuccessfulCard() {
        String[] ids = java.util.stream.IntStream.rangeClosed(1, 55).mapToObj(i -> "job-" + i).toArray(String[]::new);
        addCards(ids);
        boss.setSuccessfulDeliveryLimit(50);

        assertEquals(50, execute());

        assertEquals(50, inspected.size());
        assertEquals(50, deliveredCounts.getLast());
        verify(send, times(50)).click();
        verify(detailPage, times(50)).close();
    }

    @Test
    void limitSpansKeywordsAndDoesNotStartTheNextCity() {
        addChangingCard();
        config.setKeywords(List.of("Java实习", "Java后端实习"));
        config.setCityCode(List.of("101010100", "101020100"));
        boss.setSuccessfulDeliveryLimit(2);

        assertEquals(2, execute());

        assertEquals(2, navigation.get());
        assertEquals(2, inspected.size());
        verify(send, times(2)).click();
    }

    @Test
    void limitSpansCitiesAndDoesNotNavigateToTheThirdCity() {
        addChangingCard();
        config.setCityCode(List.of("101010100", "101020100", "101280100"));
        boss.setSuccessfulDeliveryLimit(2);

        assertEquals(2, execute());

        assertEquals(2, navigation.get());
        verify(send, times(2)).click();
    }

    @Test
    void screenshotDeliveriesUseTheSameSuccessfulLimit() throws java.io.IOException {
        addCards("visual-a", "visual-b", "unopened-tail");
        BossScreenshotScreening vision = mock(BossScreenshotScreening.class);
        ReflectionTestUtils.setField(boss, "screenshotScreening", vision);
        when(vision.beginTask()).thenReturn(new BossScreenshotSettingsService.Settings(true, ScreenshotRules.defaults()));
        ScreenshotFields fields = new ScreenshotFields("Java实习", "测试公司", "北京", "6-8K",
                BigDecimal.valueOf(6000), BigDecimal.valueOf(8000), "MONTH", null, null,
                "本科", List.of("Java"), List.of(2028), "Java后端", List.of());
        when(vision.analyze(eq(detailPage), anyString(), any(), any())).thenReturn(new ScreenshotAnalysisResult(
                "test-result", "COMPLETE", "2026-10-26", "test.png", "test-hash", fields,
                new ScreenshotEvaluation("MATCH", List.of(), List.of()), null, null, "mock"));
        boss.setSuccessfulDeliveryLimit(2);

        assertEquals(2, execute());

        assertEquals(List.of("visual-a", "visual-b"), inspected);
        verify(vision, times(2)).analyze(eq(detailPage), anyString(), any(), any());
        verify(send, times(2)).click();
        assertEquals(List.of(1, 2), deliveredCounts);
    }

    @Test
    void previewsNeverSendOrConsumeTheSuccessfulLimit() {
        addCards("preview-a", "preview-b", "preview-c");
        config.setDebugger(true);
        config.setKeywords(List.of("Java实习", "Java后端实习"));
        config.setCityCode(List.of("101010100", "101020100"));
        boss.setSuccessfulDeliveryLimit(1);

        assertEquals(0, execute());

        assertEquals(4, navigation.get());
        assertTrue(deliveredCounts.isEmpty());
        verify(page, never()).context();
        verify(send, never()).click();
    }

    @Test
    void sharedSuccessfulIdsAreSkippedBeforeReadingOrCommunicatingInTheNextRound() {
        addCards("already-sent", "new-job");
        Set<String> shared = new HashSet<>(Set.of("already-sent"));
        boss.setDeliveredJobIds(shared);
        boss.setSuccessfulDeliveryLimit(1);

        assertEquals(1, execute());

        assertEquals(List.of("new-job"), inspected);
        assertEquals(Set.of("already-sent", "new-job"), shared);
        verify(send, times(1)).click();
    }

    @Test
    void bookkeepingPrecedesObserverFailureSoLaterRoundsCannotResend() throws Throwable {
        Set<String> shared = new HashSet<>();
        boss.setDeliveredJobIds(shared);
        boss.setDeliveryCountCallback(ignored -> { throw new IllegalStateException("observer failed"); });
        Method record = Boss.class.getDeclaredMethod("recordSentGreeting", Job.class, String.class);
        record.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> record.invoke(boss, new Job(), "https://www.zhipin.com/job_detail/sent-once.html?source=test"));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(1, boss.getResultList().size());
        assertEquals(Set.of("sent-once"), shared);
    }

    @Test
    void zeroLimitKeepsOriginalUnboundedRoundAndNegativeLimitsAreRejected() {
        addCards("job-a", "job-b", "job-c");
        assertThrows(IllegalArgumentException.class, () -> boss.setSuccessfulDeliveryLimit(-1));
        boss.setSuccessfulDeliveryLimit(0);

        assertEquals(3, execute());

        verify(send, times(3)).click();
    }

    @Test
    void ordinaryKeywordErrorsAreReportedInsteadOfQualifyingForAutomaticRestart() {
        addCards("failed-job");
        detailFailure = new IllegalStateException("synthetic keyword load failure");

        assertEquals(0, execute());

        assertTrue(boss.hasProcessingFailures());
        verify(send, never()).click();
    }

    @Test
    void cancellationEscapesTheKeywordLoopUnchanged() {
        addCards("cancelled-job");
        CancellationException cancellation = new CancellationException("synthetic cancellation");
        detailFailure = cancellation;

        assertSame(cancellation, assertThrows(CancellationException.class, this::execute));

        assertFalse(boss.hasProcessingFailures());
        verify(send, never()).click();
    }

    @Test
    void explicitExecutionResetsThePreviousProcessingFailureFlag() {
        addCards("new-job");
        detailFailure = new IllegalStateException("synthetic keyword load failure");
        assertEquals(0, execute());
        assertTrue(boss.hasProcessingFailures());

        detailFailure = null;

        assertEquals(1, execute());
        assertFalse(boss.hasProcessingFailures());
        verify(send, times(1)).click();
    }

    @Test
    void inputWaitExhaustionAfterCommunicationMarksAProcessingFailure() {
        addCards("input-never-ready");
        when(input.count()).thenReturn(0);

        assertEquals(0, execute());

        assertTrue(boss.hasProcessingFailures());
        verify(send, never()).click();
    }

    @Test
    void missingSendButtonMarksAProcessingFailureWithoutClaimingSuccess() {
        addCards("send-never-ready");
        when(send.count()).thenReturn(0);

        assertEquals(0, execute());

        assertTrue(boss.hasProcessingFailures());
        assertTrue(deliveredCounts.isEmpty());
        verify(send, never()).click();
    }

    @Test
    void normalFilteringIsNotAProcessingFailure() {
        addCards("filtered");

        assertEquals(0, execute());

        assertFalse(boss.hasProcessingFailures());
        verify(send, never()).click();
    }

    private int execute() {
        try (MockedConstruction<BossJobDetailSession> ignored = mockConstruction(BossJobDetailSession.class,
                (session, context) -> when(session.await(anyString(), any(Runnable.class), any(Runnable.class), anyInt()))
                        .thenAnswer(call -> {
                            if (detailFailure != null) throw detailFailure;
                            String id = call.getArgument(0);
                            ((Runnable) call.getArgument(1)).run();
                            selectedId.set(id);
                            inspected.add(id);
                            JSONObject info = new JSONObject().put("encryptId", id).put("encryptUserId", "hr-" + id)
                                    .put("jobName", "filtered".equals(id) ? "行政助理" : "Java实习")
                                    .put("salaryDesc", "6-8K").put("postDescription", "filtered".equals(id) ? "文档整理" : "Java后端开发");
                            JSONObject root = new JSONObject().put("zpData", new JSONObject().put("jobInfo", info)
                                    .put("brandComInfo", new JSONObject().put("brandName", "测试公司")));
                            return new BossJobDetailReader.Detail(root.toString(), root);
                        }));
             MockedStatic<PlaywrightUtil> waits = mockStatic(PlaywrightUtil.class)) {
            return boss.execute();
        }
    }

    private void addCards(String... ids) {
        when(cards.count()).thenReturn(ids.length);
        for (int i = 0; i < ids.length; i++) {
            Locator card = locator(), link = locator();
            when(card.locator("a[href*='/job_detail/']")).thenReturn(link);
            when(link.count()).thenReturn(1);
            when(link.getAttribute("href")).thenReturn("/job_detail/" + ids[i] + ".html");
            when(cards.nth(i)).thenReturn(card);
        }
    }

    private void addChangingCard() {
        addCards("ignored-static-id");
        Locator card = cards.nth(0);
        when(card.locator("a[href*='/job_detail/']").getAttribute("href"))
                .thenAnswer(ignored -> "/job_detail/search-" + navigation.get() + ".html");
    }

    private static Page usablePage() {
        Page page = mock(Page.class);
        Locator ordinary = locator();
        when(page.locator(anyString())).thenReturn(ordinary);
        return page;
    }

    private static Locator locator() {
        Locator locator = mock(Locator.class);
        when(locator.first()).thenReturn(locator);
        return locator;
    }
}
