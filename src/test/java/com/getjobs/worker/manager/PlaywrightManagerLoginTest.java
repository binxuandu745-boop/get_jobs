package com.getjobs.worker.manager;

import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaywrightManagerLoginTest {
    private PlaywrightManager manager;
    private Page page;
    private BrowserContext context;
    private CookieService cookieService;

    @BeforeEach
    void setUp() {
        manager = new PlaywrightManager();
        ReflectionTestUtils.setField(manager, "bossOnly", true);
        page = mock(Page.class);
        context = mock(BrowserContext.class);
        cookieService = mock(CookieService.class);
        ReflectionTestUtils.setField(manager, "bossPage", page);
        ReflectionTestUtils.setField(manager, "context", context);
        ReflectionTestUtils.setField(manager, "playwright", mock(Playwright.class));
        ReflectionTestUtils.setField(manager, "cookieService", cookieService);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(context.pages()).thenReturn(List.of(page));
        header(page, false);
    }

    private void header(Page target, boolean authenticated) {
        Locator user = mock(Locator.class);
        Locator login = mock(Locator.class);
        when(target.locator(BossLoginProbe.USER_SELECTOR)).thenReturn(user);
        when(target.locator(BossLoginProbe.LOGIN_SELECTOR)).thenReturn(login);
        when(user.first()).thenReturn(user);
        when(login.first()).thenReturn(login);
        when(user.isVisible()).thenReturn(authenticated);
    }

    private void apply(BossLoginProbe.State state) {
        ReflectionTestUtils.invokeMethod(manager, "applyBossLoginResult",
                new BossLoginProbe.Result(state, "test", "pending"));
    }

    @Test
    void unknownDoesNotClearSessionOrNavigate() {
        apply(BossLoginProbe.State.LOGGED_IN);
        apply(BossLoginProbe.State.UNKNOWN);
        assertEquals("UNKNOWN", manager.getBossLoginState());
        assertFalse(manager.isLoggedIn("boss"));
        assertTrue(manager.getLoginStatus().get("boss"));
        verify(context, never()).clearCookies();
        verify(page, never()).navigate(anyString());
    }

    @Test
    void settingLogoutStatusHasNoBrowserSideEffects() {
        manager.setLoginStatus("boss", false);
        assertFalse(manager.isLoggedIn("boss"));
        verifyNoInteractions(page, context, cookieService);
    }

    @Test
    void positiveDetectionSavesOnlyOnceAcrossTransientUnknown() {
        apply(BossLoginProbe.State.LOGGED_IN);
        apply(BossLoginProbe.State.UNKNOWN);
        apply(BossLoginProbe.State.LOGGED_IN);
        assertTrue(manager.isLoggedIn("boss"));
        verify(cookieService, times(1)).saveOrUpdateCookie(eq("boss"), anyString(), eq("login success"));
    }

    @Test
    void startupNeverImportsOldDatabaseCookies() {
        header(page, true);
        ReflectionTestUtils.invokeMethod(manager, "setupBossPlatform");
        assertTrue(manager.isLoggedIn("boss"));
        verify(cookieService, never()).getCookieByPlatform("boss");
        verify(context, never()).addCookies(anyList());
        verify(context, never()).clearCookies();
        verify(page).navigate(eq("https://www.zhipin.com/web/geek/jobs"), any(Page.NavigateOptions.class));
    }

    @Test
    void loginInAnotherOwnedTabIsRecognizedAndRebound() {
        Page other = mock(Page.class);
        when(other.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        header(other, true);
        when(context.pages()).thenReturn(List.of(page, other));
        assertTrue(manager.refreshBossLoginStatus());
        assertSame(other, manager.getBossPage());
        verify(page, never()).navigate(anyString());
        verify(other, never()).navigate(anyString());
    }

    @Test
    void gracefulCloseRetainsStoredCookiesAndClearsOnlyRuntimeReferences() {
        manager.destroy();
        verify(context).close();
        verify(context, never()).clearCookies();
        verifyNoInteractions(cookieService);
        assertFalse(manager.isInitialized());
        assertEquals("UNKNOWN", manager.getBossLoginState());
        assertNull(manager.getContext());
    }
}
