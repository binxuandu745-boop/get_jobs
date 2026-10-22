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
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** A bound detail page survives temporary unknown state in its background search tab. */
class BossScreenshotSelectionStateTest {
    private static final String ID = "selected-job";
    private static final String URL = "https://www.zhipin.com/job_detail/" + ID + ".html";
    private static final String CHAT = "a.btn-startchat, a.op-btn-chat";
    private static final String INPUT = "div#chat-input.chat-input[contenteditable='true'], textarea.input-area";
    private static final String SEND = "div.send-message, button[type='send'].btn-send, button.btn-send";

    enum Selection { SAME_JOB, MISSING_LINK, NULL_HREF, READ_FAILURE, OTHER_JOB }

    private Boss boss;
    private BossScreenshotScreening screening;
    private BossService records;
    private Page listPage;
    private Page detailPage;
    private Locator chat;
    private Locator send;
    private AtomicReference<Selection> selection;
    private AtomicReference<Selection> afterNavigation;

    @BeforeEach
    void setUp() throws Exception {
        records = mock(BossService.class);
        screening = mock(BossScreenshotScreening.class);
        boss = new Boss(records, mock(AiService.class));
        BossConfig config = new BossConfig();
        config.setDebugger(false);
        config.setEnableAI(false);
        config.setSendImgResume(false);
        config.setFilterDeadHR(false);
        config.setSayHi("test greeting");
        boss.setConfig(config);
        boss.setShouldStopCallback(() -> false);
        ReflectionTestUtils.setField(boss, "screenshotScreening", screening);
        ReflectionTestUtils.setField(boss, "screenshotSettings",
                new BossScreenshotSettingsService.Settings(true, ScreenshotRules.defaults()));
        ReflectionTestUtils.setField(boss, "actionDelayMinMs", 1);
        ReflectionTestUtils.setField(boss, "actionDelayMaxMs", 1);

        listPage = usablePage("https://www.zhipin.com/web/geek/jobs");
        detailPage = usablePage(URL);
        boss.setPage(listPage);
        selection = new AtomicReference<>(Selection.SAME_JOB);
        afterNavigation = new AtomicReference<>(Selection.SAME_JOB);
        Locator more = selfLocator();
        when(listPage.locator("a.more-job-btn")).thenReturn(more);
        when(more.count()).thenAnswer(ignored -> {
            if (selection.get() == Selection.READ_FAILURE) {
                throw new IllegalStateException("Execution context was destroyed");
            }
            return selection.get() == Selection.MISSING_LINK ? 0 : 1;
        });
        when(more.getAttribute("href")).thenAnswer(ignored -> switch (selection.get()) {
            case NULL_HREF -> null;
            case OTHER_JOB -> "/job_detail/another-job.html";
            default -> "/job_detail/" + ID + ".html";
        });
        BrowserContext context = mock(BrowserContext.class);
        when(listPage.context()).thenReturn(context);
        when(context.newPage()).thenReturn(detailPage);
        doAnswer(ignored -> {
            selection.set(afterNavigation.get());
            return null;
        }).when(detailPage).navigate(anyString(), any(Page.NavigateOptions.class));

        chat = selfLocator();
        when(detailPage.locator(CHAT)).thenReturn(chat);
        when(chat.count()).thenReturn(1);
        when(chat.textContent()).thenReturn("立即沟通");
        Locator input = selfLocator();
        when(detailPage.locator(INPUT)).thenReturn(input);
        when(input.count()).thenReturn(1);
        when(input.isVisible()).thenReturn(true);
        when(input.evaluate("el => el.tagName.toLowerCase()")).thenReturn("textarea");
        send = selfLocator();
        when(detailPage.locator(SEND)).thenReturn(send);
        when(send.count()).thenReturn(1);
        Locator close = selfLocator();
        when(detailPage.locator("i.icon-close")).thenReturn(close);
        when(screening.analyze(eq(detailPage), eq(ID), any(), any())).thenAnswer(call -> {
            // Exercise the same callback the real capture service runs before each screenshot.
            ((Runnable) call.getArgument(3)).run();
            return matchingResult();
        });
    }

    @ParameterizedTest
    @EnumSource(value = Selection.class, names = {"MISSING_LINK", "NULL_HREF", "READ_FAILURE"})
    void unknownBackgroundSelectionAfterOpeningBoundDetailCanStillCaptureAndCommunicate(Selection unknown) throws Throwable {
        afterNavigation.set(unknown);

        assertTrue(processCurrentJob());

        verify(screening, times(1)).analyze(eq(detailPage), eq(ID), any(), any());
        verify(chat, times(1)).click();
        verify(send, times(1)).click();
        assertEquals(1, boss.getResultList().size());
        assertEquals("Java截图实习", boss.getResultList().getFirst().getJobName());
        verify(records).updateDeliveryStatus(ID, "test-hr", "已投递");
    }

    @Test
    void explicitOtherBackgroundJobStillStopsBeforeCapturingOrCommunicating() {
        afterNavigation.set(Selection.OTHER_JOB);

        assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        verifyNoInteractions(screening);
        verify(chat, never()).click();
        verify(send, never()).click();
        assertTrue(boss.getResultList().isEmpty());
        verify(detailPage).close();
    }

    @Test
    void changedDetailPageStillStopsEvenWhenBackgroundSelectionIsUnknown() {
        afterNavigation.set(Selection.MISSING_LINK);
        when(detailPage.url()).thenReturn("https://www.zhipin.com/job_detail/another-job.html");

        assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        verifyNoInteractions(screening);
        verify(chat, never()).click();
        verify(send, never()).click();
        assertTrue(boss.getResultList().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Selection.class, names = {"MISSING_LINK", "NULL_HREF", "OTHER_JOB"})
    void unknownOrDifferentInitialSelectionCannotOpenDetailPage(Selection unknown) {
        selection.set(unknown);

        assertThrows(BossTaskPausedException.class, this::processCurrentJob);

        verify(listPage, never()).context();
        verify(detailPage, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verifyNoInteractions(screening);
        verify(chat, never()).click();
        verify(send, never()).click();
    }

    private boolean processCurrentJob() throws Throwable {
        JSONObject root = new JSONObject("""
                {"zpData":{"jobInfo":{"encryptId":"selected-job","encryptUserId":"test-hr"},
                "bossInfo":{"name":"Test HR","title":"HR","activeTimeDesc":"今日活跃"}}}
                """);
        Method method = Boss.class.getDeclaredMethod("processScreenshotJob", String.class, BossJobDetailReader.Detail.class);
        method.setAccessible(true);
        try (MockedStatic<PlaywrightUtil> ignored = mockStatic(PlaywrightUtil.class)) {
            try {
                return (boolean) method.invoke(boss, "Java实习", new BossJobDetailReader.Detail(root.toString(), root));
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private static ScreenshotAnalysisResult matchingResult() {
        ScreenshotFields fields = new ScreenshotFields("Java截图实习", "测试公司", "上海", "200元/天",
                new BigDecimal("200"), new BigDecimal("200"), "DAY", null, null, "本科", List.of("Java"),
                List.of(2028), "Java岗位描述", List.of());
        return new ScreenshotAnalysisResult("selection-state-result", "COMPLETE", "2026-10-22T00:00:00Z",
                "boss-selected-job-1.png", "test-hash", fields,
                new ScreenshotEvaluation("MATCH", List.of(), List.of()), null, null, "mock-vision");
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
