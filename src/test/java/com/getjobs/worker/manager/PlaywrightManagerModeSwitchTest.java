package com.getjobs.worker.manager;

import com.getjobs.application.service.BrowserModePreferenceStore;
import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Uses mocked browser objects and initialization; no engine, browser profile or database is opened. */
class PlaywrightManagerModeSwitchTest {
    private PlaywrightManager manager;
    private BrowserModePreferenceStore preferences;
    private CookieService cookieService;
    private BrowserContext context;
    private Playwright engine;
    private Page bossPage;
    private Page liepinPage;
    private Page job51Page;
    private Page zhilianPage;
    private ExecutorService callers;
    private List<PlaywrightManager.LoginStatusChange> changes;

    @BeforeEach
    void setUp() {
        manager = spy(new PlaywrightManager());
        preferences = mock(BrowserModePreferenceStore.class);
        cookieService = mock(CookieService.class);
        context = mock(BrowserContext.class);
        engine = mock(Playwright.class);
        bossPage = mock(Page.class);
        liepinPage = mock(Page.class);
        job51Page = mock(Page.class);
        zhilianPage = mock(Page.class);
        callers = Executors.newFixedThreadPool(3);
        changes = new CopyOnWriteArrayList<>();
        ReflectionTestUtils.setField(manager, "modePreferenceStore", preferences);
        ReflectionTestUtils.setField(manager, "cookieService", cookieService);
        ReflectionTestUtils.setField(manager, "platformMode", "boss");
        ReflectionTestUtils.setField(manager, "bossOnly", true);
        setInitialRuntime(BrowserPlatformMode.BOSS);
        doAnswer(invocation -> {
            installMockRuntime();
            return null;
        }).when(manager).initializeSelectedMode();
        manager.addLoginStatusListener(changes::add);
    }

