package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossTaskPausedException;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BossJobServicePauseTest {
    private PlaywrightManager manager;
    private Boss boss;
    private BossPauseNotifier notifier;
    private BossJobService service;
    private List<JobProgressMessage> messages;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(PlaywrightManager.class);
        ConfigService configService = mock(ConfigService.class);
        ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        boss = mock(Boss.class);
        notifier = mock(BossPauseNotifier.class);
        service = new BossJobService(manager, configService, provider, notifier);
        messages = new ArrayList<>();
        when(manager.getBossPage()).thenReturn(mock(Page.class));
        when(manager.refreshBossLoginStatus()).thenReturn(true);
        BossConfig config = new BossConfig();
        config.setDebugger(false);
        when(configService.getBossConfig()).thenReturn(config);
        when(provider.getObject()).thenReturn(boss);
        when(manager.callOnPlaywright(any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(0)).call());
    }

    @Test
    void dataFailureLeavesPausedStateAndErrorInsteadOfSuccess() {
        when(boss.execute()).thenThrow(BossTaskPausedException.detailsUnavailable("未取得岗位详情响应"));
        service.executeDelivery(messages::add);
        assertEquals(true, service.getStatus().get("paused"));
        assertEquals("JOB_DETAILS_UNAVAILABLE", service.getStatus().get("pauseReason"));
        assertFalse(service.isRunning());
        assertFalse(service.shouldStop());
        assertTrue(messages.stream().anyMatch(message -> "error".equals(message.getType())
                && message.getMessage().startsWith("已暂停")));
        assertTrue(messages.stream().noneMatch(message -> "success".equals(message.getType())));
        verify(boss, times(1)).execute();
        String pauseMessage = service.getStatus().get("lastMessage").toString();
        verify(notifier).notifyPaused(eq(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE),
                eq(pauseMessage));
        verify(manager).resumeBossMonitoring();
    }

    @Test
    void verificationHasADistinctReasonAndDoesNotRestartAutomatically() {
        when(boss.execute()).thenThrow(BossTaskPausedException.verificationRequired());
        service.executeDelivery(messages::add);
        assertEquals("SECURITY_VERIFICATION", service.getStatus().get("pauseReason"));
        assertTrue(service.getStatus().get("lastMessage").toString().contains("手动完成安全验证"));
        assertTrue(messages.stream().noneMatch(message -> "success".equals(message.getType())));
        verify(boss, times(1)).execute();
        String pauseMessage = service.getStatus().get("lastMessage").toString();
        verify(notifier).notifyPaused(eq(BossTaskPausedException.Reason.SECURITY_VERIFICATION),
                eq(pauseMessage));
        verify(manager, never()).reopenBrowser();
    }

    @Test
    void onlyAnExplicitNewRunClearsThePauseState() {
        when(boss.execute()).thenThrow(BossTaskPausedException.detailsUnavailable("缺少岗位数据"))
                .thenReturn(0);
        service.executeDelivery(messages::add);
        assertEquals(true, service.getStatus().get("paused"));
        messages.clear();
        service.executeDelivery(messages::add);
        assertEquals(false, service.getStatus().get("paused"));
        assertEquals("", service.getStatus().get("pauseReason"));
        assertTrue(messages.stream().anyMatch(message -> "success".equals(message.getType())));
        verify(boss, times(2)).execute();
        verify(notifier, times(1)).notifyPaused(any(), any());
    }

    @Test
    void notificationFailureCannotUndoPauseOrSuppressTheErrorEvent() {
        when(boss.execute()).thenThrow(BossTaskPausedException.detailsUnavailable("缺少岗位数据"));
        doThrow(new IllegalStateException("Desktop unavailable")).when(notifier).notifyPaused(any(), any());

        assertDoesNotThrow(() -> service.executeDelivery(messages::add));

        assertEquals(true, service.getStatus().get("paused"));
        assertEquals("JOB_DETAILS_UNAVAILABLE", service.getStatus().get("pauseReason"));
        assertFalse(service.isRunning());
        assertFalse(service.shouldStop());
        assertTrue(messages.stream().anyMatch(message -> "error".equals(message.getType())
                && message.getMessage().startsWith("已暂停")));
        assertTrue(messages.stream().noneMatch(message -> "success".equals(message.getType())));
        verify(manager).resumeBossMonitoring();
    }

    @Test
    void pauseIsNotifiedBeforeADisconnectedProgressCallbackCanFail() {
        when(boss.execute()).thenThrow(BossTaskPausedException.verificationRequired());

        assertThrows(IllegalStateException.class, () -> service.executeDelivery(message -> {
            if ("error".equals(message.getType())) throw new IllegalStateException("SSE disconnected");
            messages.add(message);
        }));

        verify(notifier).notifyPaused(eq(BossTaskPausedException.Reason.SECURITY_VERIFICATION), any());
        assertEquals(true, service.getStatus().get("paused"));
        assertFalse(service.isRunning());
        verify(manager).resumeBossMonitoring();
    }

    @Test
    void ordinaryTaskFailureDoesNotRaiseAProtectivePauseAlert() {
        when(boss.execute()).thenThrow(new IllegalStateException("ordinary execution failure"));

        service.executeDelivery(messages::add);

        verifyNoInteractions(notifier);
        assertEquals(false, service.getStatus().get("paused"));
        assertFalse(service.isRunning());
    }
}
