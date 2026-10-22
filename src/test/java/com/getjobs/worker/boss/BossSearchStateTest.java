package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.Job;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;

/** Regression coverage for bounded search waits and preview-only safety. */
class BossSearchStateTest {
    private Page page;
    private BossService bossService;
    private AiService aiService;
    private Boss boss;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        bossService = mock(BossService.class);
        aiService = mock(AiService.class);
        boss = new Boss(bossService, aiService);
        boss.setPage(page);
        Locator verification = mock(Locator.class);
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
    }

    @Test
    void explicitEmptyResultsReturnImmediatelyWithoutWaiting() throws Throwable {
        Locator emptyMessage = mock(Locator.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.getByText("没有找到相关职位")).thenReturn(emptyMessage);
        when(emptyMessage.isVisible()).thenReturn(true);

        assertFalse(waitForJobList());

        verify(page, never()).waitForTimeout(500);
        verify(page, atLeastOnce()).locator(BossPageSafety.VERIFICATION_SELECTOR);
        verify(page, never()).evaluate(anyString());
        verifyNoInteractions(bossService, aiService);
    }

    @Test
    void visibleListReturnsImmediatelyWithoutWaiting() throws Throwable {
        Locator list = mockSearchPage();
        when(list.isVisible()).thenReturn(true);

        assertTrue(waitForJobList());

        verify(page, never()).waitForTimeout(500);
        verify(page, never()).evaluate(anyString());
    }

    @Test
    void stopRequestCancelsBeforeAnyBrowserOperation() {
        boss.setShouldStopCallback(() -> true);

        assertThrows(CancellationException.class, this::waitForJobList);

        verifyNoInteractions(page, bossService, aiService);
    }

    @Test
    void interruptedThreadCancelsBeforeAnyBrowserOperation() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, this::waitForJobList);
            verifyNoInteractions(page, bossService, aiService);
        } finally {
            // Do not leak an interrupt flag into the next test or Gradle's worker.
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.zhipin.com/web/user/safe/verify-slider",
            "https://www.zhipin.com/web/passport/zp/verify.html",
            "https://www.zhipin.com/web/passport/zp/security.html",
            "https://www.zhipin.com/security-check"
    })
    void securityVerificationRequiresManualActionWithoutWaiting(String url) {
        when(page.url()).thenReturn(url);

        IllegalStateException failure = assertThrows(IllegalStateException.class, this::waitForJobList);

        assertTrue(failure.getMessage().contains("手动完成安全验证"));
        assertTrue(failure.getMessage().contains("程序不会代办验证"));
        verify(page, never()).waitForTimeout(500);
        verify(page, never()).getByText(anyString());
        verify(page, never()).locator(anyString());
        verify(page, never()).evaluate(anyString());
    }

    @Test
    void listAppearingAfterOneProbeUsesOneBoundedWait() throws Throwable {
        Locator list = mockSearchPage();
        when(list.isVisible()).thenReturn(false, true);

        assertTrue(waitForJobList());

        verify(page, times(1)).waitForTimeout(500);
        verify(page, never()).evaluate(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void filtersChangingAndReturningDuringTheFirstDomProbeCannotBeAccepted(boolean emptyResult) {
        Locator list = mockSearchPage();
        String initial = "https://www.zhipin.com/web/geek/jobs?city=101&jobType=chosen&query=Java";
        String changed = initial.replace("jobType=chosen", "jobType=other");
        AtomicReference<String> current = new AtomicReference<>(initial);
        AtomicReference<Consumer<Frame>> navigation = new AtomicReference<>();
        Frame main = mock(Frame.class);
        when(page.url()).thenAnswer(ignored -> current.get());
        when(page.mainFrame()).thenReturn(main);
        when(main.url()).thenAnswer(ignored -> current.get());
        doAnswer(call -> { navigation.set(call.getArgument(0)); return null; })
                .when(page).onFrameNavigated(any());
        Locator probe = emptyResult ? page.getByText("没有找到相关职位") : list;
        when(probe.isVisible()).thenAnswer(ignored -> {
            current.set(changed);
            navigation.get().accept(main);
            current.set(initial);
            navigation.get().accept(main);
            return true;
        });

        try (BossJobDetailSession session = new BossJobDetailSession(page)) {
            session.beginNavigation();
            ReflectionTestUtils.setField(boss, "detailSession", session);
            ReflectionTestUtils.setField(boss, "expectedSearchUrl", initial);

            BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, this::waitForJobList);

            assertTrue(failure.getMessage().contains("搜索筛选条件已改变"));
            verify(page, never()).waitForTimeout(500);
        } finally {
            ReflectionTestUtils.setField(boss, "detailSession", null);
            ReflectionTestUtils.setField(boss, "expectedSearchUrl", null);
        }
    }

    @Test
    void missingListFailsAfterThirtyBoundedProbes() {
        mockSearchPage();

        IllegalStateException failure = assertThrows(IllegalStateException.class, this::waitForJobList);

        assertTrue(failure.getMessage().contains("岗位列表始终未出现"));
        verify(page, times(30)).waitForTimeout(500);
        verify(page, times(1)).evaluate(anyString());
    }

    @Test
    void debuggerModeCannotOpenChatOrSendMessages() throws Throwable {
        BossConfig config = new BossConfig();
        config.setDebugger(true);
        // Even if these options are set, preview mode must short-circuit first.
        config.setEnableAI(true);
        config.setSendImgResume(true);
        config.setSayHi("This text must never be sent");
        boss.setConfig(config);

        resumeSubmission();

        verifyNoInteractions(page, bossService, aiService);
        assertTrue(boss.getResultList().isEmpty());
    }

    @Test
    void stoppedSubmissionCannotOpenChatOrSendMessages() throws Throwable {
        BossConfig config = new BossConfig();
        config.setDebugger(false);
        boss.setConfig(config);
        boss.setShouldStopCallback(() -> true);

        resumeSubmission();

        verifyNoInteractions(page, bossService, aiService);
        assertTrue(boss.getResultList().isEmpty());
    }

    private Locator mockSearchPage() {
        Locator emptyMessage = mock(Locator.class);
        Locator containers = mock(Locator.class);
        Locator firstContainer = mock(Locator.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.getByText("没有找到相关职位")).thenReturn(emptyMessage);
        when(page.locator(anyString())).thenReturn(containers);
        when(containers.first()).thenReturn(firstContainer);
        Locator verification = mock(Locator.class);
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        return firstContainer;
    }

    private boolean waitForJobList() throws Throwable {
        return (boolean) invokePrivate("waitForJobList", new Class<?>[0]);
    }

    private void resumeSubmission() throws Throwable {
        Job job = new Job();
        job.setCompanyName("Test company");
        job.setJobName("Java internship");
        invokePrivate("resumeSubmission", new Class<?>[]{String.class, Job.class}, "Java实习", job);
    }

    private Object invokePrivate(String name, Class<?>[] parameterTypes, Object... arguments) throws Throwable {
        Method method = Boss.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(boss, arguments);
        } catch (InvocationTargetException failure) {
            // Preserve the actual failure type for assertThrows rather than the reflection wrapper.
            throw failure.getCause();
        }
    }
}
