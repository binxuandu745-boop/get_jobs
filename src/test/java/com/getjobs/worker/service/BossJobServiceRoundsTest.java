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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Session lifecycle only: every worker, browser and provider call is mocked. */
class BossJobServiceRoundsTest {
    private PlaywrightManager manager;
    private ConfigService configService;
    private ObjectProvider<Boss> provider;
    private BossPauseNotifier notifier;
    private BossJobService service;
    private Page page;
    private final List<JobProgressMessage> messages = new ArrayList<>();
    private final List<Round> rounds = new ArrayList<>();
    private final List<BossConfig> loadedConfigs = new ArrayList<>();
    private final AtomicInteger provided = new AtomicInteger();
    private final AtomicBoolean preview = new AtomicBoolean();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(PlaywrightManager.class);
        configService = mock(ConfigService.class);
        provider = mock(ObjectProvider.class);
        notifier = mock(BossPauseNotifier.class);
        page = mock(Page.class);
        service = spy(new BossJobService(manager, configService, provider, notifier));
        ReflectionTestUtils.setField(service, "autoRoundsEnabled", true);
        ReflectionTestUtils.setField(service, "roundSize", 50);
        ReflectionTestUtils.setField(service, "totalTarget", 150);
        ReflectionTestUtils.setField(service, "maxRounds", 30);
        ReflectionTestUtils.setField(service, "maxEmptyRounds", 3);
        when(manager.getBossPage()).thenReturn(page);
        when(manager.refreshBossLoginStatus()).thenReturn(true);
        when(manager.callOnPlaywright(any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(0)).call());
        when(configService.getBossConfig()).thenAnswer(ignored -> {
            BossConfig config = new BossConfig();
            config.setDebugger(preview.get());
            config.setJobType("1902");
            config.setCityCode(List.of("101010100"));
            config.setKeywords(List.of("Java后端实习"));
            loadedConfigs.add(config);
            return config;
        });
        when(provider.getObject()).thenAnswer(ignored -> {
            int index = provided.getAndIncrement();
            assertTrue(index < rounds.size(), "No extra round may start after the terminal boundary");
            return rounds.get(index).boss;
        });
    }

    @Test
    void threeFullRoundsReach150WithOneTerminalSuccessAndNoConcurrentRestart() {
        Round first = round(50), second = round(50), third = round(50);

        service.executeDelivery(messages::add);

        assertEquals(List.of(50, 50, 50), limits(first, second, third));
        assertEquals(3, provided.get());
        assertEquals(150, statusInt("deliveredCount"));
        assertEquals(3, statusInt("roundNumber"));
        assertEquals(50, statusInt("roundDelivered"));
        assertEquals(150, statusInt("totalTarget"));
        assertEquals(1, messageCount("success"));
        assertFalse(service.isRunning());
        assertFalse(service.shouldStop());
        assertEquals(0, service.runningForMillis());
        verify(manager, times(3)).ensureReady();
        verify(manager, times(3)).refreshBossLoginStatus();
        verify(manager, times(3)).callOnPlaywright(any());
        verify(manager, times(1)).resumeBossMonitoring();
        verify(manager, never()).reopenBrowser();
        verifyNoInteractions(notifier);
    }

    @Test
    void exhaustedShortRoundsContinueAndTheFinalRoundUsesOnlyTheRemaining30() {
        Round first = round(20), second = round(50), third = round(50), fourth = round(30);

        service.executeDelivery(messages::add);

        assertEquals(List.of(50, 50, 50, 30), limits(first, second, third, fourth));
        assertEquals(150, statusInt("deliveredCount"));
        assertEquals(30, statusInt("roundDelivered"));
        assertEquals(30, statusInt("roundLimit"));
        assertEquals(4, statusInt("roundNumber"));
        assertEquals(1, messageCount("success"));
        verify(configService, times(4)).getBossConfig();
    }

    @Test
    void liveSuccessfulCountKeepsPriorRoundsAndDoesNotCountScannedPositions() {
        round(50);
        Round second = round(50);
        round(50);
        second.afterEachCount = count -> {
            assertEquals(2, statusInt("roundNumber"));
            assertEquals(count, statusInt("roundDelivered"));
            assertEquals(50 + count, statusInt("deliveredCount"));
            assertTrue(service.isRunning());
            assertEquals(0, messageCount("success"));
        };

        service.executeDelivery(messages::add);

        assertEquals(150, statusInt("deliveredCount"));
    }

    @Test
    void freshConfigWorkerAndCityAreAppliedForEachRound() {
        ReflectionTestUtils.setField(service, "randomCityEnabled", true);
        ReflectionTestUtils.setField(service, "randomCityPool", List.of("101010100", "101020100"));
        doReturn(0).when(service).randomCityIndex(anyInt());
        Round first = round(50), second = round(50), third = round(50);

        service.executeDelivery(messages::add);

        assertEquals(3, loadedConfigs.size());
        assertNotSame(loadedConfigs.get(0), loadedConfigs.get(1));
        assertEquals(List.of("101010100"), loadedConfigs.get(0).getCityCode());
        assertEquals(List.of("101020100"), loadedConfigs.get(1).getCityCode());
        assertEquals(List.of("101010100"), loadedConfigs.get(2).getCityCode());
        verify(service, times(3)).randomCityIndex(anyInt());
        for (int index = 0; index < 3; index++) {
            Round current = List.of(first, second, third).get(index);
            verify(current.boss).setConfig(same(loadedConfigs.get(index)));
            verify(current.boss).setPage(page);
            verify(current.boss).prepare();
            verify(current.boss, times(1)).execute();
        }
    }

    @Test
    void allRoundsShareTheSameSuccessfulJobLedger() {
        Round first = round(50), second = round(50), third = round(50);
        first.afterReturn = () -> first.deliveredIds.get().add("already-sent-job");
        second.beforeCount = () -> assertTrue(second.deliveredIds.get().contains("already-sent-job"));

        service.executeDelivery(messages::add);

        assertNotNull(first.deliveredIds.get());
        assertSame(first.deliveredIds.get(), second.deliveredIds.get());
        assertSame(first.deliveredIds.get(), third.deliveredIds.get());
    }

    @Test
    void aNewExplicitSessionGetsAFreshSuccessfulJobLedgerAndCounters() {
        ReflectionTestUtils.setField(service, "totalTarget", 1);
        Round first = round(1), second = round(1);
        first.afterReturn = () -> first.deliveredIds.get().add("previous-session-job");
        service.executeDelivery(messages::add);
        messages.clear();
        second.beforeCount = () -> {
            assertFalse(second.deliveredIds.get().contains("previous-session-job"));
            assertEquals(0, statusInt("deliveredCount"));
            assertEquals(1, statusInt("roundNumber"));
        };

        service.executeDelivery(messages::add);

        assertNotSame(first.deliveredIds.get(), second.deliveredIds.get());
        assertEquals(1, statusInt("deliveredCount"));
        assertEquals(1, messageCount("success"));
    }

    @Test
    void stopAtRoundBoundaryCancelsTheWholeSessionAndDoesNotStartAnotherWorker() {
        Round first = round(50);
        first.afterReturn = service::stopDelivery;

        service.executeDelivery(messages::add);

        assertEquals(1, provided.get());
        assertEquals(50, statusInt("deliveredCount"));
        assertEquals(0, messageCount("success"));
        assertEquals(0, messageCount("error"));
        assertEquals(1, messageCount("stopped"));
        assertFalse(service.isRunning());
        verify(manager, times(1)).resumeBossMonitoring();
    }

    @Test
    void aSecondStartWhileAnAutomaticSessionRunsCannotCreateAnotherWorker() {
        List<JobProgressMessage> rejected = new ArrayList<>();
        Round first = round(50);
        round(50);
        round(50);
        first.beforeCount = () -> service.executeDelivery(rejected::add);

        service.executeDelivery(messages::add);

        assertEquals(1, rejected.size());
        assertEquals("warning", rejected.getFirst().getType());
        assertEquals(3, provided.get());
        assertEquals(150, statusInt("deliveredCount"));
        assertEquals(1, messageCount("success"));
    }

    @Test
    void protectivePauseInLaterRoundKeepsSuccessfulCountAndNeverContinues() {
        round(50);
        Round second = round(7);
        second.failure = BossTaskPausedException.verificationRequired();

        service.executeDelivery(messages::add);

        assertEquals(2, provided.get());
        assertEquals(57, statusInt("deliveredCount"));
        assertEquals(7, statusInt("roundDelivered"));
        assertEquals(true, service.getStatus().get("paused"));
        assertEquals("SECURITY_VERIFICATION", service.getStatus().get("pauseReason"));
        assertEquals(0, messageCount("success"));
        assertEquals(1, messageCount("error"));
        verify(notifier).notifyPaused(eq(BossTaskPausedException.Reason.SECURITY_VERIFICATION), anyString());
        verify(manager, times(1)).resumeBossMonitoring();
        assertFalse(service.isRunning());
    }

    @Test
    void ordinaryFailureInLaterRoundIsTerminalAndNotAnAutomaticRetry() {
        round(50);
        Round second = round(4);
        second.failure = new IllegalStateException("synthetic worker failure");

        service.executeDelivery(messages::add);

        assertEquals(2, provided.get());
        assertEquals(54, statusInt("deliveredCount"));
        assertEquals(0, messageCount("success"));
        assertEquals(1, messageCount("error"));
        assertEquals(false, service.getStatus().get("paused"));
        verifyNoInteractions(notifier);
        assertFalse(service.isRunning());
    }

    @Test
    void cancellationIsTerminalWithoutAFalseErrorOrNextRound() {
        Round first = round(3);
        first.failure = new CancellationException("synthetic stop");

        service.executeDelivery(messages::add);

        assertEquals(1, provided.get());
        assertEquals(3, statusInt("deliveredCount"));
        assertEquals(0, messageCount("success"));
        assertEquals(0, messageCount("error"));
        assertEquals(1, messageCount("stopped"));
        assertFalse(service.isRunning());
        verifyNoInteractions(notifier);
    }

    @Test
    void loginLossBeforeTheNextRoundDoesNotCreateAnotherWorker() {
        round(50);
        when(manager.refreshBossLoginStatus()).thenReturn(true, false);
        when(manager.getBossLoginMessage()).thenReturn("synthetic login expired");

        service.executeDelivery(messages::add);

        assertEquals(1, provided.get());
        assertEquals(50, statusInt("deliveredCount"));
        assertEquals(0, messageCount("success"));
        assertEquals(1, messageCount("error"));
        verify(manager, times(2)).refreshBossLoginStatus();
        verify(manager, times(1)).resumeBossMonitoring();
    }

    @Test
    void consecutiveEmptyRoundsSendTerminalStoppedInsteadOfLoopingForever() {
        round(0);
        round(0);
        round(0);

        service.executeDelivery(messages::add);

        assertEquals(3, provided.get());
        assertEquals(0, statusInt("deliveredCount"));
        assertEquals(3, statusInt("roundNumber"));
        assertEquals(0, messageCount("success"));
        assertEquals(1, messageCount("stopped"));
        assertFalse(service.isRunning());
    }

    @Test
    void nonemptyRoundResetsTheConsecutiveEmptyRoundGuard() {
        ReflectionTestUtils.setField(service, "totalTarget", 2);
        round(0);
        round(0);
        round(1);
        round(0);
        round(0);
        round(1);

        service.executeDelivery(messages::add);

        assertEquals(6, provided.get());
        assertEquals(2, statusInt("deliveredCount"));
        assertEquals(1, messageCount("success"));
    }

    @Test
    void maximumRoundCountStopsBelowTargetWithTerminalStoppedEvent() {
        ReflectionTestUtils.setField(service, "maxRounds", 2);
        round(50);
        round(50);

        service.executeDelivery(messages::add);

        assertEquals(2, provided.get());
        assertEquals(100, statusInt("deliveredCount"));
        assertEquals(0, messageCount("success"));
        assertEquals(1, messageCount("stopped"));
        assertFalse(service.isRunning());
    }

    @Test
    void previewRemainsOneRoundAndDoesNotReceiveADeliveryCap() {
        preview.set(true);
        Round first = round(0);
        when(first.boss.getPreviewInspectedCount()).thenReturn(5);
        when(first.boss.getPreviewMatchedCount()).thenReturn(3);

        service.executeDelivery(messages::add);

        assertEquals(1, provided.get());
        verify(first.boss, never()).setSuccessfulDeliveryLimit(anyInt());
        assertEquals(0, statusInt("deliveredCount"));
        assertEquals(1, messageCount("success"));
        assertFalse(service.isRunning());
    }

    @Test
    void configuredPreviewOnlyAlsoPreventsAutomaticRounds() {
        ReflectionTestUtils.setField(service, "previewOnly", true);
        Round first = round(0);

        service.executeDelivery(messages::add);

        assertEquals(1, provided.get());
        assertTrue(loadedConfigs.getFirst().getDebugger());
        verify(first.boss, never()).setSuccessfulDeliveryLimit(anyInt());
        assertEquals(1, messageCount("success"));
    }

    @ParameterizedTest
    @CsvSource({"0,150,30,3", "51,150,30,3", "50,0,30,3", "50,150,0,3", "50,150,30,0"})
    void invalidRoundConfigurationStopsBeforeOpeningAWorkerOrBrowser(
            int configuredRoundSize, int configuredTarget, int configuredMaxRounds, int configuredMaxEmptyRounds) {
        ReflectionTestUtils.setField(service, "roundSize", configuredRoundSize);
        ReflectionTestUtils.setField(service, "totalTarget", configuredTarget);
        ReflectionTestUtils.setField(service, "maxRounds", configuredMaxRounds);
        ReflectionTestUtils.setField(service, "maxEmptyRounds", configuredMaxEmptyRounds);

        service.executeDelivery(messages::add);

        assertEquals(0, provided.get());
        assertEquals(0, statusInt("roundNumber"));
        assertEquals(0, statusInt("deliveredCount"));
        assertEquals(0, messageCount("success"));
        assertEquals(1, messageCount("error"));
        assertFalse(service.isRunning());
        verify(manager, never()).ensureReady();
        verify(configService, never()).getBossConfig();
        verifyNoInteractions(notifier);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 51})
    void invalidReturnedDeliveryCountTerminatesInsteadOfStartingAnotherRound(int invalidCount) {
        Round first = round(0);
        doReturn(invalidCount).when(first.boss).execute();

        service.executeDelivery(messages::add);

        assertEquals(1, provided.get());
        assertEquals(0, statusInt("deliveredCount"));
        assertEquals(0, messageCount("success"));
        assertEquals(1, messageCount("error"));
        verify(first.boss).setSuccessfulDeliveryLimit(50);
        assertFalse(service.isRunning());
        verify(manager, times(1)).resumeBossMonitoring();
    }

    @Test
    void keywordOrCommunicationFailureKeepsSentCountButDoesNotRestart() {
        Round first = round(20);
        when(first.boss.hasProcessingFailures()).thenReturn(true);

        service.executeDelivery(messages::add);

        assertEquals(1, provided.get());
        assertEquals(20, statusInt("deliveredCount"));
        assertEquals(1, messageCount("stopped"));
        assertEquals(0, messageCount("success"));
        assertFalse(service.isRunning());
        verify(manager, times(1)).resumeBossMonitoring();
    }

    private Round round(int successfulCount) {
        Round result = new Round(successfulCount);
        rounds.add(result);
        return result;
    }

    private List<Integer> limits(Round... items) {
        return java.util.Arrays.stream(items).map(round -> round.limit.get()).toList();
    }

    private int statusInt(String key) {
        Map<String, Object> status = service.getStatus();
        assertTrue(status.get(key) instanceof Number, "Missing numeric session state: " + key);
        return ((Number) status.get(key)).intValue();
    }

    private long messageCount(String type) {
        return messages.stream().filter(message -> type.equals(message.getType())).count();
    }

    private final class Round {
        private final Boss boss = mock(Boss.class);
        private final AtomicInteger limit = new AtomicInteger();
        private final AtomicReference<IntConsumer> countCallback = new AtomicReference<>();
        private final AtomicReference<Set<String>> deliveredIds = new AtomicReference<>();
        private Runnable beforeCount = () -> { };
        private IntConsumer afterEachCount = ignored -> { };
        private Runnable afterReturn = () -> { };
        private RuntimeException failure;

        @SuppressWarnings("unchecked")
        private Round(int successfulCount) {
            doAnswer(invocation -> { limit.set(invocation.getArgument(0)); return null; })
                    .when(boss).setSuccessfulDeliveryLimit(anyInt());
            doAnswer(invocation -> { countCallback.set(invocation.getArgument(0)); return null; })
                    .when(boss).setDeliveryCountCallback(any(IntConsumer.class));
            doAnswer(invocation -> { deliveredIds.set(invocation.getArgument(0)); return null; })
                    .when(boss).setDeliveredJobIds(any(Set.class));
            when(boss.execute()).thenAnswer(ignored -> {
                assertTrue(service.isRunning(), "A round is part of one running session");
                assertEquals(0, messageCount("success"), "Intermediate rounds must not finish the frontend task");
                beforeCount.run();
                if (successfulCount > 0) {
                    assertNotNull(countCallback.get(), "A live round must report successful sends");
                    assertTrue(limit.get() > 0 && successfulCount <= limit.get(), "Worker exceeds its assigned cap");
                    for (int count = 1; count <= successfulCount; count++) {
                        countCallback.get().accept(count);
                        afterEachCount.accept(count);
                    }
                }
                afterReturn.run();
                if (failure != null) throw failure;
                return successfulCount;
            });
        }
    }
}
