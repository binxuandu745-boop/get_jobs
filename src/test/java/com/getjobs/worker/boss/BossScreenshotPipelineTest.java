package com.getjobs.worker.boss;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.getjobs.application.screenshot.BossScreenshotSettingsService;
import com.getjobs.application.screenshot.OfflineScreenshotService;
import com.getjobs.application.screenshot.ScreenshotAnalysisResult;
import com.getjobs.application.screenshot.ScreenshotEvaluation;
import com.getjobs.application.screenshot.ScreenshotFields;
import com.getjobs.application.screenshot.ScreenshotRules;
import com.getjobs.application.screenshot.ScreenshotVisionException;
import com.getjobs.application.screenshot.ScreenshotVisionConfigService;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Browser and model are mocks: these tests never contact a recruitment site or an AI service. */
class BossScreenshotPipelineTest {
    private static final String ID = "test-id";
    private static final String HR = "test-hr";
    private static final String DETAIL_URL = "https://www.zhipin.com/job_detail/" + ID + ".html";
    private static final String CHAT = "a.btn-startchat, a.op-btn-chat";
    private static final String INPUT = "div#chat-input.chat-input[contenteditable='true'], textarea.input-area";
    private static final String SEND = "div.send-message, button[type='send'].btn-send, button.btn-send";
    private Boss boss;
    private BossConfig config;
    private BossService records;
    private AiService greeting;
    private BossScreenshotScreening screening;
    private Page listPage;
    private Page detailPage;
    private Locator chat;
    private Locator input;
    private Locator send;
    private Locator close;
    private AtomicBoolean stopped;
    private AtomicReference<String> selectedHref;

    @BeforeEach
    void setUp() {
        records = mock(BossService.class);
        greeting = mock(AiService.class);
        screening = mock(BossScreenshotScreening.class);
        boss = new Boss(records, greeting);
        config = new BossConfig();
        config.setDebugger(false);
        config.setEnableAI(true);
        config.setSendImgResume(false);
        config.setFilterDeadHR(true);
        config.setSayHi("fallback greeting");
        config.setCityCode(List.of());
        boss.setConfig(config);
        stopped = new AtomicBoolean();
        boss.setShouldStopCallback(stopped::get);
        ReflectionTestUtils.setField(boss, "screenshotScreening", screening);
        ReflectionTestUtils.setField(boss, "screenshotSettings",
                new BossScreenshotSettingsService.Settings(true, ScreenshotRules.defaults()));

        listPage = usablePage("https://www.zhipin.com/web/geek/jobs");
        detailPage = usablePage(DETAIL_URL);
        boss.setPage(listPage);
        Locator more = selfLocator();
        when(listPage.locator("a.more-job-btn")).thenReturn(more);
        when(more.count()).thenReturn(1);
        selectedHref = new AtomicReference<>("/job_detail/" + ID + ".html");
        when(more.getAttribute("href")).thenAnswer(ignored -> selectedHref.get());
        BrowserContext context = mock(BrowserContext.class);
        when(listPage.context()).thenReturn(context);
        when(context.newPage()).thenReturn(detailPage);

        chat = selfLocator();
        when(detailPage.locator(CHAT)).thenReturn(chat);
        when(chat.count()).thenReturn(1);
        when(chat.textContent()).thenReturn("立即沟通");
        input = selfLocator();
        when(detailPage.locator(INPUT)).thenReturn(input);
        when(input.count()).thenReturn(1);
        when(input.isVisible()).thenReturn(true);
        when(input.evaluate("el => el.tagName.toLowerCase()")).thenReturn("textarea");
        send = selfLocator();
        when(detailPage.locator(SEND)).thenReturn(send);
        when(send.count()).thenReturn(1);
        close = selfLocator();
        when(detailPage.locator("i.icon-close")).thenReturn(close);
        when(greeting.sendRequest(anyString())).thenReturn("vision-based greeting");
    }

