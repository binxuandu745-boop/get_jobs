package com.getjobs.worker.service;

import com.getjobs.worker.boss.BossTaskPausedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.swing.SwingUtilities;
import java.awt.AWTError;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.getjobs.worker.boss.BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE;
import static com.getjobs.worker.boss.BossTaskPausedException.Reason.SECURITY_VERIFICATION;
import static org.junit.jupiter.api.Assertions.*;

class BossPauseNotifierTest {
    @Test
    void disabledNotificationsDoNotInspectOrUseTheDesktop() {
        RecordingNotifier notifier = new RecordingNotifier();
        ReflectionTestUtils.setField(notifier, "enabled", false);
        notifier.failure = Failure.HEADLESS_CHECK;

        assertDoesNotThrow(() -> notifier.notifyPaused(SECURITY_VERIFICATION, "验证提示"));

        assertEquals(0, notifier.headlessChecks);
        assertTrue(notifier.pending.isEmpty());
        assertTrue(notifier.windows.isEmpty());
        assertEquals(0, notifier.beeps);
    }

    @Test
    void headlessEnvironmentSkipsNotification() {
        RecordingNotifier notifier = new RecordingNotifier();
        notifier.headless = true;

        notifier.notifyPaused(JOB_DETAILS_UNAVAILABLE, "页面提示");

        assertEquals(1, notifier.headlessChecks);
        assertTrue(notifier.pending.isEmpty());
        assertTrue(notifier.windows.isEmpty());
        assertEquals(0, notifier.beeps);
    }

    @ParameterizedTest
    @EnumSource(BossTaskPausedException.Reason.class)
    void notificationsAreEnabledByDefaultAndPreservePauseMessage(BossTaskPausedException.Reason reason) {
        RecordingNotifier notifier = new RecordingNotifier();
        String message = "已暂停：人工检查后再手动开始";

        notifier.notifyPaused(reason, message);

        assertEquals(1, notifier.pending.size());
        assertTrue(notifier.windows.isEmpty(), "The task thread only queues desktop work");
        notifier.flush();
        RecordingWindow window = notifier.windows.getFirst();
        assertEquals(message, window.message);
        assertEquals(reason == SECURITY_VERIFICATION ? "Boss 任务已暂停：需要安全验证"
                : "Boss 任务已暂停：页面读取异常", window.title);
        assertEquals(1, notifier.beeps);
    }

    @ParameterizedTest
    @EnumSource(BossTaskPausedException.Reason.class)
    void missingMessageStillTellsTheUserToCheckAndRestartManually(BossTaskPausedException.Reason reason) {
        RecordingNotifier notifier = new RecordingNotifier();

        notifier.notifyPaused(reason, null);
        notifier.flush();

        String message = notifier.windows.getFirst().message;
        assertTrue(message.contains("手动开始任务"));
        assertTrue(message.contains(reason == SECURITY_VERIFICATION ? "安全验证" : "岗位详情"));
    }

    @Test
    void repeatedPauseUpdatesOneWindowAndBeepsOnce() {
        RecordingNotifier notifier = new RecordingNotifier();

        notifier.notifyPaused(SECURITY_VERIFICATION, "先出现验证");
        notifier.notifyPaused(JOB_DETAILS_UNAVAILABLE, "随后页面读取异常");
        notifier.flush();

        assertEquals(1, notifier.windows.size());
        RecordingWindow window = notifier.windows.getFirst();
        assertEquals(2, window.shows);
        assertEquals("Boss 任务已暂停：页面读取异常", window.title);
        assertEquals("随后页面读取异常", window.message);
        assertEquals(1, notifier.beeps);
    }

    @Test
    void nextPauseCanCreateANewWindowAfterTheUserClosesIt() {
        RecordingNotifier notifier = new RecordingNotifier();
        notifier.notifyPaused(SECURITY_VERIFICATION, "第一次暂停");
        notifier.flush();
        notifier.windows.getFirst().dispose();

        notifier.notifyPaused(JOB_DETAILS_UNAVAILABLE, "下一次暂停");
        notifier.flush();

        assertEquals(2, notifier.windows.size());
        assertEquals("下一次暂停", notifier.windows.getLast().message);
        assertEquals(2, notifier.beeps);
    }

    @ParameterizedTest
    @EnumSource(value = Failure.class, names = {"HEADLESS_CHECK", "SCHEDULE", "CREATE", "SHOW", "BEEP"})
    void desktopFailureDoesNotEscapeToThePausedTask(Failure failure) {
        RecordingNotifier notifier = new RecordingNotifier();
        notifier.failure = failure;

        assertDoesNotThrow(() -> notifier.notifyPaused(SECURITY_VERIFICATION, "已暂停"));
        assertDoesNotThrow(notifier::flush);

        if (failure == Failure.SHOW) {
            assertEquals(1, notifier.windows.getFirst().disposals,
                    "A partially displayed window is cleaned up after failure");
        }
        if (failure == Failure.BEEP) {
            assertEquals("已暂停", notifier.windows.getFirst().message);
            assertEquals(0, notifier.windows.getFirst().disposals,
                    "A sound failure should not close the visible notification");
        }
    }

