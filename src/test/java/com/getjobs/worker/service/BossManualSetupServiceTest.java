package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossManualSearchFilters;
import com.getjobs.worker.boss.BossTaskPausedException;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No network or real browser: exercises the manual setup boundary and cross-round settings. */
class BossManualSetupServiceTest {
    private static final String BASE = "https://www.zhipin.com/web/geek/jobs?city=101010100"
            + "&position=100101&jobType=1902&experience=102&salary=405&query=Java";
    private static final String CHOSEN = "https://www.zhipin.com/web/geek/jobs?city=101010100"
            + "&position=100102&jobType=1901&salary=406&query=Java";
    private PlaywrightManager manager;
    private ConfigService configs;
    private ObjectProvider<Boss> provider;
    private BossPauseNotifier notifier;
    private BossJobService service;
    private Page page;
    private List<BossConfig> loaded;
    private List<JobProgressMessage> messages;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(PlaywrightManager.class);
        configs = mock(ConfigService.class);
        provider = mock(ObjectProvider.class);
        notifier = mock(BossPauseNotifier.class);
        service = spy(new BossJobService(manager, configs, provider, notifier));
        page = mock(Page.class);
        loaded = new ArrayList<>();
        messages = new ArrayList<>();
        ReflectionTestUtils.setField(service, "autoRoundsEnabled", true);
        ReflectionTestUtils.setField(service, "manualSetupEnabled", true);
        ReflectionTestUtils.setField(service, "manualSetupWaitMs", 10000);
        ReflectionTestUtils.setField(service, "randomCityEnabled", true);
        ReflectionTestUtils.setField(service, "randomCityPool", List.of("101010100", "101020100"));
        doReturn(0).when(service).randomCityIndex(anyInt());
        when(manager.getBossPage()).thenReturn(page);
        when(manager.refreshBossLoginStatus()).thenReturn(true);
        when(manager.callOnPlaywright(any())).thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
        when(configs.getBossConfig()).thenAnswer(call -> {
            BossConfig config = new BossConfig();
            config.setDebugger(false);
            config.setCityCode(List.of("101010100"));
            config.setKeywords(List.of("Java", "Backend"));
            config.setJobType("1902");
            config.setPosition("100101");
            config.setExperience(List.of("102"));
            config.setSalary(List.of("405"));
            loaded.add(config);
            return config;
        });
    }

    @Test
    void manuallyChosenFiltersCarryAcrossAllRoundsAndUnchangedCityStillRotates() {
        Boss first = mock(Boss.class), second = mock(Boss.class), third = mock(Boss.class);
        BossManualSearchFilters filters = BossManualSearchFilters.capture(BASE, CHOSEN);
        when(provider.getObject()).thenReturn(first, second, third);
        when(first.awaitManualSearchSetup(10000)).thenReturn(filters);
        when(first.execute()).thenReturn(50);
        when(second.execute()).thenReturn(50);
        when(third.execute()).thenReturn(50);

        service.executeDelivery(messages::add);

        verify(first).awaitManualSearchSetup(10000);
        verify(second, never()).awaitManualSearchSetup(anyInt());
        verify(third, never()).awaitManualSearchSetup(anyInt());
        verify(first).setManualSearchGuard(same(filters));
        for (BossConfig config : loaded) {
            assertEquals("1901", config.getJobType());
            assertEquals("100102", config.getPosition());
            assertEquals(List.of(), config.getExperience());
            assertEquals(List.of("406"), config.getSalary());
            assertEquals(List.of("Java", "Backend"), config.getKeywords());
        }
        assertEquals(List.of("101010100"), loaded.get(0).getCityCode());
        assertEquals(List.of("101020100"), loaded.get(1).getCityCode());
        assertEquals(List.of("101010100"), loaded.get(2).getCityCode());
        assertEquals(150, service.getStatus().get("deliveredCount"));
        verify(first).setSuccessfulDeliveryLimit(50);
        verify(second).setSuccessfulDeliveryLimit(50);
        verify(third).setSuccessfulDeliveryLimit(50);
    }

    @Test
    void explicitlyChangedCityAndKeywordWinInEveryRound() {
        Boss first = mock(Boss.class), second = mock(Boss.class), third = mock(Boss.class);
        String selected = CHOSEN.replace("city=101010100", "city=101280600")
                .replace("query=Java", "query=Backend");
        when(provider.getObject()).thenReturn(first, second, third);
        when(first.awaitManualSearchSetup(10000)).thenReturn(BossManualSearchFilters.capture(BASE, selected));
        when(first.execute()).thenReturn(50);
        when(second.execute()).thenReturn(50);
        when(third.execute()).thenReturn(50);

        service.executeDelivery(messages::add);

        assertEquals(3, loaded.size());
        loaded.forEach(config -> {
            assertEquals(List.of("101280600"), config.getCityCode());
            assertEquals(List.of("Backend"), config.getKeywords());
        });
        verify(service, times(1)).randomCityIndex(anyInt());
        assertEquals("101280600", service.getStatus().get("cityCode"));
    }

    @Test
    void stopDuringManualSetupDoesNotStartTheFirstOrNextRound() {
        Boss boss = mock(Boss.class);
        when(provider.getObject()).thenReturn(boss);
        when(boss.awaitManualSearchSetup(10000)).thenAnswer(call -> {
            service.stopDelivery();
            return BossManualSearchFilters.capture(BASE, CHOSEN);
        });

        service.executeDelivery(messages::add);

        verify(boss, never()).execute();
        verify(provider, times(1)).getObject();
        assertTrue(messages.stream().anyMatch(message -> "stopped".equals(message.getType())));
        assertFalse(service.isRunning());
    }

    @Test
    void cancellationFromWaitingStopsWithoutDelivery() {
        Boss boss = mock(Boss.class);
        when(provider.getObject()).thenReturn(boss);
        when(boss.awaitManualSearchSetup(10000)).thenThrow(new CancellationException("cancelled"));

        service.executeDelivery(messages::add);

        verify(boss, never()).execute();
        assertTrue(messages.stream().anyMatch(message -> "stopped".equals(message.getType())));
        assertFalse(service.isRunning());
    }

    @Test
    void verificationDuringSetupRemainsProtectivelyPausedAndDoesNotDeliver() {
        Boss boss = mock(Boss.class);
        when(provider.getObject()).thenReturn(boss);
        when(boss.awaitManualSearchSetup(10000)).thenThrow(BossTaskPausedException.verificationRequired());

        service.executeDelivery(messages::add);

        verify(boss, never()).execute();
        verify(provider, times(1)).getObject();
        assertEquals(true, service.getStatus().get("paused"));
        assertEquals("SECURITY_VERIFICATION", service.getStatus().get("pauseReason"));
        verify(notifier).notifyPaused(eq(BossTaskPausedException.Reason.SECURITY_VERIFICATION), anyString());
    }

    @Test
    void missingSnapshotStopsBeforeDelivery() {
        Boss boss = mock(Boss.class);
        when(provider.getObject()).thenReturn(boss);
        when(boss.awaitManualSearchSetup(10000)).thenReturn(null);

        service.executeDelivery(messages::add);

        verify(boss, never()).execute();
        assertEquals(true, service.getStatus().get("paused"));
        assertEquals("JOB_DETAILS_UNAVAILABLE", service.getStatus().get("pauseReason"));
    }

    @Test
    void disabledSetupKeepsLegacySingleRunBehavior() {
        Boss boss = mock(Boss.class);
        ReflectionTestUtils.setField(service, "manualSetupEnabled", false);
        ReflectionTestUtils.setField(service, "autoRoundsEnabled", false);
        when(provider.getObject()).thenReturn(boss);

        service.executeDelivery(messages::add);

        verify(boss, never()).awaitManualSearchSetup(anyInt());
        verify(boss).execute();
        assertEquals("1902", loaded.getFirst().getJobType());
    }

    @Test
    void loginProbeRebindingUsesAuthenticatedPageForBothSetupAndExecution() {
        Page initial = mock(Page.class), authenticated = mock(Page.class);
        Boss boss = mock(Boss.class);
        ReflectionTestUtils.setField(service, "autoRoundsEnabled", false);
        when(manager.getBossPage()).thenReturn(initial, authenticated);
        when(provider.getObject()).thenReturn(boss);
        when(boss.awaitManualSearchSetup(10000)).thenReturn(BossManualSearchFilters.capture(BASE, CHOSEN));

        service.executeDelivery(messages::add);

        var order = inOrder(boss);
        order.verify(boss).setPage(same(authenticated));
        order.verify(boss).awaitManualSearchSetup(10000);
        order.verify(boss).setManualSearchGuard(any());
        order.verify(boss).execute();
        verify(boss, never()).setPage(same(initial));
    }
}
