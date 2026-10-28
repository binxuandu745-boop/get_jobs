package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses simulated page state and no network; the mocked waits do not sleep. */
class BossManualSetupWaitTest {
    private static final String BASE = "https://www.zhipin.com/web/geek/jobs?city=101010100"
            + "&position=100101&jobType=1902&query=Java";
    private static final String CHOSEN = "https://www.zhipin.com/web/geek/jobs?city=101010100"
            + "&position=100102&jobType=1901&query=Java";
    private Boss boss;
    private BossConfig config;
    private Page page;
    private Locator verification, position, jobType;
    private AtomicReference<String> current;
    private AtomicBoolean stopped;
    private AtomicInteger waits;
    private List<String> progress;

    @BeforeEach
    void setUp() {
        boss = new Boss(mock(BossService.class), mock(AiService.class));
        page = mock(Page.class);
        verification = mock(Locator.class);
        position = mock(Locator.class);
        jobType = mock(Locator.class);
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        when(page.getByText("职位类型")).thenReturn(position);
        when(position.first()).thenReturn(position);
        when(page.getByText("求职类型")).thenReturn(jobType);
        when(jobType.first()).thenReturn(jobType);
        when(jobType.isVisible()).thenReturn(true);
        current = new AtomicReference<>("https://www.zhipin.com/web/geek/jobs");
        when(page.url()).thenAnswer(call -> current.get());
        doAnswer(call -> { current.set(call.getArgument(0)); return null; })
                .when(page).navigate(anyString(), any(Page.NavigateOptions.class));
        waits = new AtomicInteger();
        doAnswer(call -> { waits.incrementAndGet(); return null; }).when(page).waitForTimeout(anyDouble());
        config = new BossConfig();
        config.setCityCode(List.of("101010100"));
        config.setKeywords(List.of("Java", "Backend"));
        config.setPosition("100101");
        config.setJobType("1902");
        boss.setConfig(config);
        boss.setPage(page);
        stopped = new AtomicBoolean();
        boss.setShouldStopCallback(stopped::get);
        progress = new ArrayList<>();
        boss.setProgressCallback((text, number, total) -> progress.add(text));
    }