    @Test
    void matchCommunicatesOnceAndUsesVisualJobFieldsAndVisualDescriptionForGreeting() throws Throwable {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(result("COMPLETE", "MATCH", fields()));

        processCurrentJob();

        verify(screening, times(1)).analyze(eq(detailPage), eq(ID), any(), any());
        verify(chat, times(1)).click();
        verify(input).fill("vision-based greeting");
        verify(send, times(1)).click();
        verify(greeting).sendRequest(argThat(prompt -> prompt.contains("Java视觉实习")
                && prompt.contains("VISION_DESCRIPTION") && !prompt.contains("OLD_JSON_DESCRIPTION")
                && !prompt.contains("旧JSON销售岗位")));
        assertEquals(1, boss.getResultList().size());
        Job sent = boss.getResultList().getFirst();
        assertEquals("Java视觉实习", sent.getJobName());
        assertEquals("6-8K", sent.getSalary());
        assertEquals("视觉公司", sent.getCompanyName());
        assertEquals("上海", sent.getJobArea());
        assertEquals("VISION_DESCRIPTION", sent.getJobInfo());
        verify(records).recordScreenshotObservation(argThat(entity ->
                "Java视觉实习".equals(entity.getJobName()) && "6-8K".equals(entity.getSalary())
                        && "视觉公司".equals(entity.getCompanyName())
                        && "VISION_DESCRIPTION".equals(entity.getJobDescription())
                        && "未投递".equals(entity.getDeliveryStatus())));
        verify(records).updateDeliveryStatus(ID, HR, "已投递");
        verify(detailPage, atLeastOnce()).close();
        verify(listPage, never()).close();
    }

