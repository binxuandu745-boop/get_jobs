package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.LiepinJobService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaywrightPlatformModeControllerTest {
    private PlaywrightManager manager;
    private BossJobService boss;
    private LiepinJobService liepin;
    private PlaywrightController controller;

    @BeforeEach
    void setUp() {
        manager = mock(PlaywrightManager.class);
        boss = mock(BossJobService.class);
        liepin = mock(LiepinJobService.class);
        controller = new PlaywrightController(manager, boss);
        ReflectionTestUtils.setField(controller, "liepinJobService", liepin);
        when(manager.isPlatformEnabled("liepin")).thenReturn(true);
        when(manager.getBrowserPlatformMode()).thenReturn("liepin");
        when(manager.getLiepinLoginState()).thenReturn("UNKNOWN");
        when(manager.getLiepinLoginMessage()).thenReturn("尚未确认登录");
    }

    @Test
    void statusReportsLiepinOnlyWithoutRequiringBossPage() {
        when(manager.isInitialized()).thenReturn(true);
        when(manager.getLiepinPage()).thenReturn(mock(Page.class));
        var response = controller.getStatus();
        assertEquals(200, response.getStatusCode().value());
        var state = response.getBody();
        assertNotNull(state);
        assertEquals("liepin", state.get("platformMode"));
        assertEquals(false, state.get("hasBossPage"));
        assertEquals(true, state.get("hasLiepinPage"));
        assertEquals("UNKNOWN", state.get("liepinLoginState"));
        assertFalse((Boolean) state.get("liepinLoggedIn"));
        assertNoLaunch();
    }

    @Test
    void genericLoginRecheckRefreshesOnlyEnabledPlatform() {
        controller.checkLogin();
        verify(manager).refreshLiepinLoginStatus();
        verify(manager, never()).refreshBossLoginStatus();
        assertNoLaunch();
    }

    @Test
    void loginRecheckIsSkippedDuringLiepinTask() {
        when(liepin.isRunning()).thenReturn(true);
        controller.checkLogin();
        verify(manager, never()).refreshLiepinLoginStatus();
        verify(manager, never()).refreshBossLoginStatus();
        assertNoLaunch();
    }

    @Test
    void loginRecheckIsSkippedWhenPlaywrightIsBusy() {
        when(manager.isPlaywrightBusy()).thenReturn(true);
        controller.checkLogin();
        verify(manager, never()).refreshLiepinLoginStatus();
        verify(manager, never()).refreshBossLoginStatus();
        assertNoLaunch();
    }

    @Test
    void runningLiepinTaskPreventsReopeningBrowser() {
        when(liepin.isRunning()).thenReturn(true);
        assertEquals(409, controller.reopen().getStatusCode().value());
        assertNoLaunch();
    }

    @Test
    void busyPlaywrightPreventsReopeningBrowser() {
        when(manager.isPlaywrightBusy()).thenReturn(true);
        assertEquals(409, controller.reopen().getStatusCode().value());
        assertNoLaunch();
    }

    @Test
    void bossDiagnosticsCannotOpenBossPagesInLiepinMode() {
        assertEquals(409, controller.fingerprint().getStatusCode().value());
        assertEquals(409, controller.diagnose("https://www.zhipin.com").getStatusCode().value());
        assertEquals(409, controller.testNavigate().getStatusCode().value());
        verify(manager, never()).callOnPlaywright(any());
        verify(manager, never()).getBossPage();
        verify(manager, never()).getContext();
        assertNoLaunch();
    }

    @Test
    void liepinManualRecheckReturnsTriStateWithoutStartingDelivery() {
        var liepinController = liepinController();
        var response = liepinController.refreshLoginStatus();
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("UNKNOWN", response.getBody().get("state"));
        assertEquals("尚未确认登录", response.getBody().get("message"));
        verify(manager).refreshLiepinLoginStatus();
        assertNoLaunch();
    }

    @Test
    void liepinManualRecheckDoesNotProbeWhenTaskRuns() {
        when(liepin.isRunning()).thenReturn(true);
        assertEquals(200, liepinController().refreshLoginStatus().getStatusCode().value());
        verify(manager, never()).refreshLiepinLoginStatus();
        assertNoLaunch();
    }

    @Test
    void liepinManualRecheckDoesNotProbeWhenPlaywrightIsBusy() {
        when(manager.isPlaywrightBusy()).thenReturn(true);
        assertEquals(200, liepinController().refreshLoginStatus().getStatusCode().value());
        verify(manager, never()).refreshLiepinLoginStatus();
        assertNoLaunch();
    }

    private LiepinController liepinController() {
        var result = new LiepinController();
        ReflectionTestUtils.setField(result, "playwrightManager", manager);
        ReflectionTestUtils.setField(result, "liepinJobService", liepin);
        return result;
    }

    private void assertNoLaunch() {
        verify(manager, never()).init();
        verify(manager, never()).ensureReady();
        verify(manager, never()).reopenBrowser();
        verify(boss, never()).executeDelivery(any());
        verify(liepin, never()).executeDelivery(any());
    }
}
