package com.getjobs.worker.manager;

import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Mocked platform pages and login evidence only; never initialize an engine, profile or browser. */
class PlaywrightManagerPlatformModeTest {
    private PlaywrightManager manager;
    private BrowserContext context;
    private Playwright engine;
    private Page page;
    private CookieService cookieService;
    private List<PlaywrightManager.LoginStatusChange> changes;

    @BeforeEach
    void setUp() {
        manager = spy(new PlaywrightManager());
        context = mock(BrowserContext.class);
        engine = mock(Playwright.class);
        page = mock(Page.class);
        cookieService = mock(CookieService.class);
        changes = new ArrayList<>();
        ReflectionTestUtils.setField(manager, "platformMode", "liepin");
        ReflectionTestUtils.setField(manager, "bossOnly", true);
        ReflectionTestUtils.setField(manager, "playwright", engine);
        ReflectionTestUtils.setField(manager, "context", context);
        ReflectionTestUtils.setField(manager, "liepinPage", page);
        ReflectionTestUtils.setField(manager, "cookieService", cookieService);
        when(context.cookies()).thenReturn(List.of());
        when(context.pages()).thenReturn(List.of(page));
        when(page.url()).thenReturn("https://www.liepin.com/zhaopin/");
        header(page, false);
        doAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call())
                .when(manager).callOnPlaywright(any());
        manager.addLoginStatusListener(changes::add);
    }

    @ParameterizedTest
    @EnumSource(BrowserPlatformMode.class)
    void pageCreationUsesOnlyTheSelectedPlatforms(BrowserPlatformMode mode) {
        ReflectionTestUtils.setField(manager, "platformMode", modeValue(mode));
        Page first = mock(Page.class);
        Page second = mock(Page.class);
        Page third = mock(Page.class);
        Page fourth = mock(Page.class);
        when(context.newPage()).thenReturn(first, second, third, fourth);

        ReflectionTestUtils.invokeMethod(manager, "createPlatformPages", mode);

        int expectedCount = mode == BrowserPlatformMode.ALL ? 4 : 1;
        verify(context, times(expectedCount)).newPage();
        assertEquals(modeValue(mode), manager.getBrowserPlatformMode());
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) {
            assertEquals(mode.enables(platform), manager.isPlatformEnabled(platform));
        }
        switch (mode) {
            case BOSS -> {
                assertSame(first, manager.getBossPage());
                assertNull(manager.getLiepinPage());
                assertNull(manager.getJob51Page());
                assertNull(manager.getZhilianPage());
            }
            case LIEPIN -> {
                assertNull(manager.getBossPage());
                assertSame(first, manager.getLiepinPage());
                assertNull(manager.getJob51Page());
                assertNull(manager.getZhilianPage());
            }
            case JOB51 -> {
                assertNull(manager.getBossPage());
                assertNull(manager.getLiepinPage());
                assertSame(first, manager.getJob51Page());
                assertNull(manager.getZhilianPage());
            }
            case ZHILIAN -> {
                assertNull(manager.getBossPage());
                assertNull(manager.getLiepinPage());
                assertNull(manager.getJob51Page());
                assertSame(first, manager.getZhilianPage());
            }
            case ALL -> {
                assertSame(first, manager.getBossPage());
                assertSame(second, manager.getLiepinPage());
                assertSame(third, manager.getJob51Page());
                assertSame(fourth, manager.getZhilianPage());
                verify(second).setDefaultTimeout(30_000);
                verify(third).setDefaultTimeout(30_000);
                verify(fourth).setDefaultTimeout(30_000);
            }
        }
        verify(first).setDefaultTimeout(30_000);
        verifyNoInteractions(engine, cookieService);
        verify(manager, never()).init();
    }

    @ParameterizedTest
    @EnumSource(value = BrowserPlatformMode.class, names = {"BOSS", "LIEPIN", "JOB51", "ZHILIAN"})
    void singlePlatformInitializationRequiresOnlyItsOwnPage(BrowserPlatformMode mode) {
        setSinglePlatformRuntime(mode);

        assertTrue(manager.isInitialized());
        assertEquals(modeValue(mode), manager.getBrowserPlatformMode());
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) {
            assertEquals(platform.equals(modeValue(mode)), manager.isPlatformEnabled(platform), platform);
            assertFalse(manager.isLoggedIn(platform), platform);
        }
        verifyNoInteractions(context, engine, cookieService);
    }

    @ParameterizedTest
    @EnumSource(value = BrowserPlatformMode.class, names = {"JOB51", "ZHILIAN"})
    void job51AndZhilianInitializationRejectsMissingOrClosedSelectedPage(BrowserPlatformMode mode) {
        setSinglePlatformRuntime(mode);
        assertTrue(manager.isInitialized());
        assertNull(manager.getBossPage());
        assertNull(manager.getLiepinPage());

        when(page.isClosed()).thenReturn(true);
        assertFalse(manager.isInitialized());
        when(page.isClosed()).thenReturn(false);
        ReflectionTestUtils.setField(manager, selectedPageField(mode), null);
        assertFalse(manager.isInitialized());
        assertFalse(manager.isLoggedIn(modeValue(mode)));
        verifyNoInteractions(context, engine, cookieService);
    }

    @Test
    void liepinStartupKeepsPersistentProfileAndNeverImportsOldDatabaseCookies() {
        header(page, true);
        ReflectionTestUtils.invokeMethod(manager, "setupLiepinPlatform");
        assertTrue(manager.isLoggedIn("liepin"));
        verify(cookieService, never()).getCookieByPlatform("liepin");
        verify(context, never()).addCookies(any());
        verify(context, never()).clearCookies();
        verify(page).navigate(eq("https://www.liepin.com"), any(Page.NavigateOptions.class));
        verify(cookieService).saveOrUpdateCookie(eq("liepin"), anyString(), eq("login success"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingPage", "closedPage", "missingEngine", "missingContext", "closedBrowser"})
    void liepinOnlyInitializationRejectsMissingOrClosedRuntimeObjects(String missing) {
        switch (missing) {
            case "missingPage" -> ReflectionTestUtils.setField(manager, "liepinPage", null);
            case "closedPage" -> when(page.isClosed()).thenReturn(true);
            case "missingEngine" -> ReflectionTestUtils.setField(manager, "playwright", null);
            case "missingContext" -> ReflectionTestUtils.setField(manager, "context", null);
            case "closedBrowser" -> ReflectionTestUtils.setField(manager, "browserClosed", true);
            default -> throw new AssertionError(missing);
        }
        assertFalse(manager.isInitialized());
    }

    @Test
    void allModeRequiresEverySelectedPageToRemainAvailable() {
        ReflectionTestUtils.setField(manager, "platformMode", "all");
        ReflectionTestUtils.setField(manager, "bossPage", mock(Page.class));
        ReflectionTestUtils.setField(manager, "job51Page", mock(Page.class));
        ReflectionTestUtils.setField(manager, "zhilianPage", mock(Page.class));
        assertTrue(manager.isInitialized());
        for (String field : List.of("bossPage", "liepinPage", "job51Page", "zhilianPage")) {
            Page selected = (Page) ReflectionTestUtils.getField(manager, field);
            ReflectionTestUtils.setField(manager, field, null);
            assertFalse(manager.isInitialized(), field);
            ReflectionTestUtils.setField(manager, field, selected);
            when(selected.isClosed()).thenReturn(true);
            assertFalse(manager.isInitialized(), field);
            when(selected.isClosed()).thenReturn(false);
        }
        assertTrue(manager.isInitialized());
    }

    @Test
    void disabledBossRefreshHasNoBrowserOrCookieSideEffects() {
        assertFalse(manager.refreshBossLoginStatus());
        assertNull(manager.getBossPage());
        verify(manager, never()).callOnPlaywright(any());
        verifyNoInteractions(page, context, engine, cookieService);
        assertTrue(changes.isEmpty());
    }

    @Test
    void transientUnknownPreservesStoredPositiveStateButReportsUnauthenticatedUntilConfirmed() {
        apply(LiepinLoginProbe.State.LOGGED_IN);
        assertTrue(manager.isLoggedIn("liepin"));
        changes.clear();

        apply(LiepinLoginProbe.State.UNKNOWN);

        assertEquals("UNKNOWN", manager.getLiepinLoginState());
        assertEquals("test UNKNOWN", manager.getLiepinLoginMessage());
        assertTrue(manager.getLoginStatus().get("liepin"));
        assertFalse(manager.isLoggedIn("liepin"));
        assertTrue(changes.stream().anyMatch(change -> "liepin".equals(change.platform())
                && !change.isLoggedIn() && "UNKNOWN".equals(change.state())));

        apply(LiepinLoginProbe.State.LOGGED_IN);
        apply(LiepinLoginProbe.State.LOGGED_IN);

        assertTrue(manager.isLoggedIn("liepin"));
        verify(cookieService, times(1)).saveOrUpdateCookie(eq("liepin"), anyString(), eq("login success"));
        assertSessionDataWasNotCleared();
        assertNoNavigation(page);
    }

    @Test
    void explicitLogoutNotifiesListenersWithoutClearingCookies() {
        apply(LiepinLoginProbe.State.LOGGED_IN);
        changes.clear();

        apply(LiepinLoginProbe.State.LOGGED_OUT);

        assertEquals("LOGGED_OUT", manager.getLiepinLoginState());
        assertEquals("test LOGGED_OUT", manager.getLiepinLoginMessage());
        assertFalse(manager.getLoginStatus().get("liepin"));
        assertFalse(manager.isLoggedIn("liepin"));
        assertTrue(changes.stream().anyMatch(change -> "liepin".equals(change.platform())
                && !change.isLoggedIn() && "LOGGED_OUT".equals(change.state())));
        verify(cookieService, times(1)).saveOrUpdateCookie(eq("liepin"), anyString(), eq("login success"));
        assertSessionDataWasNotCleared();
        assertNoNavigation(page);
    }

    @Test
    void browserClosePublishesLiepinUnknownAndDisabledStatusWithoutClearingCookies() {
        apply(LiepinLoginProbe.State.LOGGED_IN);
        changes.clear();

        ReflectionTestUtils.invokeMethod(manager, "markBrowserClosed");

        assertEquals("UNKNOWN", manager.getLiepinLoginState());
        assertTrue(manager.getLiepinLoginMessage().contains("已关闭"));
        assertFalse(manager.isInitialized());
        assertFalse(manager.isLoggedIn("liepin"));
        assertFalse(manager.getLoginStatus().get("liepin"));
        assertTrue(changes.stream().anyMatch(change -> "liepin".equals(change.platform())
                && !change.isLoggedIn() && "UNKNOWN".equals(change.state())));
        verify(cookieService, times(1)).saveOrUpdateCookie(eq("liepin"), anyString(), eq("login success"));
        assertSessionDataWasNotCleared();
        assertNoNavigation(page);
        verify(context, never()).close();
        verify(engine, never()).close();
    }

    @Test
    void readOnlyRefreshRebindsAnAuthenticatedPageFromTheSameContext() {
        Page otherPlatform = mock(Page.class);
        when(otherPlatform.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        Page authenticated = mock(Page.class);
        when(authenticated.url()).thenReturn("https://www.liepin.com/zhaopin/");
        header(authenticated, true);
        when(context.pages()).thenReturn(List.of(page, otherPlatform, authenticated));

        assertTrue(manager.refreshLiepinLoginStatus());

        assertSame(authenticated, manager.getLiepinPage());
        assertSame(context, manager.getContext());
        assertTrue(manager.isLoggedIn("liepin"));
        assertEquals("LOGGED_IN", manager.getLiepinLoginState());
        verify(authenticated).setDefaultTimeout(30_000);
        verify(authenticated).onFrameNavigated(any());
        verify(context, never()).newPage();
        verify(manager, never()).init();
        verify(manager, never()).ensureReady();
        verify(manager, never()).reopenBrowser();
        verify(cookieService, times(1)).saveOrUpdateCookie(eq("liepin"), anyString(), eq("login success"));
        assertSessionDataWasNotCleared();
        assertNoNavigation(page);
        assertNoNavigation(otherPlatform);
        assertNoNavigation(authenticated);
    }

    private void apply(LiepinLoginProbe.State state) {
        ReflectionTestUtils.invokeMethod(manager, "applyLiepinLoginResult",
                new LiepinLoginProbe.Result(state, "test " + state.name()));
    }

    private void setSinglePlatformRuntime(BrowserPlatformMode mode) {
        ReflectionTestUtils.setField(manager, "platformMode", modeValue(mode));
        for (String field : List.of("bossPage", "liepinPage", "job51Page", "zhilianPage")) {
            ReflectionTestUtils.setField(manager, field, null);
        }
        ReflectionTestUtils.setField(manager, selectedPageField(mode), page);
    }

    private static String selectedPageField(BrowserPlatformMode mode) {
        return switch (mode) {
            case BOSS -> "bossPage";
            case LIEPIN -> "liepinPage";
            case JOB51 -> "job51Page";
            case ZHILIAN -> "zhilianPage";
            case ALL -> throw new IllegalArgumentException("A single platform mode is required");
        };
    }

    private static String modeValue(BrowserPlatformMode mode) {
        return mode == BrowserPlatformMode.JOB51 ? "51job" : mode.name().toLowerCase(Locale.ROOT);
    }

    private void header(Page target, boolean authenticated) {
        Locator user = mock(Locator.class);
        Locator login = mock(Locator.class);
        when(target.locator(LiepinLoginProbe.USER_SELECTOR)).thenReturn(user);
        when(target.locator(LiepinLoginProbe.LOGIN_SELECTOR)).thenReturn(login);
        when(user.first()).thenReturn(user);
        when(login.first()).thenReturn(login);
        when(user.isVisible()).thenReturn(authenticated);
    }

    private void assertSessionDataWasNotCleared() {
        verify(context, never()).clearCookies();
        verify(cookieService, never()).clearCookieByPlatform(anyString(), anyString());
        verify(cookieService, never()).deleteCookie(anyString());
        verify(cookieService, never()).getCookieByPlatform(anyString());
    }

    private static void assertNoNavigation(Page target) {
        verify(target, never()).navigate(anyString());
        verify(target, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(target, never()).reload();
        verify(target, never()).reload(any(Page.ReloadOptions.class));
    }
}