    @Test
    void shutdownDisposesTheWindowAndIgnoresLaterAndAlreadyQueuedNotifications() {
        RecordingNotifier notifier = new RecordingNotifier();
        notifier.notifyPaused(SECURITY_VERIFICATION, "已显示");
        notifier.flush();
        notifier.notifyPaused(JOB_DETAILS_UNAVAILABLE, "等待显示");

        notifier.destroy();
        notifier.notifyPaused(SECURITY_VERIFICATION, "关闭后不再显示");
        notifier.flush();

        assertEquals(1, notifier.windows.size());
        RecordingWindow window = notifier.windows.getFirst();
        assertEquals(1, window.shows);
        assertEquals(1, window.disposals);
        assertFalse(window.displayable);
        assertEquals(1, notifier.beeps);
    }

    @Test
    void cleanupFailureDoesNotEscapeShutdown() {
        RecordingNotifier notifier = new RecordingNotifier();
        notifier.notifyPaused(SECURITY_VERIFICATION, "已暂停");
        notifier.flush();
        notifier.failure = Failure.DISPOSE;

        assertDoesNotThrow(notifier::destroy);
        assertDoesNotThrow(notifier::flush);
        notifier.notifyPaused(SECURITY_VERIFICATION, "关闭后不再显示");
        assertTrue(notifier.pending.isEmpty());
    }

    @Test
    void defaultSchedulerUsesEdtAndReturnsWithoutWaitingForTheWindow() throws Exception {
        CountDownLatch showStarted = new CountDownLatch(1);
        CountDownLatch releaseShow = new CountDownLatch(1);
        AtomicBoolean showedOnEdt = new AtomicBoolean();
        AtomicBoolean showFinished = new AtomicBoolean();
        AtomicBoolean disposedOnEdt = new AtomicBoolean();
        BossPauseNotifier notifier = new BossPauseNotifier() {
            @Override
            protected boolean isHeadless() { return false; }

            @Override
            protected AlertWindow createAlertWindow() {
                return new AlertWindow() {
                    @Override
                    public boolean isDisplayable() { return true; }

                    @Override
                    public void show(String title, String message) {
                        showedOnEdt.set(SwingUtilities.isEventDispatchThread());
                        showStarted.countDown();
                        try {
                            releaseShow.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } finally {
                            showFinished.set(true);
                        }
                    }

                    @Override
                    public void dispose() { disposedOnEdt.set(SwingUtilities.isEventDispatchThread()); }
                };
            }

            @Override
            protected void beep() { /* No sound in tests. */ }
        };
        ExecutorService taskThread = Executors.newSingleThreadExecutor();
        try {
            taskThread.submit(() -> notifier.notifyPaused(SECURITY_VERIFICATION, "已暂停"))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(showStarted.await(5, TimeUnit.SECONDS));
            assertTrue(showedOnEdt.get());
            assertFalse(showFinished.get(), "The task returns while EDT display work is still pending");
        } finally {
            releaseShow.countDown();
            notifier.destroy();
            taskThread.shutdownNow();
            SwingUtilities.invokeAndWait(() -> {});
        }
        assertTrue(disposedOnEdt.get());
    }

    private enum Failure { HEADLESS_CHECK, SCHEDULE, CREATE, SHOW, BEEP, DISPOSE }

    private static class RecordingNotifier extends BossPauseNotifier {
        final Queue<Runnable> pending = new ArrayDeque<>();
        final List<RecordingWindow> windows = new ArrayList<>();
        boolean headless;
        int headlessChecks;
        int beeps;
        Failure failure;

        @Override
        protected boolean isHeadless() {
            headlessChecks++;
            failIf(Failure.HEADLESS_CHECK);
            return headless;
        }

        @Override
        protected void runOnEventDispatchThread(Runnable action) {
            failIf(Failure.SCHEDULE);
            pending.add(action);
        }

        @Override
        protected AlertWindow createAlertWindow() {
            failIf(Failure.CREATE);
            RecordingWindow window = new RecordingWindow(this);
            windows.add(window);
            return window;
        }

        @Override
        protected void beep() {
            failIf(Failure.BEEP);
            beeps++;
        }

        void flush() {
            while (!pending.isEmpty()) pending.remove().run();
        }

        void failIf(Failure stage) {
            if (failure == stage) throw new AWTError("Simulated desktop failure: " + stage);
        }
    }

    private static final class RecordingWindow implements BossPauseNotifier.AlertWindow {
        private final RecordingNotifier owner;
        boolean displayable = true;
        String title;
        String message;
        int shows;
        int disposals;

        private RecordingWindow(RecordingNotifier owner) { this.owner = owner; }

        @Override
        public boolean isDisplayable() { return displayable; }

        @Override
        public void show(String title, String message) {
            owner.failIf(Failure.SHOW);
            this.title = title;
            this.message = message;
            shows++;
        }

        @Override
        public void dispose() {
            disposals++;
            owner.failIf(Failure.DISPOSE);
            displayable = false;
        }
    }
}
