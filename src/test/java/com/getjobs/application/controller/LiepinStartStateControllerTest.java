package com.getjobs.application.controller;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.liepin.LiepinTaskPausedException;
import com.getjobs.worker.manager.BrowserModeSwitchBusyException;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.LiepinJobService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Offline API lifecycle tests: all browser/configuration access is mocked, no application starts. */
class LiepinStartStateControllerTest {
    private PlaywrightManager manager;
    private Liepin worker;
    private LiepinJobService service;
    private LiepinController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(PlaywrightManager.class);
        worker = mock(Liepin.class);
        ConfigService configService = mock(ConfigService.class);
        ObjectProvider<Liepin> provider = mock(ObjectProvider.class);
        service = new LiepinJobService(manager, configService, provider);
        controller = new LiepinController();
        ReflectionTestUtils.setField(controller, "playwrightManager", manager);
        ReflectionTestUtils.setField(controller, "liepinJobService", service);
        when(manager.isLoggedIn("liepin")).thenReturn(true);
        when(manager.getLiepinPage()).thenReturn(mock(Page.class));
        when(configService.getLiepinConfig()).thenReturn(new LiepinConfig());
        when(provider.getObject()).thenReturn(worker);
        when(manager.callOnPlaywright(any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(0)).call());
    }

    @Test
    void acceptedStartPublishesStartingBeforeSchedulingAndDoesNotLoseALaterPause() {
        AtomicReference<Runnable> task = new AtomicReference<>();
        when(manager.startPlatformTask(eq("liepin"), any(Runnable.class))).thenAnswer(invocation -> {
            assertEquals(true, service.getStatus().get("starting"));
            assertEquals(false, service.getStatus().get("isRunning"));
            assertTrue(service.isRunning());
            task.set(invocation.getArgument(1));
            return new CompletableFuture<Void>();
        });
        when(worker.execute()).thenThrow(LiepinTaskPausedException.communicationUnconfirmed(101L, "确认沟通", null));

        var response = controller.startLiepinJob();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(task.get());
        var queuedStatus = controller.getCurrentStatus().getBody();
        assertNotNull(queuedStatus);
        assertEquals(true, queuedStatus.get("success"));
        assertEquals(true, queuedStatus.get("starting"));
        assertEquals(false, queuedStatus.get("isRunning"));
        assertEquals(false, queuedStatus.get("paused"));
        verify(worker, never()).execute();

        task.get().run();

        var pausedStatus = controller.getCurrentStatus().getBody();
        assertNotNull(pausedStatus);
        assertEquals(false, pausedStatus.get("starting"));
        assertEquals(false, pausedStatus.get("isRunning"));
        assertEquals(true, pausedStatus.get("paused"));
        assertEquals("COMMUNICATION_UNCONFIRMED", pausedStatus.get("pauseReason"));
        assertFalse(service.isRunning());
    }

    @Test
    void modeAdmissionRejectionRestoresTheOldPauseInsteadOfLeavingStarting() {
        var previousPause = enterPausedState();
        when(manager.startPlatformTask(eq("liepin"), any(Runnable.class)))
                .thenThrow(new BrowserModeSwitchBusyException());

        var response = controller.startLiepinJob();

        assertEquals(409, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("busy", response.getBody().get("status"));
        assertEquals(true, service.getStatus().get("paused"));
        assertEquals("COMMUNICATION_UNCONFIRMED", service.getStatus().get("pauseReason"));
        assertEquals(previousPause.getMessage(), service.getStatus().get("lastMessage"));
        assertEquals(false, service.getStatus().get("starting"));
        assertFalse(service.isRunning());
        verify(worker, times(1)).execute();
    }

    @Test
    void schedulingFailureAlsoRestoresTheOldPause() {
        var previousPause = enterPausedState();
        when(manager.startPlatformTask(eq("liepin"), any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("mock scheduler stopped"));

        var response = controller.startLiepinJob();

        assertEquals(500, response.getStatusCode().value());
        assertEquals(true, service.getStatus().get("paused"));
        assertEquals(previousPause.getMessage(), service.getStatus().get("lastMessage"));
        assertEquals(false, service.getStatus().get("starting"));
        assertFalse(service.isRunning());
    }

    @Test
    void aSecondStartCannotCancelOrRestoreOverTheFirstPendingRequest() {
        AtomicReference<Runnable> firstTask = new AtomicReference<>();
        when(manager.startPlatformTask(eq("liepin"), any(Runnable.class))).thenAnswer(invocation -> {
            firstTask.set(invocation.getArgument(1));
            return new CompletableFuture<Void>();
        });
        assertEquals(200, controller.startLiepinJob().getStatusCode().value());

        assertEquals(400, controller.startLiepinJob().getStatusCode().value());

        assertEquals(true, service.getStatus().get("starting"));
        assertEquals("任务启动中", service.getStatus().get("lastMessage"));
        verify(manager, times(1)).startPlatformTask(eq("liepin"), any(Runnable.class));
        firstTask.get().run();
        assertFalse(service.isRunning());
        verify(worker, times(1)).execute();
    }

    @Test
    void queuedStartCanBeStoppedBeforeItTouchesAnyBrowserOrWorker() {
        AtomicReference<Runnable> task = new AtomicReference<>();
        when(manager.startPlatformTask(eq("liepin"), any(Runnable.class))).thenAnswer(invocation -> {
            task.set(invocation.getArgument(1));
            return new CompletableFuture<Void>();
        });
        assertEquals(200, controller.startLiepinJob().getStatusCode().value());

        assertEquals(200, controller.stopLiepinJob().getStatusCode().value());
        assertTrue(service.shouldStop());
        task.get().run();

        verify(manager, never()).ensureReady();
        verify(worker, never()).execute();
        assertEquals("任务已停止", service.getStatus().get("lastMessage"));
        assertEquals(false, service.getStatus().get("starting"));
        assertFalse(service.isRunning());
    }

    @Test
    void workerMayPauseBeforeStartReturnsWithoutTheControllerOverwritingIt() {
        when(worker.execute()).thenThrow(LiepinTaskPausedException.verificationRequired());
        when(manager.startPlatformTask(eq("liepin"), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return CompletableFuture.completedFuture(null);
        });

        assertEquals(200, controller.startLiepinJob().getStatusCode().value());

        assertEquals(true, service.getStatus().get("paused"));
        assertEquals("SECURITY_VERIFICATION", service.getStatus().get("pauseReason"));
        assertEquals(false, service.getStatus().get("starting"));
        assertFalse(service.isRunning());
    }

    @Test
    void loginRejectionDoesNotClearAnExistingPauseOrQueueWork() {
        var previousPause = enterPausedState();
        when(manager.isLoggedIn("liepin")).thenReturn(false);

        assertEquals(400, controller.startLiepinJob().getStatusCode().value());

        assertEquals(true, service.getStatus().get("paused"));
        assertEquals(previousPause.getMessage(), service.getStatus().get("lastMessage"));
        assertFalse(service.isRunning());
        verify(manager, never()).startPlatformTask(any(), any());
    }

    private LiepinTaskPausedException enterPausedState() {
        var pause = LiepinTaskPausedException.communicationUnconfirmed(101L, "确认沟通", null);
        when(worker.execute()).thenThrow(pause);
        service.executeDelivery(message -> { });
        assertTrue((Boolean) service.getStatus().get("paused"));
        return pause;
    }
}
