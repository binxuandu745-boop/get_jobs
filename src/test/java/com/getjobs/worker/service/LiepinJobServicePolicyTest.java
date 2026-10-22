package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.liepin.LiepinTaskPausedException;
import com.getjobs.worker.manager.BrowserModeSwitchBusyException;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Service lifecycle checks use mocks only; no browser, configuration storage or messages are accessed. */
class LiepinJobServicePolicyTest {
    private PlaywrightManager manager;
    private Liepin worker;
    private Page page;
    private LiepinConfig config;
    private LiepinJobService service;
    private List<JobProgressMessage> messages;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(PlaywrightManager.class);
        ConfigService configService = mock(ConfigService.class);
        ObjectProvider<Liepin> provider = mock(ObjectProvider.class);
        worker = mock(Liepin.class);
        page = mock(Page.class);
        config = new LiepinConfig();
        config.setKeywords(List.of("Java"));
        service = new LiepinJobService(manager, configService, provider);
        messages = new ArrayList<>();

        when(manager.getLiepinPage()).thenReturn(page);
        when(manager.isLoggedIn("liepin")).thenReturn(true);
        when(configService.getLiepinConfig()).thenReturn(config);
        when(provider.getObject()).thenReturn(worker);
        when(manager.callOnPlaywright(any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(0)).call());
    }

    @ParameterizedTest
    @EnumSource(LiepinTaskPausedException.Reason.class)
    void protectivePausesKeepTheirReasonAndEmitErrorWithoutSuccessOrAutomaticRestart(
            LiepinTaskPausedException.Reason reason) {
        LiepinTaskPausedException pause = pauseFor(reason);
        when(worker.execute()).thenThrow(pause);

        service.executeDelivery(messages::add);

        assertEquals(true, service.getStatus().get("paused"));
        assertEquals(reason.name(), service.getStatus().get("pauseReason"));
        assertEquals(pause.getMessage(), service.getStatus().get("lastMessage"));
        assertEquals(1, messages.stream().filter(message -> "error".equals(message.getType())
                && pause.getMessage().equals(message.getMessage())).count());
        assertTrue(messages.stream().noneMatch(message -> "success".equals(message.getType())));
        assertRunFinished();
        verifySingleRunMonitoringLifecycle();
    }

    @Test
    void cancellationEmitsStoppedWarningWithoutSuccessOrProtectivePause() {
        when(worker.execute()).thenThrow(new CancellationException("用户取消猎聘任务"));

        service.executeDelivery(messages::add);

        assertEquals(false, service.getStatus().get("paused"));
        assertEquals("", service.getStatus().get("pauseReason"));
        assertEquals("任务已停止", service.getStatus().get("lastMessage"));
        assertEquals(1, messages.stream().filter(message -> "warning".equals(message.getType())
                && "任务已停止".equals(message.getMessage())).count());
        assertTrue(messages.stream().noneMatch(message -> "success".equals(message.getType())
                || "error".equals(message.getType())));
        assertRunFinished();
        verifySingleRunMonitoringLifecycle();
    }

    @Test
    void successfulRunReportsTheReturnedCountAndKeepsPauseStateClear() {
        when(worker.execute()).thenReturn(7);

        service.executeDelivery(messages::add);

        assertEquals(false, service.getStatus().get("paused"));
        assertEquals("", service.getStatus().get("pauseReason"));
        assertEquals("任务处理完成，共确认建立7次沟通（不代表招呼或简历送达）", service.getStatus().get("lastMessage"));
        List<JobProgressMessage> successMessages = messages.stream()
                .filter(message -> "success".equals(message.getType())).toList();
        assertEquals(1, successMessages.size());
        assertEquals("liepin", successMessages.get(0).getPlatform());
        assertEquals("任务处理完成，共确认建立7次沟通（不代表招呼或简历送达）", successMessages.get(0).getMessage());
        assertTrue(messages.stream().noneMatch(message -> "error".equals(message.getType())
                || "warning".equals(message.getType())));
        verify(worker).setPage(page);
        verify(worker).setConfig(config);
        verify(worker).setProgressCallback(any());
        verify(worker).setShouldStopCallback(any());
        assertRunFinished();
        verifySingleRunMonitoringLifecycle();
    }

    @Test
    void onlyAnExplicitNewRunClearsAPreviousProtectivePause() {
        when(worker.execute()).thenThrow(LiepinTaskPausedException.verificationRequired()).thenReturn(2);

        service.executeDelivery(messages::add);

        assertEquals(true, service.getStatus().get("paused"));
        assertEquals("SECURITY_VERIFICATION", service.getStatus().get("pauseReason"));
        verify(worker, times(1)).execute();
        verify(manager, never()).reopenBrowser();
        messages.clear();

        service.executeDelivery(messages::add);

        assertEquals(false, service.getStatus().get("paused"));
        assertEquals("", service.getStatus().get("pauseReason"));
        assertEquals("任务处理完成，共确认建立2次沟通（不代表招呼或简历送达）", service.getStatus().get("lastMessage"));
        assertEquals(1, messages.stream().filter(message -> "success".equals(message.getType())).count());
        assertTrue(messages.stream().noneMatch(message -> "error".equals(message.getType())));
        verify(worker, times(2)).execute();
        verify(manager, times(2)).ensureReady();
        verify(manager, times(2)).pauseLiepinMonitoring();
        verify(manager, times(2)).resumeLiepinMonitoring();
        verify(manager, never()).reopenBrowser();
        assertRunFinished();
    }

    @Test
    void prepareStartPublishesPendingStateBeforeAnyAsynchronousExecution() {
        LiepinJobService.StartSnapshot admission = service.prepareStart();

        assertTrue(service.isRunning(), "busy checks must include a queued start");
        assertEquals(false, service.getStatus().get("isRunning"));
        assertEquals(true, service.getStatus().get("starting"));
        assertEquals(false, service.getStatus().get("paused"));
        assertEquals("", service.getStatus().get("pauseReason"));
        assertEquals("任务启动中", service.getStatus().get("lastMessage"));
        assertThrows(BrowserModeSwitchBusyException.class, service::prepareStart);
        verify(manager, never()).ensureReady();
        verify(worker, never()).execute();

        service.cancelPreparedStart(admission);
        assertRunFinished();
        assertEquals("尚未运行", service.getStatus().get("lastMessage"));
    }

    @Test
    void rejectedSchedulingRestoresThePreviousPauseAndOnlyItsOwnPendingAdmission() {
        LiepinTaskPausedException pause = LiepinTaskPausedException.communicationUnconfirmed(101L, "确认沟通", null);
        when(worker.execute()).thenThrow(pause);
        service.executeDelivery(messages::add);

        LiepinJobService.StartSnapshot first = service.prepareStart();
        service.cancelPreparedStart(first);
        assertEquals(true, service.getStatus().get("paused"));
        assertEquals("COMMUNICATION_UNCONFIRMED", service.getStatus().get("pauseReason"));
        assertEquals(pause.getMessage(), service.getStatus().get("lastMessage"));

        LiepinJobService.StartSnapshot second = service.prepareStart();
        service.cancelPreparedStart(first);
        assertEquals(true, service.getStatus().get("starting"));
        assertEquals(false, service.getStatus().get("paused"));
        service.executePreparedDelivery(first, messages::add);
        assertEquals(true, service.getStatus().get("starting"));
        verify(worker, times(1)).execute();

        service.cancelPreparedStart(second);
        assertEquals(true, service.getStatus().get("paused"));
        assertEquals(pause.getMessage(), service.getStatus().get("lastMessage"));
        assertRunFinished();
    }

    @Test
    void stoppingAQueuedStartCannotBeClearedWhenTheWorkerEnters() {
        LiepinJobService.StartSnapshot admission = service.prepareStart();
        service.stopDelivery();
        assertTrue(service.shouldStop());

        service.executePreparedDelivery(admission, messages::add);

        verify(manager, never()).ensureReady();
        verify(worker, never()).execute();
        assertEquals("任务已停止", service.getStatus().get("lastMessage"));
        assertTrue(messages.stream().anyMatch(message -> "warning".equals(message.getType())));
        assertRunFinished();
    }

    @Test
    void enteringTheWorkerAtomicallyReplacesStartingWithRunning() {
        LiepinJobService.StartSnapshot admission = service.prepareStart();
        doAnswer(invocation -> {
            assertEquals(false, service.getStatus().get("starting"));
            assertEquals(true, service.getStatus().get("isRunning"));
            assertTrue(service.isRunning());
            assertThrows(BrowserModeSwitchBusyException.class, service::prepareStart);
            return null;
        }).when(manager).ensureReady();

        service.executePreparedDelivery(admission, messages::add);

        assertRunFinished();
        service.executePreparedDelivery(admission, messages::add);
        verify(worker, times(1)).execute();
    }

    @Test
    void missingPageEndsPendingStartAndPublishesTheFailureInsteadOfStalePause() {
        when(manager.getLiepinPage()).thenReturn(null);

        service.executePreparedDelivery(service.prepareStart(), messages::add);

        assertEquals("猎聘页面未初始化", service.getStatus().get("lastMessage"));
        assertEquals(false, service.getStatus().get("paused"));
        verify(worker, never()).execute();
        verify(manager, never()).pauseLiepinMonitoring();
        verify(manager, never()).resumeLiepinMonitoring();
        assertRunFinished();
    }

    @Test
    void loginFailureAfterAdmissionEndsPendingStartAndPublishesTheFailure() {
        LiepinJobService.StartSnapshot admission = service.prepareStart();
        when(manager.isLoggedIn("liepin")).thenReturn(false);

        service.executePreparedDelivery(admission, messages::add);

        assertEquals("请先登录猎聘", service.getStatus().get("lastMessage"));
        verify(worker, never()).execute();
        assertRunFinished();
    }

    @Test
    void browserInitializationFailureAlsoClearsBothBusyFlags() {
        doThrow(new IllegalStateException("mock browser unavailable")).when(manager).ensureReady();

        service.executePreparedDelivery(service.prepareStart(), messages::add);

        assertEquals("投递失败: mock browser unavailable", service.getStatus().get("lastMessage"));
        verify(worker, never()).execute();
        assertRunFinished();
    }

    @Test
    void stopDuringInitializationIsHonoredBeforeDeliveryCanStart() {
        doAnswer(invocation -> {
            service.stopDelivery();
            return null;
        }).when(manager).ensureReady();

        service.executePreparedDelivery(service.prepareStart(), messages::add);

        verify(worker, never()).execute();
        assertEquals("任务已停止", service.getStatus().get("lastMessage"));
        assertRunFinished();
    }

    @Test
    void cleanupFailureDoesNotLeaveRunningOrStartingFlagsSet() {
        doThrow(new IllegalStateException("mock monitor failure")).when(manager).resumeLiepinMonitoring();

        service.executePreparedDelivery(service.prepareStart(), messages::add);

        assertRunFinished();
        verify(worker, times(1)).execute();
    }

    private void assertRunFinished() {
        assertFalse(service.isRunning());
        assertFalse(service.shouldStop());
        assertEquals(false, service.getStatus().get("isRunning"));
        assertEquals(false, service.getStatus().get("starting"));
    }

    private void verifySingleRunMonitoringLifecycle() {
        verify(manager, times(1)).ensureReady();
        verify(manager, times(1)).callOnPlaywright(any());
        verify(worker, times(1)).execute();
        verify(manager, never()).reopenBrowser();
        InOrder lifecycle = inOrder(manager, worker);
        lifecycle.verify(manager).pauseLiepinMonitoring();
        lifecycle.verify(worker).execute();
        lifecycle.verify(manager).resumeLiepinMonitoring();
    }

    private static LiepinTaskPausedException pauseFor(LiepinTaskPausedException.Reason reason) {
        return switch (reason) {
            case SECURITY_VERIFICATION -> LiepinTaskPausedException.verificationRequired();
            case PAGE_UNAVAILABLE -> LiepinTaskPausedException.pageUnavailable();
            case READ_FAILED -> LiepinTaskPausedException.readFailed();
            case COMMUNICATION_UNCONFIRMED -> LiepinTaskPausedException.communicationUnconfirmed(101L, "确认沟通", null);
            case RECORD_STATE_UNAVAILABLE -> LiepinTaskPausedException.recordStateUnavailable(101L, "确认记录", null);
        };
    }
}