    @Test
    void oneVisibleControlAllowsCountdownAndCapturesChangedUrlAfterStableWait() {
        doAnswer(call -> { if (waits.incrementAndGet() == 3) current.set(CHOSEN); return null; })
                .when(page).waitForTimeout(anyDouble());

        BossManualSearchFilters selected = boss.awaitManualSearchSetup(10000);

        assertTrue(selected.matchesSelectedUrl(CHOSEN));
        selected.applyTo(config);
        assertEquals("100102", config.getPosition());
        assertEquals("1901", config.getJobType());
        assertEquals(List.of("Java", "Backend"), config.getKeywords());
        assertTrue(waits.get() >= 44, "Ten seconds of quarter-second waits plus a stable URL check");
        assertTrue(progress.stream().anyMatch(value -> value.contains("10 秒")));
        verify(page, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void absentFilterControlsStopBeforeCountdownOrDelivery() {
        when(jobType.isVisible()).thenReturn(false);

        BossTaskPausedException paused = assertThrows(BossTaskPausedException.class,
                () -> boss.awaitManualSearchSetup(1000));

        assertTrue(paused.getMessage().contains("筛选控件尚未就绪"));
        assertFalse(progress.stream().anyMatch(value -> value.contains("还剩")));
        assertEquals(40, waits.get());
    }

    @Test
    void earlyFilterChangeAfterInitialPageWasSeenStillAllowsTheManualWindow() {
        AtomicBoolean first = new AtomicBoolean(true);
        when(jobType.isVisible()).thenAnswer(call -> {
            if (first.getAndSet(false)) {
                current.set(CHOSEN);
                return false;
            }
            return true;
        });

        BossManualSearchFilters selected = boss.awaitManualSearchSetup(1000);

        assertTrue(selected.matchesSelectedUrl(CHOSEN));
        assertTrue(progress.stream().anyMatch(value -> value.contains("还剩")));
    }

    @Test
    void neverReachingInitialSearchCannotTreatAnotherSearchAsUserSelection() {
        doAnswer(call -> { current.set(CHOSEN); return null; })
                .when(page).navigate(anyString(), any(Page.NavigateOptions.class));

        assertThrows(BossTaskPausedException.class, () -> boss.awaitManualSearchSetup(1000));

        assertFalse(progress.stream().anyMatch(value -> value.contains("还剩")));
    }

    @Test
    void stopDuringCountdownIsNotConvertedIntoACompletedSetup() {
        doAnswer(call -> { waits.incrementAndGet(); stopped.set(true); return null; })
                .when(page).waitForTimeout(anyDouble());

        assertThrows(CancellationException.class, () -> boss.awaitManualSearchSetup(10000));

        assertEquals(1, waits.get());
    }

    @Test
    void verificationDuringCountdownRemainsAProtectivePause() {
        doAnswer(call -> {
            waits.incrementAndGet();
            current.set("https://www.zhipin.com/web/passport/zp/verify.html");
            return null;
        }).when(page).waitForTimeout(anyDouble());

        BossTaskPausedException paused = assertThrows(BossTaskPausedException.class,
                () -> boss.awaitManualSearchSetup(10000));

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, paused.getReason());
        assertEquals(1, waits.get());
    }

    @Test
    void anUnstableAddressAfterCountdownDoesNotStartDelivery() {
        doAnswer(call -> {
            int count = waits.incrementAndGet();
            if (count >= 4) current.set((count % 2 == 0) ? BASE : CHOSEN);
            return null;
        }).when(page).waitForTimeout(anyDouble());

        BossTaskPausedException paused = assertThrows(BossTaskPausedException.class,
                () -> boss.awaitManualSearchSetup(1000));

        assertTrue(paused.getMessage().contains("尚未稳定"));
    }

    @Test
    void invalidInitialFilterCodeFailsBeforeOpeningSearchPage() {
        config.setPosition("100101&jobType=1901");

        assertThrows(BossTaskPausedException.class, () -> boss.awaitManualSearchSetup(10000));

        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void changedSelectionAfterSnapshotStopsBeforeAutomatedNavigation() {
        BossManualSearchFilters snapshot = BossManualSearchFilters.capture(BASE, CHOSEN);
        boss.setManualSearchGuard(snapshot);
        current.set(BASE);

        BossTaskPausedException paused = assertThrows(BossTaskPausedException.class, boss::execute);

        assertTrue(paused.getMessage().contains("筛选条件已改变"));
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void selectionChangedByProbeBeforeFirstNavigationIsNotOverwritten() throws Exception {
        BossManualSearchFilters snapshot = BossManualSearchFilters.capture(BASE, CHOSEN);
        boss.setManualSearchGuard(snapshot);
        current.set(CHOSEN);
        when(verification.isVisible()).thenAnswer(call -> { current.set(BASE); return false; });
        Method method = Boss.class.getDeclaredMethod("navigateTo", String.class);
        method.setAccessible(true);

        InvocationTargetException wrapper = assertThrows(InvocationTargetException.class,
                () -> method.invoke(boss, CHOSEN));

        assertInstanceOf(BossTaskPausedException.class, wrapper.getCause());
        assertTrue(wrapper.getCause().getMessage().contains("筛选条件已改变"));
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void noKeywordAllowsTheUserToProvideOneDuringTheManualWindow() {
        config.setKeywords(List.of());
        doAnswer(call -> {
            if (waits.incrementAndGet() == 1) current.set(BASE.replace("query=Java", "query=Backend"));
            return null;
        }).when(page).waitForTimeout(anyDouble());

        BossManualSearchFilters selected = boss.awaitManualSearchSetup(1000);
        selected.applyTo(config);

        assertEquals(List.of("Backend"), config.getKeywords());
    }

    @Test
    void noKeywordCanRemainEmptyWithoutBlockingSetup() {
        config.setKeywords(List.of());

        BossManualSearchFilters selected = assertDoesNotThrow(() -> boss.awaitManualSearchSetup(1000));
        selected.applyTo(config);

        assertEquals(List.of(""), config.getKeywords());
    }
}
