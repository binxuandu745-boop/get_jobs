package com.getjobs.application.controller;

import com.getjobs.worker.manager.BrowserModeSwitchBusyException;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.Job51JobService;
import com.getjobs.worker.service.LiepinJobService;
import com.getjobs.worker.service.ZhilianJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Controller-only tests: no application context, browser, tasks, or configuration store is opened. */
class BrowserModeSwitchControllerTest {
    private PlaywrightManager manager;
    private BossJobService boss;
    private LiepinJobService liepin;
    private Job51JobService job51;
    private ZhilianJobService zhilian;
    private PlaywrightController controller;

    @BeforeEach
    void setUp() {
        manager = mock(PlaywrightManager.class);
        boss = mock(BossJobService.class);
        liepin = mock(LiepinJobService.class);
        job51 = mock(Job51JobService.class);
        zhilian = mock(ZhilianJobService.class);
        controller = new PlaywrightController(manager, boss);
        ReflectionTestUtils.setField(controller, "liepinJobService", liepin);
        ReflectionTestUtils.setField(controller, "job51JobService", job51);
        ReflectionTestUtils.setField(controller, "zhilianJobService", zhilian);
        when(manager.getBrowserPlatformMode()).thenReturn("liepin");
        when(manager.getBossLoginState()).thenReturn("DISABLED");
        when(manager.getBossLoginMessage()).thenReturn("当前模式未启用BOSS");
        when(manager.getLiepinLoginState()).thenReturn("UNKNOWN");
        when(manager.getLiepinLoginMessage()).thenReturn("尚未确认登录");
        when(manager.canSwitchPlatformMode(any(BooleanSupplier.class))).thenAnswer(invocation ->
                !invocation.<BooleanSupplier>getArgument(0).getAsBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"boss", "liepin", "51job", "zhilian"})
    void eachPlatformSwitchReturnsChangedAndFlatStatusWithoutStartingDelivery(String mode) {
        when(manager.switchPlatformMode(eq(mode), any(BooleanSupplier.class))).thenReturn(true);
        when(manager.getBrowserPlatformMode()).thenReturn(mode);
        when(manager.isInitialized()).thenReturn(true);

        Map<String, Object> state = body(controller.switchPlatformMode(
                new PlaywrightController.PlatformModeRequest(mode)), 200);

        assertEquals(true, state.get("success"));
        assertEquals(true, state.get("changed"));
        assertEquals(true, state.get("initialized"));
        assertEquals(mode, state.get("platformMode"));
        assertEquals(false, state.get("switchingMode"));
        assertEquals(true, state.get("canSwitchMode"));
        assertEquals("UNKNOWN", state.get("liepinLoginState"));
        assertNotNull(state.get("message"));
        verify(manager).switchPlatformMode(eq(mode), any(BooleanSupplier.class));
        assertNoLaunchOrTaskChanges();
    }

    @Test
    void selectingCurrentModeReturnsSuccessfulNoOp() {
        when(manager.switchPlatformMode(eq("liepin"), any(BooleanSupplier.class))).thenReturn(false);
        when(manager.isInitialized()).thenReturn(true);

        Map<String, Object> state = body(controller.switchPlatformMode(
                new PlaywrightController.PlatformModeRequest("liepin")), 200);

        assertEquals(true, state.get("success"));
        assertEquals(false, state.get("changed"));
        assertEquals("liepin", state.get("platformMode"));
        assertEquals(true, state.get("initialized"));
        assertEquals(false, state.get("switchingMode"));
        assertEquals(true, state.get("canSwitchMode"));
        verify(manager).switchPlatformMode(eq("liepin"), any(BooleanSupplier.class));
        assertNoLaunchOrTaskChanges();
    }