    @AfterEach
    void stopTestExecutors() throws InterruptedException {
        callers.shutdownNow();
        assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS), "Test callers did not stop");
        ExecutorService playwrightExecutor = (ExecutorService)
                ReflectionTestUtils.getField(manager, "playwrightExecutor");
        playwrightExecutor.shutdownNow();
        assertTrue(playwrightExecutor.awaitTermination(5, TimeUnit.SECONDS), "Playwright executor did not stop");
    }

    @ParameterizedTest
    @ValueSource(strings = {"boss", "liepin", "51job", "zhilian"})
    void persistedModeOverridesYamlAndLegacyFlagAndIsReadOnlyOnce(String savedMode) {
        when(preferences.readSavedMode()).thenReturn(savedMode);
        ReflectionTestUtils.setField(manager, "platformMode", "all");
        ReflectionTestUtils.setField(manager, "bossOnly", true);

        assertEquals(savedMode, manager.getBrowserPlatformMode());
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) {
            assertEquals(platform.equals(savedMode), manager.isPlatformEnabled(platform), platform);
        }
        assertEquals(savedMode, manager.getBrowserPlatformMode());

        verify(preferences, times(1)).readSavedMode();
        assertNoSwitchOperations();
        verifyNoInteractions(engine, context, cookieService);
    }

    @ParameterizedTest
    @MethodSource("startupFallbacks")
    void missingSavedPreferenceUsesYamlOrTheLegacyStartupFlag(String yaml, boolean bossOnly,
                                                             String expected) {
        ReflectionTestUtils.setField(manager, "platformMode", yaml);
        ReflectionTestUtils.setField(manager, "bossOnly", bossOnly);

        assertEquals(expected, manager.getBrowserPlatformMode());

        verify(preferences).readSavedMode();
        assertNoSwitchOperations();
        verifyNoInteractions(engine, context, cookieService);
    }

    @Test
    void validSavedPreferenceTakesPriorityEvenWhenYamlIsInvalid() {
        when(preferences.readSavedMode()).thenReturn("boss");
        ReflectionTestUtils.setField(manager, "platformMode", "unsupported");

        assertEquals("boss", manager.getBrowserPlatformMode());
        assertFalse(manager.isPlatformEnabled("liepin"));
        assertFalse(manager.isPlatformEnabled("51job"));
        assertFalse(manager.isPlatformEnabled("zhilian"));
        assertNoSwitchOperations();
    }

    @Test
    void invalidStartupConfigurationNeverFallsBackToAllPlatforms() {
        ReflectionTestUtils.setField(manager, "platformMode", "unsupported");
        ReflectionTestUtils.setField(manager, "bossOnly", false);

        assertThrows(IllegalArgumentException.class, manager::getBrowserPlatformMode);
        assertNull(ReflectionTestUtils.getField(manager, "selectedMode"));
        assertNoSwitchOperations();
        verifyNoInteractions(engine, context, cookieService);
    }

    @Test
    void unreadableSavedPreferenceFailsWithoutFallingBackToYaml() {
        when(preferences.readSavedMode()).thenThrow(new IllegalStateException("saved preference unavailable"));
        ReflectionTestUtils.setField(manager, "platformMode", "all");

        assertThrows(IllegalStateException.class, manager::getBrowserPlatformMode);
        assertNull(ReflectionTestUtils.getField(manager, "selectedMode"));
        assertNoSwitchOperations();
        verifyNoInteractions(engine, context, cookieService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "unsupported", "job51", "boss,liepin"})
    void switchRequiresAnExplicitValidModeBeforeCheckingBusyState(String requested) {
        AtomicBoolean checkedTask = new AtomicBoolean();
        queueDepth().set(1);
        try {
            assertThrows(IllegalArgumentException.class, () -> manager.switchPlatformMode(requested, () -> {
                checkedTask.set(true);
                return true;
            }));

            assertFalse(checkedTask.get());
            assertFalse(manager.isSwitchingMode());
            assertNull(ReflectionTestUtils.getField(manager, "selectedMode"));
            verifyNoInteractions(preferences, engine, context, cookieService);
            verify(manager, never()).closeForModeSwitch();
            verify(manager, never()).initializeSelectedMode();
        } finally {
            queueDepth().set(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"boss", "liepin", "51job", "zhilian"})
    void runningDeliveryRejectsSwitchEvenWhenTheRequestedModeMatches(String requested) {
        assertThrows(IllegalStateException.class, () -> manager.switchPlatformMode(requested, () -> true));

        assertFalse(manager.isSwitchingMode());
        assertEquals("boss", manager.getBrowserPlatformMode());
        assertSame(context, manager.getContext());
        assertSame(engine, manager.getPlaywright());
        assertNoSwitchOperations();
        verifyNoInteractions(engine, context, cookieService);
    }

    @Test
    void queuedAndRunningPlaywrightWorkRejectsSwitchWithoutDisturbingEitherTask() throws Exception {
        CountDownLatch foregroundEntered = new CountDownLatch(1);
        CountDownLatch releaseForeground = new CountDownLatch(1);
        AtomicInteger queuedExecutions = new AtomicInteger();
        Future<String> foreground = callers.submit(() -> manager.callOnPlaywright(() -> {
            foregroundEntered.countDown();
            await(releaseForeground, "Foreground task was not released");
            return "foreground complete";
        }));
        await(foregroundEntered, "Foreground task did not start");
        Future<Integer> queued = callers.submit(() -> manager.callOnPlaywright(queuedExecutions::incrementAndGet));

        try {
            awaitQueueDepth(2);
            assertThrows(IllegalStateException.class, () -> manager.switchPlatformMode("liepin", () -> false));
            assertFalse(manager.isSwitchingMode());
            assertFalse(manager.tryRunOnPlaywright(() -> { throw new AssertionError("Busy background task ran"); }));
            assertEquals(0, queuedExecutions.get());
            assertNoSwitchOperations();
            verifyNoInteractions(engine, context, cookieService);
        } finally {
            releaseForeground.countDown();
        }

        assertEquals("foreground complete", foreground.get(5, TimeUnit.SECONDS));
        assertEquals(1, queued.get(5, TimeUnit.SECONDS));
        assertEquals("boss", manager.getBrowserPlatformMode());
        assertFalse(manager.isPlaywrightBusy());
        assertEquals("available", manager.callOnPlaywright(() -> "available"));
    }

    @Test
    void reservedPlatformTaskRejectsSwitchBeforeItSubmitsAnyPlaywrightWork() throws Exception {
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        Future<?> task = manager.startPlatformTask("boss", () -> {
            taskEntered.countDown();
            await(releaseTask, "Reserved platform task was not released");
        });
        await(taskEntered, "Reserved platform task did not start");

        try {
            assertTrue(manager.hasPlatformTaskReservations());
            assertFalse(manager.isPlaywrightBusy());
            assertFalse(manager.canSwitchPlatformMode(() -> false));
            assertThrows(IllegalStateException.class, () -> manager.switchPlatformMode("liepin", () -> false));
            assertFalse(manager.isSwitchingMode());
            assertNoSwitchOperations();
            verifyNoInteractions(engine, context, cookieService);
        } finally {
            releaseTask.countDown();
        }

        task.get(5, TimeUnit.SECONDS);
        assertFalse(manager.hasPlatformTaskReservations());
        assertTrue(manager.canSwitchPlatformMode(() -> false));
        assertEquals("boss", manager.getBrowserPlatformMode());
    }

    @Test
    void initializedSameModeIsANoopThatRetainsRuntimeAndLoginState() {
        setAuthenticated(BrowserPlatformMode.BOSS);

        assertFalse(manager.switchPlatformMode(" \tBoSs\n", () -> false));

        assertEquals("boss", manager.getBrowserPlatformMode());
        assertTrue(manager.isInitialized());
        assertTrue(manager.isLoggedIn("boss"));
        assertSame(context, manager.getContext());
        assertSame(engine, manager.getPlaywright());
        assertSame(bossPage, manager.getBossPage());
        assertFalse(manager.isSwitchingMode());
        assertTrue(changes.isEmpty());
        assertNoSwitchOperations();
        verifyNoInteractions(engine, context, cookieService);
    }

    @Test
    void sameModeWithAClosedBrowserRebuildsTheRuntime() {
        ReflectionTestUtils.setField(manager, "browserClosed", true);

        assertTrue(manager.switchPlatformMode("boss", () -> false));

        assertEquals("boss", manager.getBrowserPlatformMode());
        assertTrue(manager.isInitialized());
        assertNotSame(context, manager.getContext());
        assertNotSame(engine, manager.getPlaywright());
        verify(preferences).save("boss");
        verify(manager).closeForModeSwitch();
        verify(manager).initializeSelectedMode();
        assertFalse(manager.isSwitchingMode());
        assertSessionDataWasNotChanged();
    }

    @ParameterizedTest
    @MethodSource("newSinglePlatformSwitches")
    void switchingToJob51OrZhilianCreatesOnlyTheSelectedMockPage(String requested, String expected,
                                                               BrowserPlatformMode target) {
        assertTrue(manager.switchPlatformMode(requested, () -> false));

        assertEquals(expected, manager.getBrowserPlatformMode());
        assertSame(target, ReflectionTestUtils.getField(manager, "selectedMode"));
        assertTrue(manager.isInitialized());
        assertNull(manager.getBossPage());
        assertNull(manager.getLiepinPage());
        if (target == BrowserPlatformMode.JOB51) {
            assertNull(manager.getZhilianPage());
            assertNotSame(job51Page, manager.getJob51Page());
            assertNotNull(manager.getJob51Page());
        } else {
            assertNull(manager.getJob51Page());
            assertNotSame(zhilianPage, manager.getZhilianPage());
            assertNotNull(manager.getZhilianPage());
        }
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) {
            assertEquals(platform.equals(expected), manager.isPlatformEnabled(platform), platform);
            assertFalse(manager.isLoggedIn(platform), platform);
        }
        InOrder order = inOrder(preferences, manager);
        order.verify(preferences).save(expected);
        order.verify(manager).closeForModeSwitch();
        order.verify(manager).initializeSelectedMode();
        verify(context).close();
        verify(engine).close();
        assertFalse(manager.isSwitchingMode());
        assertFalse(manager.isPlaywrightBusy());
        assertSessionDataWasNotChanged();
        verifyNoInteractions(cookieService);
        assertEquals("available", manager.callOnPlaywright(() -> "available"));
    }

    @Test
    void switchPersistsThenClosesOldModeAndInitializesTargetOnThePlaywrightThread() {
        ReflectionTestUtils.setField(manager, "platformMode", "all");
        setInitialRuntime(BrowserPlatformMode.ALL);
        setAuthenticated(BrowserPlatformMode.ALL);
        AtomicReference<String> closeMode = new AtomicReference<>();
        AtomicReference<String> closeThread = new AtomicReference<>();
        AtomicReference<String> initializeMode = new AtomicReference<>();
        AtomicReference<String> initializeThread = new AtomicReference<>();
        doAnswer(invocation -> {
            closeMode.set(manager.getBrowserPlatformMode());
            closeThread.set(Thread.currentThread().getName());
            return invocation.callRealMethod();
        }).when(manager).closeForModeSwitch();
        doAnswer(invocation -> {
            initializeMode.set(manager.getBrowserPlatformMode());
            initializeThread.set(Thread.currentThread().getName());
            installMockRuntime();
            return null;
        }).when(manager).initializeSelectedMode();

        assertTrue(manager.switchPlatformMode("  LiEpIn \t", () -> false));

        InOrder order = inOrder(preferences, manager);
        order.verify(preferences).save("liepin");
        order.verify(manager).closeForModeSwitch();
        order.verify(manager).initializeSelectedMode();
        assertEquals("all", closeMode.get());
        assertEquals("liepin", initializeMode.get());
        assertEquals("playwright-thread", closeThread.get());
        assertEquals(closeThread.get(), initializeThread.get());
        assertEquals("liepin", manager.getBrowserPlatformMode());
        assertTrue(manager.isInitialized());
        assertFalse(manager.isSwitchingMode());
        assertFalse(manager.isPlaywrightBusy());
        assertNull(manager.getBossPage());
        assertNull(manager.getJob51Page());
        assertNull(manager.getZhilianPage());
        assertNotSame(liepinPage, manager.getLiepinPage());
        verify(context).close();
        verify(engine).close();
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) {
            assertFalse(manager.getLoginStatus().get(platform), platform);
            assertFalse(manager.isLoggedIn(platform), platform);
            assertTrue(changes.stream().anyMatch(change -> platform.equals(change.platform())
                    && !change.isLoggedIn()), platform + " did not publish invalidation");
        }
        assertEquals("UNKNOWN", manager.getBossLoginState());
        assertEquals("UNKNOWN", manager.getLiepinLoginState());
        assertSessionDataWasNotChanged();
        verifyNoInteractions(bossPage, liepinPage, job51Page, zhilianPage);
        assertEquals("available", manager.callOnPlaywright(() -> "available"));
    }

    @Test
    void persistenceFailureRetainsTheOldRuntimeAndReleasesTheSwitchGuard() {
        doThrow(new IllegalStateException("private-persistence-token")).when(preferences).save("liepin");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> manager.switchPlatformMode("liepin", () -> false));

        assertFalse(failure.getMessage().contains("private-persistence-token"));
        assertNull(failure.getCause());
        assertEquals("boss", manager.getBrowserPlatformMode());
        assertSame(context, manager.getContext());
        assertSame(engine, manager.getPlaywright());
        assertSame(bossPage, manager.getBossPage());
        assertTrue(manager.isInitialized());
        assertFalse(manager.isSwitchingMode());
        assertFalse(manager.isPlaywrightBusy());
        verify(preferences).save("liepin");
        verify(manager, never()).closeForModeSwitch();
        verify(manager, never()).initializeSelectedMode();
        verifyNoInteractions(engine, context, cookieService);
        assertEquals("available", manager.callOnPlaywright(() -> "available"));
    }

    @Test
    void initializationFailureRetainsSavedTargetCleansPartialRuntimeAndReleasesTheSwitchGuard() {
        AtomicReference<BrowserContext> partialContext = new AtomicReference<>();
        AtomicReference<Playwright> partialEngine = new AtomicReference<>();
        doAnswer(invocation -> {
            installMockRuntime();
            partialContext.set(manager.getContext());
            partialEngine.set(manager.getPlaywright());
            throw new IllegalStateException("private-initialization-token");
        }).when(manager).initializeSelectedMode();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> manager.switchPlatformMode("liepin", () -> false));

        assertFalse(failure.getMessage().contains("private-initialization-token"));
        assertNull(failure.getCause());
        assertEquals("liepin", manager.getBrowserPlatformMode());
        assertFalse(manager.isInitialized());
        assertNull(manager.getContext());
        assertNull(manager.getPlaywright());
        assertNull(manager.getBossPage());
        assertNull(manager.getLiepinPage());
        assertNull(manager.getJob51Page());
        assertNull(manager.getZhilianPage());
        assertFalse(manager.isSwitchingMode());
        assertFalse(manager.isPlaywrightBusy());
        verify(preferences, times(1)).save("liepin");
        verify(manager, times(2)).closeForModeSwitch();
        verify(manager).initializeSelectedMode();
        verify(context).close();
        verify(engine).close();
        verify(partialContext.get()).close();
        verify(partialEngine.get()).close();
        assertSessionDataWasNotChanged();
        assertEquals("available", manager.callOnPlaywright(() -> "available"));
    }

    @Test
    void switchInProgressRejectsAnotherSwitchAndForegroundSubmissionAndSkipsBackgroundWork() throws Exception {
        CountDownLatch initializationEntered = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        AtomicBoolean foregroundRan = new AtomicBoolean();
        AtomicBoolean backgroundRan = new AtomicBoolean();
        blockInitialization(initializationEntered, releaseInitialization);
        Future<Boolean> switching = callers.submit(() -> manager.switchPlatformMode("liepin", () -> false));
        await(initializationEntered, "Mode initialization did not start");

        try {
            assertTrue(manager.isSwitchingMode());
            assertThrows(IllegalStateException.class, () -> manager.switchPlatformMode("all", () -> false));
            assertThrows(IllegalStateException.class, () -> manager.callOnPlaywright(() -> {
                foregroundRan.set(true);
                return null;
            }));
            assertThrows(IllegalStateException.class,
                    () -> manager.startPlatformTask("boss", () -> foregroundRan.set(true)));
            assertFalse(manager.tryRunOnPlaywright(() -> backgroundRan.set(true)));
            assertFalse(foregroundRan.get());
            assertFalse(backgroundRan.get());
            verify(preferences, times(1)).save("liepin");
            verify(preferences, never()).save("all");
        } finally {
            releaseInitialization.countDown();
        }

        assertTrue(switching.get(5, TimeUnit.SECONDS));
        assertFalse(manager.isSwitchingMode());
        assertFalse(manager.isPlaywrightBusy());
        assertEquals("available", manager.callOnPlaywright(() -> "available"));
        CountDownLatch backgroundCompleted = new CountDownLatch(1);
        assertTrue(manager.tryRunOnPlaywright(backgroundCompleted::countDown));
        await(backgroundCompleted, "Background work remained blocked after switching");
    }

    @Test
    void submissionRacingWithSwitchAdmissionCannotEnterTheOldBrowser() throws Exception {
        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        CountDownLatch initializationEntered = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        CountDownLatch submissionStarted = new CountDownLatch(1);
        AtomicBoolean foregroundRan = new AtomicBoolean();
        blockInitialization(initializationEntered, releaseInitialization);
        Future<Boolean> switching = callers.submit(() -> manager.switchPlatformMode("liepin", () -> {
            guardEntered.countDown();
            await(releaseGuard, "Task-running guard was not released");
            return false;
        }));
        await(guardEntered, "Switch admission guard did not start");
        Future<?> submission = callers.submit(() -> {
            submissionStarted.countDown();
            return manager.callOnPlaywright(() -> {
                foregroundRan.set(true);
                return null;
            });
        });

        try {
            await(submissionStarted, "Racing submission did not start");
            releaseGuard.countDown();
            await(initializationEntered, "Switch did not reach initialization");
            ExecutionException rejection = assertThrows(ExecutionException.class,
                    () -> submission.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, rejection.getCause());
            assertFalse(foregroundRan.get());
            assertTrue(manager.isSwitchingMode());
        } finally {
            releaseGuard.countDown();
            releaseInitialization.countDown();
        }

        assertTrue(switching.get(5, TimeUnit.SECONDS));
        assertFalse(manager.isSwitchingMode());
        assertEquals("liepin", manager.getBrowserPlatformMode());
        assertEquals("available", manager.callOnPlaywright(() -> "available"));
    }

    private void blockInitialization(CountDownLatch entered, CountDownLatch release) {
        doAnswer(invocation -> {
            entered.countDown();
            await(release, "Mode initialization was not released");
            installMockRuntime();
            return null;
        }).when(manager).initializeSelectedMode();
    }

    private void setInitialRuntime(BrowserPlatformMode mode) {
        ReflectionTestUtils.setField(manager, "playwright", engine);
        ReflectionTestUtils.setField(manager, "context", context);
        ReflectionTestUtils.setField(manager, "browserClosed", false);
        ReflectionTestUtils.setField(manager, "bossPage", mode.enables("boss") ? bossPage : null);
        ReflectionTestUtils.setField(manager, "liepinPage", mode.enables("liepin") ? liepinPage : null);
        ReflectionTestUtils.setField(manager, "job51Page", mode.enables("51job") ? job51Page : null);
        ReflectionTestUtils.setField(manager, "zhilianPage", mode.enables("zhilian") ? zhilianPage : null);
    }

    private void installMockRuntime() {
        BrowserPlatformMode mode = BrowserPlatformMode.resolve(manager.getBrowserPlatformMode(), false);
        ReflectionTestUtils.setField(manager, "playwright", mock(Playwright.class));
        ReflectionTestUtils.setField(manager, "context", mock(BrowserContext.class));
        ReflectionTestUtils.setField(manager, "browserClosed", false);
        ReflectionTestUtils.setField(manager, "bossPage", mode.enables("boss") ? mock(Page.class) : null);
        ReflectionTestUtils.setField(manager, "liepinPage", mode.enables("liepin") ? mock(Page.class) : null);
        ReflectionTestUtils.setField(manager, "job51Page", mode.enables("51job") ? mock(Page.class) : null);
        ReflectionTestUtils.setField(manager, "zhilianPage", mode.enables("zhilian") ? mock(Page.class) : null);
    }

    private void setAuthenticated(BrowserPlatformMode mode) {
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) {
            if (mode.enables(platform)) manager.getLoginStatus().put(platform, true);
        }
        ReflectionTestUtils.setField(manager, "bossLoginResult",
                new BossLoginProbe.Result(BossLoginProbe.State.LOGGED_IN, "authenticated", "success"));
        ReflectionTestUtils.setField(manager, "liepinLoginResult",
                new LiepinLoginProbe.Result(LiepinLoginProbe.State.LOGGED_IN, "authenticated"));
    }

    private AtomicInteger queueDepth() {
        return (AtomicInteger) ReflectionTestUtils.getField(manager, "playwrightQueueDepth");
    }

    private void awaitQueueDepth(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queueDepth().get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, queueDepth().get(), "Playwright work did not enter the queue");
    }

    private static void await(CountDownLatch latch, String failureMessage) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), failureMessage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failureMessage, interrupted);
        }
    }

    private void assertNoSwitchOperations() {
        verify(preferences, never()).save(anyString());
        verify(manager, never()).closeForModeSwitch();
        verify(manager, never()).initializeSelectedMode();
    }

    private void assertSessionDataWasNotChanged() {
        verify(context, never()).clearCookies();
        verify(context, never()).addCookies(anyList());
        verify(cookieService, never()).getCookieByPlatform(anyString());
        verify(cookieService, never()).clearCookieByPlatform(anyString(), anyString());
        verify(cookieService, never()).deleteCookie(anyString());
    }

    private static Stream<Arguments> startupFallbacks() {
        return Stream.of(
                Arguments.of(null, true, "boss"),
                Arguments.of("", false, "all"),
                Arguments.of(" \t", true, "boss"),
                Arguments.of(" \tLiEpIn\n", false, "liepin"),
                Arguments.of(" \t51JoB\n", false, "51job"),
                Arguments.of(" \tZhIlIaN\n", true, "zhilian"),
                Arguments.of("all", true, "all")
        );
    }

    private static Stream<Arguments> newSinglePlatformSwitches() {
        return Stream.of(
                Arguments.of(" \t51JoB\n", "51job", BrowserPlatformMode.JOB51),
                Arguments.of(" \tZhIlIaN\n", "zhilian", BrowserPlatformMode.ZHILIAN)
        );
    }
}