    @Test
    void sendMovementFailurePausesWithoutRetryingOrRecordingDelivery() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any()))
                .thenReturn(result("COMPLETE", "MATCH", fields()));
        BossMouseMovement movement = mock(BossMouseMovement.class);
        ReflectionTestUtils.setField(boss, "mouseMovement", movement);
        ReflectionTestUtils.setField(boss, "mouseMovementEnabled", true);
        ReflectionTestUtils.setField(boss, "mouseMovementMinMs", 450);
        ReflectionTestUtils.setField(boss, "mouseMovementMaxMs", 1100);
        BossTaskPausedException paused = BossTaskPausedException.detailsUnavailable("test movement unavailable");
        doThrow(paused).when(movement).moveTo(eq(detailPage), eq(send),
                any(Runnable.class), eq(450), eq(1100));

        Logger logger = (Logger) LoggerFactory.getLogger(Boss.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.setLevel(Level.TRACE);
        logger.addAppender(logs);
        try {
            assertSame(paused, assertThrows(BossTaskPausedException.class, this::processCurrentJob));

            verify(chat, times(1)).click();
            verify(input).fill("vision-based greeting");
            verify(send, never()).click();
            verify(close, never()).click();
            verify(movement, times(1)).moveTo(eq(detailPage), eq(send),
                    any(Runnable.class), eq(450), eq(1100));
            verify(movement, never()).moveTo(eq(detailPage), eq(close),
                    any(Runnable.class), eq(450), eq(1100));
            verify(records, never()).updateDeliveryStatus(anyString(), anyString(), eq("已投递"));
            assertTrue(boss.getResultList().isEmpty());
            List<String> failures = logs.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(failures.stream().anyMatch(message -> message.contains("发送招呼")
                    && message.contains("阶段")), () -> "Missing failed action diagnostic: " + failures);
            assertFalse(failures.stream().anyMatch(message -> message.contains("关闭聊天小窗")));
            verify(detailPage).close();
            verify(listPage, never()).close();
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(previousLevel);
            logs.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.zhipin.com/web/geek/chat",
            "https://www.zhipin.com/web/geek/chat?jobId=test-id"
    })
    void standaloneChatNeverLooksForOrClicksPopupCloseEvenWhenAnIconExists(String chatUrl) throws Throwable {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any()))
                .thenReturn(result("COMPLETE", "MATCH", fields()));
        when(close.count()).thenReturn(1);
        when(close.isVisible()).thenReturn(true);
        doAnswer(ignored -> {
            when(detailPage.url()).thenReturn(chatUrl);
            return null;
        }).when(chat).click();

        processCurrentJob();

        verify(detailPage, never()).locator("i.icon-close");
        verifyNoInteractions(close);
        assertGreetingRecordedOnceAndOnlyOwnedPageClosed();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrHiddenPopupCloseDoesNotAttemptCleanup(boolean iconExists) throws Throwable {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any()))
                .thenReturn(result("COMPLETE", "MATCH", fields()));
        when(close.count()).thenReturn(iconExists ? 1 : 0);
        when(close.isVisible()).thenReturn(false);

        processCurrentJob();

        verify(close, never()).click();
        verify(close, never()).click(any(Locator.ClickOptions.class));
        assertGreetingRecordedOnceAndOnlyOwnedPageClosed();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void visiblePopupCleanupUsesOneBoundedClickWithoutMouseMovementAndKeepsSentGreeting(boolean closeTimesOut)
            throws Throwable {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any()))
                .thenReturn(result("COMPLETE", "MATCH", fields()));
        when(close.count()).thenReturn(1);
        when(close.isVisible()).thenReturn(true);
        BossMouseMovement movement = mock(BossMouseMovement.class);
        ReflectionTestUtils.setField(boss, "mouseMovement", movement);
        ReflectionTestUtils.setField(boss, "mouseMovementEnabled", true);
        ReflectionTestUtils.setField(boss, "mouseMovementMinMs", 450);
        ReflectionTestUtils.setField(boss, "mouseMovementMaxMs", 1100);
        if (closeTimesOut) {
            doThrow(new TimeoutError("popup disappeared during cleanup"))
                    .when(close).click(any(Locator.ClickOptions.class));
        }

        processCurrentJob();

        verify(close, times(1)).click(argThat(options -> Double.valueOf(1000).equals(options.timeout)));
        verify(close, never()).click();
        verify(movement, never()).moveTo(eq(detailPage), eq(close), any(Runnable.class), anyInt(), anyInt());
        var order = inOrder(send, records, close);
        order.verify(send).click();
        order.verify(records).updateDeliveryStatus(ID, HR, "已投递");
        order.verify(close).click(any(Locator.ClickOptions.class));
        assertGreetingRecordedOnceAndOnlyOwnedPageClosed();
    }

    @ParameterizedTest
    @CsvSource({"verification,false", "cancel,false", "identity,false",
            "verification,true", "cancel,true", "identity,true"})
    void protectiveStopAfterSendOrCleanupFailureKeepsRecordedGreeting(String reason, boolean duringCleanup)
            throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any()))
                .thenReturn(result("COMPLETE", "MATCH", fields()));
        Runnable triggerStop = () -> {
            switch (reason) {
                case "verification" -> when(detailPage.url())
                        .thenReturn("https://www.zhipin.com/web/passport/zp/verify.html");
                case "cancel" -> stopped.set(true);
                case "identity" -> when(detailPage.url())
                        .thenReturn("https://www.zhipin.com/job_detail/other-job.html");
                default -> throw new AssertionError("Unexpected stop reason: " + reason);
            }
        };
        if (duringCleanup) {
            when(close.count()).thenReturn(1);
            when(close.isVisible()).thenReturn(true);
            doAnswer(ignored -> {
                triggerStop.run();
                throw new TimeoutError("cleanup interrupted by changed page state");
            }).when(close).click(any(Locator.ClickOptions.class));
        } else {
            doAnswer(ignored -> {
                triggerStop.run();
                return null;
            }).when(send).click();
        }

        if ("cancel".equals(reason)) {
            assertThrows(CancellationException.class, this::processCurrentJob);
        } else {
            BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, this::processCurrentJob);
            assertEquals("verification".equals(reason)
                    ? BossTaskPausedException.Reason.SECURITY_VERIFICATION
                    : BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        }

        if (duringCleanup) {
            verify(close, times(1)).click(any(Locator.ClickOptions.class));
        } else {
            verify(detailPage, never()).locator("i.icon-close");
            verifyNoInteractions(close);
        }
        assertGreetingRecordedOnceAndOnlyOwnedPageClosed();
    }

    @Test
    void jsonSubmissionToStandaloneChatRecordsOnceAndClosesOnlyItsNewDetailPage() throws Throwable {
        Job job = prepareJsonSubmission();
        when(close.count()).thenReturn(1);
        when(close.isVisible()).thenReturn(true);
        doAnswer(ignored -> {
            when(detailPage.url()).thenReturn("https://www.zhipin.com/web/geek/chat");
            return null;
        }).when(chat).click();

        submitJsonJob(job);

        verify(detailPage).navigate(eq(DETAIL_URL), any(Page.NavigateOptions.class));
        verify(input).fill("fallback greeting");
        verify(detailPage, never()).locator("i.icon-close");
        verifyNoInteractions(close, screening, greeting);
        assertGreetingRecordedOnceAndOnlyOwnedPageClosed();
        assertSame(job, boss.getResultList().getFirst());
    }

    @Test
    void jsonSubmissionVerificationAfterSendKeepsSentRecordAndLeavesDetailPageForInspection() {
        Job job = prepareJsonSubmission();
        doAnswer(ignored -> {
            when(detailPage.url()).thenReturn("https://www.zhipin.com/web/passport/zp/verify.html");
            return null;
        }).when(send).click();

        BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, () -> submitJsonJob(job));

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(chat, times(1)).click();
        verify(send, times(1)).click();
        verify(records, times(1)).updateDeliveryStatus(ID, HR, "已投递");
        verify(records, never()).updateDeliveryStatus(anyString(), anyString(), eq("投递失败"));
        assertEquals(1, boss.getResultList().size());
        assertSame(job, boss.getResultList().getFirst());
        verify(detailPage, never()).locator("i.icon-close");
        verify(detailPage, never()).close();
        verify(listPage, never()).close();
        verifyNoInteractions(close, screening, greeting);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jsonSubmissionOwnedPageCloseFailureDoesNotChangeSuccessOrRetry(boolean playwrightFailure) throws Throwable {
        Job job = prepareJsonSubmission();
        RuntimeException failure = playwrightFailure
                ? new TimeoutError("owned detail page close failed")
                : new IllegalStateException("owned detail page close failed");
        doThrow(failure).when(detailPage).close();

        submitJsonJob(job);

        assertGreetingRecordedOnceAndOnlyOwnedPageClosed();
        assertSame(job, boss.getResultList().getFirst());
        verifyNoInteractions(screening, greeting);
    }

    @SuppressWarnings("unchecked")
    private Job prepareJsonSubmission() {
        ((Map<String, String>) ReflectionTestUtils.getField(boss, "encryptIdToUserId")).put(ID, HR);
        config.setEnableAI(false);
        Job job = new Job();
        job.setJobName("Java JSON实习");
        job.setCompanyName("JSON测试公司");
        job.setSalary("6-8K");
        return job;
    }

    private void submitJsonJob(Job job) throws Throwable {
        Method method = Boss.class.getDeclaredMethod("resumeSubmission", String.class, Job.class);
        method.setAccessible(true);
        try (MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class)) {
            try {
                method.invoke(boss, "Java实习", job);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private void assertGreetingRecordedOnceAndOnlyOwnedPageClosed() {
        verify(chat, times(1)).click();
        verify(send, times(1)).click();
        verify(records, times(1)).updateDeliveryStatus(ID, HR, "已投递");
        verify(records, never()).updateDeliveryStatus(anyString(), anyString(), eq("投递失败"));
        assertEquals(1, boss.getResultList().size());
        verify(detailPage, times(1)).close();
        verify(listPage, never()).close();
    }

    @Test
    void executeUsesScreenshotDataEvenWhenOldJsonTitleAndSalaryWouldBeRejected() throws Exception {
        boss = spy(boss);
        doNothing().when(boss).appendRawJson(anyString());
        AtomicReference<String> searchUrl = new AtomicReference<>("https://www.zhipin.com/web/geek/jobs");
        when(listPage.url()).thenAnswer(ignored -> searchUrl.get());
        doAnswer(call -> {
            searchUrl.set(call.getArgument(0));
            return null;
        }).when(listPage).navigate(anyString(), any(Page.NavigateOptions.class));
        boss.setProgressCallback((message, current, total) -> { });
        config.setCityCode(List.of("101010100"));
        config.setKeywords(List.of("Java实习"));
        ReflectionTestUtils.setField(boss, "requiredRoleTerms", List.of("java", "后端"));
        ReflectionTestUtils.setField(boss, "minimumMonthlySalary", 5000);
        ReflectionTestUtils.setField(boss, "minimumDailySalary", 125);
        when(screening.beginTask()).thenReturn(new BossScreenshotSettingsService.Settings(true, ScreenshotRules.defaults()));

        Locator visibleList = selfLocator();
        when(visibleList.isVisible()).thenReturn(true);
        when(listPage.locator("ul.rec-job-list, ul.job-list-box, .job-list-box, .search-job-result, li.job-card-box, li.job-card-wrapper"))
                .thenReturn(visibleList);
        Locator empty = selfLocator();
        when(listPage.getByText("没有找到相关职位")).thenReturn(empty);
        Locator cards = selfLocator();
        Locator card = selfLocator();
        Locator link = selfLocator();
        when(listPage.locator(Locators.JOB_LIST_SELECTOR)).thenReturn(cards);
        when(cards.count()).thenReturn(1);
        when(cards.nth(0)).thenReturn(card);
        when(card.locator("a[href*='/job_detail/']")).thenReturn(link);
        when(link.count()).thenReturn(1);
        when(link.getAttribute("href")).thenReturn("/job_detail/" + ID + ".html");
        Locator end = selfLocator();
        when(end.count()).thenReturn(1);
        when(listPage.locator(":text-is('没有更多了'):visible, :text-is('没有更多职位了'):visible, "
                + ":text-is('没有更多岗位了'):visible, :text-is('已经到底了'):visible")).thenReturn(end);
        doAnswer(call -> {
            assertTrue(((BooleanSupplier) call.getArgument(0)).getAsBoolean());
            return null;
        }).when(listPage).waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));

        ScreenshotFields daily = new ScreenshotFields("Java视觉实习", "视觉公司", "上海", "200元/天",
                new BigDecimal("200"), new BigDecimal("200"), "DAY", null, null, "本科", List.of("Java"),
                List.of(2028), "VISION_DESCRIPTION", List.of());
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(result("COMPLETE", "MATCH", daily));
        try (MockedConstruction<BossJobDetailSession> sessions = mockConstruction(BossJobDetailSession.class,
                (session, context) -> when(session.await(eq(ID), any(Runnable.class), any(Runnable.class), anyInt()))
                        .thenAnswer(call -> {
                            ((Runnable) call.getArgument(1)).run();
                            return jsonDetail();
                        }));
             MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class)) {
            assertEquals(1, boss.execute());
            assertEquals(1, sessions.constructed().size());
        }

        verify(screening, times(1)).analyze(eq(detailPage), eq(ID), any(), any());
        verify(chat, times(1)).click();
        verify(send, times(1)).click();
        assertEquals("200元/天", boss.getResultList().getFirst().getSalary());
        verify(records).recordScreenshotObservation(argThat(entity -> "Java视觉实习".equals(entity.getJobName())
                && "200元/天".equals(entity.getSalary()) && "未投递".equals(entity.getDeliveryStatus())));
    }

    @Test
    void mismatchNeverOpensCommunicationOrGeneratesGreeting() throws Throwable {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(result("COMPLETE", "MISMATCH", fields()));

        processCurrentJob();

        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
        assertTrue(boss.getResultList().isEmpty());
        verify(records).recordScreenshotObservation(argThat(entity -> "已过滤".equals(entity.getDeliveryStatus())));
        verify(detailPage).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"本月活跃", "3日内活跃", ""})
    void recentActivityFilterBlocksScreenshotMatchAndRecordsFiltered(String activity) throws Throwable {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(result("COMPLETE", "MATCH", fields()));
        JSONObject root = new JSONObject(jsonDetail().body());
        root.getJSONObject("zpData").getJSONObject("bossInfo").put("activeTimeDesc", activity);
        processCurrentJob(new BossJobDetailReader.Detail(root.toString(), root));

        verify(records).recordScreenshotObservation(argThat(entity -> "已过滤".equals(entity.getDeliveryStatus())));
        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
    }

    @Test
    void missingRecruiterBlocksScreenshotMatchAndRecordsFiltered() throws Throwable {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(result("COMPLETE", "MATCH", fields()));
        JSONObject root = new JSONObject(jsonDetail().body());
        root.getJSONObject("zpData").remove("bossInfo");
        processCurrentJob(new BossJobDetailReader.Detail(root.toString(), root));

        verify(records).recordScreenshotObservation(argThat(entity -> "已过滤".equals(entity.getDeliveryStatus())));
        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
    }

    @Test
    void failedVisionResultPausesInsteadOfCommunicating() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(result("ERROR", "MISMATCH", null));

        BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        assertTrue(failure.getMessage().contains("TEST_VISION_ERROR"));
        verify(records, never()).recordScreenshotObservation(any());
        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
        assertTrue(boss.getResultList().isEmpty());
        verify(detailPage).close();
    }

    @Test
    void invalidModelOutputSkipsThatJobWithoutPausingTheRun() throws Throwable {
        // The vision call succeeded and only this job's JSON was unusable, so the run must continue.
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(invalidExtraction());

        processCurrentJob();

        verify(records, never()).recordScreenshotObservation(any());
        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
        assertTrue(boss.getResultList().isEmpty());
        verify(detailPage).close();
    }

    @Test
    void stoppingDuringModelCallDiscardsMatchAndCannotCommunicate() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenAnswer(ignored -> {
            stopped.set(true);
            return result("COMPLETE", "MATCH", fields());
        });

        assertThrows(CancellationException.class, this::processCurrentJob);

        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
        assertTrue(boss.getResultList().isEmpty());
        verify(detailPage).close();
    }

    @Test
    void selectingAnotherJobDuringModelCallDiscardsMatchAndCannotCommunicate() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenAnswer(ignored -> {
            selectedHref.set("/job_detail/other-job.html");
            return result("COMPLETE", "MATCH", fields());
        });

        assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
        assertTrue(boss.getResultList().isEmpty());
        verify(detailPage).close();
    }

    @Test
    void navigatingDetailToAnotherJobDuringModelCallDiscardsMatch() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenAnswer(ignored -> {
            when(detailPage.url()).thenReturn("https://www.zhipin.com/job_detail/other-job.html");
            return result("COMPLETE", "MATCH", fields());
        });

        assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
    }

    @Test
    void changingDetailDuringPreCommunicationWaitCannotUsePreviousVisionApproval() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(result("COMPLETE", "MATCH", fields()));
        doAnswer(ignored -> {
            when(detailPage.url()).thenReturn("https://www.zhipin.com/job_detail/other-job.html");
            return null;
        }).when(detailPage).waitForTimeout(anyDouble());

        assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        verify(chat, never()).click();
        verifyNoInteractions(greeting);
        assertTrue(boss.getResultList().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat-button", "chat-input"})
    void verificationAppearingDuringCommunicationReadinessPollingKeepsSecurityClassification(String stage)
            throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any()))
                .thenReturn(result("COMPLETE", "MATCH", fields()));
        Runnable showVerification = () -> when(detailPage.url())
                .thenReturn("https://www.zhipin.com/web/passport/zp/verify.html");
        if ("chat-button".equals(stage)) {
            // The first button lookup is not ready; the next poll observes the verification URL.
            when(chat.count()).thenAnswer(ignored -> {
                showVerification.run();
                return 0;
            });
        } else {
            when(input.count()).thenReturn(0);
            // Only the input-readiness loop waits for 500ms; all browser calls remain mocks.
            doAnswer(ignored -> {
                showVerification.run();
                return null;
            }).when(detailPage).waitForTimeout(500);
        }

        BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(chat, times("chat-input".equals(stage) ? 1 : 0)).click();
        if ("chat-input".equals(stage)) verify(detailPage, times(1)).waitForTimeout(500);
        verify(input, never()).fill(anyString());
        verify(send, never()).click();
        verifyNoInteractions(greeting);
        verify(records, never()).updateDeliveryStatus(anyString(), anyString(), eq("已投递"));
        assertTrue(boss.getResultList().isEmpty());
    }

    @Test
    void changingDetailDuringGreetingGenerationCannotFillOrSendToAnotherJob() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any()))
                .thenReturn(result("COMPLETE", "MATCH", fields()));
        when(greeting.sendRequest(anyString())).thenAnswer(ignored -> {
            when(detailPage.url()).thenReturn("https://www.zhipin.com/job_detail/other-job.html");
            return "stale greeting";
        });

        assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        verify(chat).click();
        verify(input, never()).click();
        verify(input, never()).fill(anyString());
        verify(send, never()).click();
        assertTrue(boss.getResultList().isEmpty());
    }

    @Test
    void changingDetailDuringSendDelayCannotSendPreparedGreetingToAnotherJob() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any()))
                .thenReturn(result("COMPLETE", "MATCH", fields()));
        AtomicBoolean prepared = new AtomicBoolean();
        doAnswer(ignored -> { prepared.set(true); return null; }).when(input).fill(anyString());
        doAnswer(ignored -> {
            if (prepared.get()) when(detailPage.url())
                    .thenReturn("https://www.zhipin.com/job_detail/other-job.html");
            return null;
        }).when(detailPage).waitForTimeout(anyDouble());

        assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        verify(input).fill("vision-based greeting");
        verify(send, never()).click();
        assertTrue(boss.getResultList().isEmpty());
    }

    @Test
    void knownCompanyBlacklistStillBlocksVisuallyMatchingJob() throws Throwable {
        ReflectionTestUtils.setField(boss, "blackCompanies", java.util.Set.of("旧JSON公司"));
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(result("COMPLETE", "MATCH", fields()));

        processCurrentJob();

        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
        assertTrue(boss.getResultList().isEmpty());
    }

    @Test
    void failureToSaveVisualObservationPausesBeforeCommunication() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenReturn(result("COMPLETE", "MATCH", fields()));
        doThrow(new IllegalStateException("storage failed")).when(records).recordScreenshotObservation(any());

        assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        verify(detailPage, never()).locator(CHAT);
        verifyNoInteractions(greeting);
        assertTrue(boss.getResultList().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "岗位标题和职位描述尚未就绪，等待已达到上限",
            "岗位内容超过6张截图，尚未完整覆盖，已取消本岗位识别",
            "岗位滚动未前进，尚未完整覆盖职位描述"
    })
    void controlledCaptureDiagnosticAppearsInFinalPauseWithoutCommunicating(String safeReason) throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenThrow(captureFailure(safeReason));

        BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        assertTrue(failure.getMessage().contains(safeReason));
        verify(screening, times(1)).analyze(eq(detailPage), eq(ID), any(), any());
        verify(chat, never()).click();
        verify(send, never()).click();
        verify(records, never()).recordScreenshotObservation(any());
        verifyNoInteractions(greeting);
        verify(detailPage).close();
    }

    @Test
    void unexpectedCaptureFailureDoesNotExposePrivateUpstreamDetails() throws Exception {
        when(screening.analyze(eq(detailPage), eq(ID), any(), any()))
                .thenThrow(new IllegalStateException("private-url?api_key=private-secret"));

        BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        assertFalse(failure.getMessage().contains("private-url"));
        assertFalse(failure.getMessage().contains("private-secret"));
        verify(chat, never()).click();
        verify(send, never()).click();
        verifyNoInteractions(greeting);
    }

    @Test
    void captureReadinessPollingStillSubmitsOneOrderedImageSetAndCommunicatesOnce() throws Throwable {
        OfflineScreenshotService analysis = mock(OfflineScreenshotService.class);
        installRealScreening(analysis);
        when(analysis.analyze(anyList(), any(ScreenshotRules.class))).thenReturn(result("COMPLETE", "MATCH", fields()));
        List<OfflineScreenshotService.Upload> images = List.of(
                new OfflineScreenshotService.Upload(new byte[]{1}, "first-view.png"),
                new OfflineScreenshotService.Upload(new byte[]{2}, "second-view.png"));
        try (MockedStatic<BossJobScreenshotCapture> capture = captureMock()) {
            capture.when(() -> BossJobScreenshotCapture.capture(eq(detailPage), eq(ID), any(Runnable.class)))
                    .thenAnswer(call -> {
                        Runnable guard = call.getArgument(2);
                        // Readiness probes stay inside capture, before the one paid analysis call.
                        for (int probe = 0; probe < 4; probe++) {
                            guard.run();
                            detailPage.waitForTimeout(125);
                        }
                        guard.run();
                        return images;
                    });

            processCurrentJob();

            capture.verify(() -> BossJobScreenshotCapture.capture(eq(detailPage), eq(ID), any(Runnable.class)), times(1));
        }
        verify(analysis, times(1)).analyze(argThat(actual -> actual.size() == 2
                && "boss-test-id-1.png".equals(actual.get(0).sourceName())
                && "boss-test-id-2.png".equals(actual.get(1).sourceName())
                && actual.get(0).image() == images.get(0).image()
                && actual.get(1).image() == images.get(1).image()), any(ScreenshotRules.class));
        verify(chat, times(1)).click();
        verify(send, times(1)).click();
        assertEquals(1, boss.getResultList().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "verification", "identity"})
    void captureSafetyStopCannotBeRetriedOrReachTheModel(String stop) throws Exception {
        OfflineScreenshotService analysis = mock(OfflineScreenshotService.class);
        installRealScreening(analysis);
        try (MockedStatic<BossJobScreenshotCapture> capture = captureMock()) {
            capture.when(() -> BossJobScreenshotCapture.capture(eq(detailPage), eq(ID), any(Runnable.class)))
                    .thenAnswer(call -> {
                        switch (stop) {
                            case "cancel" -> stopped.set(true);
                            case "verification" -> when(detailPage.url())
                                    .thenReturn("https://www.zhipin.com/web/passport/zp/verify.html");
                            case "identity" -> when(detailPage.url())
                                    .thenReturn("https://www.zhipin.com/job_detail/other-job.html");
                            default -> fail("unknown stop fixture");
                        }
                        ((Runnable) call.getArgument(2)).run();
                        return fail("The safety guard must stop before any model call");
                    });

            if ("cancel".equals(stop)) {
                assertThrows(CancellationException.class, this::processCurrentJob);
            } else {
                BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, this::processCurrentJob);
                assertEquals("verification".equals(stop)
                        ? BossTaskPausedException.Reason.SECURITY_VERIFICATION
                        : BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
            }
            capture.verify(() -> BossJobScreenshotCapture.capture(eq(detailPage), eq(ID), any(Runnable.class)), times(1));
        }
        verifyNoInteractions(analysis, greeting);
        verify(chat, never()).click();
        verify(send, never()).click();
        verify(records, never()).recordScreenshotObservation(any());
        assertTrue(boss.getResultList().isEmpty());
    }

    @Test
    void missingVisionConfigurationStopsBeforeAnyJobNavigation() {
        when(screening.beginTask()).thenThrow(new ScreenshotVisionException("MISSING_CONFIG", "missing config"));

        assertThrows(BossTaskPausedException.class, boss::execute);

        verifyNoInteractions(listPage, detailPage, greeting);
    }

    @Test
    void previewDoesNotReadVisionCredentialsOrCallVisionApi() {
        config.setDebugger(true);

        assertEquals(0, boss.execute());

        verifyNoInteractions(screening, listPage, detailPage, greeting);
    }

    private void processCurrentJob() throws Throwable {
        processCurrentJob(jsonDetail());
    }

    private void processCurrentJob(BossJobDetailReader.Detail detail) throws Throwable {
        Method method = Boss.class.getDeclaredMethod("processScreenshotJob", String.class, BossJobDetailReader.Detail.class);
        method.setAccessible(true);
        try (MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class)) {
            try {
                method.invoke(boss, "Java实习", detail);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private static BossJobDetailReader.Detail jsonDetail() {
        String body = """
                {"zpData":{"jobInfo":{"encryptId":"test-id","encryptUserId":"test-hr",
                "jobName":"旧JSON销售岗位","salaryDesc":"1-2K","postDescription":"OLD_JSON_DESCRIPTION"},
                "brandComInfo":{"brandName":"旧JSON公司"},"bossInfo":{"name":"Test HR","title":"HR","activeTimeDesc":"今日活跃"}}}
                """;
        return new BossJobDetailReader.Detail(body, new JSONObject(body));
    }

    private static ScreenshotAnalysisResult result(String status, String decision, ScreenshotFields fields) {
        return new ScreenshotAnalysisResult("test-result", status, "2026-10-22T00:00:00Z", "boss-test-id-1.png",
                "test-hash", fields, new ScreenshotEvaluation(decision, List.of("test reason"), List.of()),
                "ERROR".equals(status) ? "TEST_VISION_ERROR" : null, null, "mock-vision");
    }

    private static ScreenshotFields fields() {
        return new ScreenshotFields("Java视觉实习", "视觉公司", "上海", "6-8K", new BigDecimal("6000"),
                new BigDecimal("8000"), "MONTH", null, null, "本科", List.of("Java"), List.of(2028),
                "VISION_DESCRIPTION", List.of());
    }

    private static ScreenshotAnalysisResult invalidExtraction() {
        return new ScreenshotAnalysisResult("test-result", "ERROR", "2026-10-22T00:00:00Z", "boss-test-id-1.png",
                "test-hash", null, new ScreenshotEvaluation("MISMATCH",
                List.of("识别失败，按不符合条件处理：AI输出未通过字段校验"), List.of()),
                "INVALID_EXTRACTION", "AI输出未通过字段校验：字段类型或长度不合法：jobTitle", "mock-vision");
    }

    private void installRealScreening(OfflineScreenshotService analysis) {
        BossScreenshotScreening bridge = new BossScreenshotScreening(mock(BossScreenshotSettingsService.class),
                mock(ScreenshotVisionConfigService.class), analysis);
        ReflectionTestUtils.setField(boss, "screenshotScreening", bridge);
    }

    private static MockedStatic<BossJobScreenshotCapture> captureMock() {
        MockedStatic<BossJobScreenshotCapture> capture = mockStatic(BossJobScreenshotCapture.class);
        capture.when(() -> BossJobScreenshotCapture.matchesJobUrl(anyString(), anyString())).thenCallRealMethod();
        return capture;
    }

    private static BossJobScreenshotCapture.CaptureException captureFailure(String safeReason) throws Exception {
        var constructor = BossJobScreenshotCapture.CaptureException.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(safeReason);
    }

    private static Page usablePage(String url) {
        Page page = mock(Page.class);
        when(page.url()).thenReturn(url);
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