    @Test
    void nullRequestIsRejectedWithoutCallingManagerSwitch() {
        Map<String, Object> error = body(controller.switchPlatformMode(null), 400);

        assertEquals(false, error.get("success"));
        assertNotNull(error.get("message"));
        verify(manager, never()).switchPlatformMode(any(), any());
        assertNoLaunchOrTaskChanges();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", "other", "lagou", "job51", "boss,liepin",
            "API_KEY=private-key; jdbc:sqlite:private-user.db"})
    void invalidModesReturnBadRequestWithoutLaunchingBrowser(String mode) {
        when(manager.switchPlatformMode(nullable(String.class), any(BooleanSupplier.class)))
                .thenThrow(new IllegalArgumentException("请选择 boss、liepin、51job 或 zhilian 浏览器模式"));

        Map<String, Object> error = body(controller.switchPlatformMode(
                new PlaywrightController.PlatformModeRequest(mode)), 400);

        assertEquals(false, error.get("success"));
        assertEquals(mode == null || mode.isBlank() ? "请选择浏览器模式"
                : "模式只能选择BOSS、猎聘、51job或智联招聘", error.get("message"));
        assertFalse(error.get("message").toString().contains("private-key"));
        assertFalse(error.get("message").toString().contains("private-user.db"));
        assertNoLaunchOrTaskChanges();
    }

    @ParameterizedTest
    @ValueSource(strings = {"boss", "liepin", "51job", "zhilian"})
    void everyRunningPlatformIsIncludedInTheManagerBusyGuard(String platform) {
        markRunning(platform);
        doAnswer(invocation -> {
            BooleanSupplier runningTasks = invocation.getArgument(1);
            assertTrue(runningTasks.getAsBoolean());
            throw new BrowserModeSwitchBusyException();
        }).when(manager).switchPlatformMode(eq("boss"), any(BooleanSupplier.class));

        Map<String, Object> error = body(controller.switchPlatformMode(
                new PlaywrightController.PlatformModeRequest("boss")), 409);

        assertEquals(false, error.get("success"));
        assertNotNull(error.get("message"));
        verify(manager).switchPlatformMode(eq("boss"), any(BooleanSupplier.class));
        verifyRunningPlatformChecked(platform);
        assertNoLaunchOrTaskChanges();
    }

    @Test
    void managerBusyFailureReturnsConflictRatherThanServerError() {
        when(manager.switchPlatformMode(eq("boss"), any(BooleanSupplier.class)))
                .thenThrow(new BrowserModeSwitchBusyException());

        Map<String, Object> error = body(controller.switchPlatformMode(
                new PlaywrightController.PlatformModeRequest("boss")), 409);

        assertEquals(false, error.get("success"));
        assertNotNull(error.get("message"));
        assertNoLaunchOrTaskChanges();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "模式选择保存失败，原浏览器未切换；请检查本地配置存储",
            "模式选择已保存，但浏览器初始化失败；请检查后重试。未启动投递",
            "API_KEY=private-key; jdbc:sqlite:private-user.db"
    })
    void storageAndInitializationFailuresReturnServerErrorWithoutAutomaticRetry(String message) {
        when(manager.switchPlatformMode(eq("boss"), any(BooleanSupplier.class)))
                .thenThrow(new IllegalStateException(message, new RuntimeException("private-key")));

        Map<String, Object> error = body(controller.switchPlatformMode(
                new PlaywrightController.PlatformModeRequest("boss")), 500);

        assertEquals(false, error.get("success"));
        assertEquals("切换未能完整完成，请刷新状态核对当前模式；不要重复提交", error.get("message"));
        assertFalse(error.toString().contains("private-key"));
        assertFalse(error.toString().contains("private-user.db"));
        verify(manager).switchPlatformMode(eq("boss"), any(BooleanSupplier.class));
        assertNoLaunchOrTaskChanges();
    }

    @Test
    void statusReturnsSwitchAvailabilityWithoutSavingSwitchingOrStartingTasks() {
        when(manager.isInitialized()).thenReturn(true);

        Map<String, Object> state = body(controller.getStatus(), 200);

        assertEquals(true, state.get("success"));
        assertEquals("liepin", state.get("platformMode"));
        assertEquals(true, state.get("initialized"));
        assertEquals(false, state.get("switchingMode"));
        assertEquals(true, state.get("canSwitchMode"));
        assertEquals(false, state.get("hasBossPage"));
        assertEquals(false, state.get("hasLiepinPage"));
        assertEquals(false, state.get("has51jobPage"));
        assertEquals(false, state.get("hasZhilianPage"));
        assertEquals("UNKNOWN", state.get("liepinLoginState"));
        assertFalse(state.containsKey("status"));
        verify(manager).canSwitchPlatformMode(any(BooleanSupplier.class));
        verify(manager, never()).switchPlatformMode(any(), any());
        assertNoLaunchOrTaskChanges();
    }

    @Test
    void statusReadFailureReturnsGenericErrorWithoutStartingOrSwitching() {
        when(manager.getBrowserPlatformMode())
                .thenThrow(new IllegalStateException("API_KEY=private-key; jdbc:sqlite:private-user.db"));

        Map<String, Object> error = body(controller.getStatus(), 500);

        assertEquals(false, error.get("success"));
        assertEquals(false, error.get("canSwitchMode"));
        assertEquals("无法读取浏览器模式，请检查本地配置存储", error.get("message"));
        assertFalse(error.toString().contains("private-key"));
        assertFalse(error.toString().contains("private-user.db"));
        verify(manager, never()).switchPlatformMode(any(), any());
        assertNoLaunchOrTaskChanges();
    }

    @ParameterizedTest
    @ValueSource(strings = {"boss", "liepin", "51job", "zhilian"})
    void statusDisablesSwitchingWhileAnyPlatformTaskRuns(String platform) {
        markRunning(platform);

        Map<String, Object> state = body(controller.getStatus(), 200);

        assertEquals(false, state.get("canSwitchMode"));
        verifyRunningPlatformChecked(platform);
        verify(manager, never()).switchPlatformMode(any(), any());
        assertNoLaunchOrTaskChanges();
    }

    @Test
    void statusReportsSwitchInProgress() {
        when(manager.isSwitchingMode()).thenReturn(true);
        when(manager.canSwitchPlatformMode(any(BooleanSupplier.class))).thenReturn(false);

        Map<String, Object> state = body(controller.getStatus(), 200);

        assertEquals(true, state.get("switchingMode"));
        assertEquals(false, state.get("canSwitchMode"));
        verify(manager, never()).switchPlatformMode(any(), any());
        assertNoLaunchOrTaskChanges();
    }

    private Map<String, Object> body(ResponseEntity<Map<String, Object>> response, int expectedStatus) {
        assertEquals(expectedStatus, response.getStatusCode().value());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private void markRunning(String platform) {
        switch (platform) {
            case "boss" -> when(boss.isRunning()).thenReturn(true);
            case "liepin" -> when(liepin.isRunning()).thenReturn(true);
            case "51job" -> when(job51.isRunning()).thenReturn(true);
            case "zhilian" -> when(zhilian.isRunning()).thenReturn(true);
            default -> throw new IllegalArgumentException("Unsupported test platform");
        }
    }

    private void verifyRunningPlatformChecked(String platform) {
        switch (platform) {
            case "boss" -> verify(boss).isRunning();
            case "liepin" -> verify(liepin).isRunning();
            case "51job" -> verify(job51).isRunning();
            case "zhilian" -> verify(zhilian).isRunning();
            default -> throw new IllegalArgumentException("Unsupported test platform");
        }
    }

    private void assertNoLaunchOrTaskChanges() {
        verify(manager, never()).init();
        verify(manager, never()).ensureReady();
        verify(manager, never()).reopenBrowser();
        verify(manager, never()).startPlatformTask(anyString(), any(Runnable.class));
        verify(boss, never()).executeDelivery(any());
        verify(liepin, never()).executeDelivery(any());
        verify(job51, never()).executeDelivery(any());
        verify(zhilian, never()).executeDelivery(any());
        verify(boss, never()).stopDelivery();
        verify(liepin, never()).stopDelivery();
        verify(job51, never()).stopDelivery();
        verify(zhilian, never()).stopDelivery();
    }
}
