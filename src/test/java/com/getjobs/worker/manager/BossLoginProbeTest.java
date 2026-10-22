package com.getjobs.worker.manager;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.getjobs.worker.manager.BossLoginProbe.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossLoginProbeTest {
    private Page page;
    private Locator user;
    private Locator login;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        user = mock(Locator.class);
        login = mock(Locator.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.locator(BossLoginProbe.USER_SELECTOR)).thenReturn(user);
        when(page.locator(BossLoginProbe.LOGIN_SELECTOR)).thenReturn(login);
        when(user.first()).thenReturn(user);
        when(login.first()).thenReturn(login);
    }

    @Test
    void missingHeaderIsUnknownNotLogout() {
        assertEquals(UNKNOWN, BossLoginProbe.detect(page).state());
        verify(page, never()).navigate(anyString());
    }

    @Test
    void visibleUserWithoutLoginEntryIsAuthenticated() {
        when(user.isVisible()).thenReturn(true);
        assertEquals(LOGGED_IN, BossLoginProbe.detect(page).state());
        assertFalse(BossLoginProbe.USER_SELECTOR.contains("li.nav-figure"));
    }

    @Test
    void explicitLoginEntryIsLoggedOut() {
        when(login.isVisible()).thenReturn(true);
        when(login.textContent(any(Locator.TextContentOptions.class))).thenReturn("登录/注册");
        assertEquals(LOGGED_OUT, BossLoginProbe.detect(page).state());
        verify(page, never()).navigate(anyString());
    }

    @Test
    void unrelatedButtonDoesNotProveLogout() {
        when(login.isVisible()).thenReturn(true);
        when(login.textContent(any(Locator.TextContentOptions.class))).thenReturn("搜索");
        assertEquals(UNKNOWN, BossLoginProbe.detect(page).state());
    }

    @Test
    void conflictingHeaderSignalsAreUnknown() {
        when(user.isVisible()).thenReturn(true);
        when(login.isVisible()).thenReturn(true);
        when(login.textContent(any(Locator.TextContentOptions.class))).thenReturn("登录");
        assertEquals(UNKNOWN, BossLoginProbe.detect(page).state());
    }

    @ParameterizedTest
    @ValueSource(strings={"https://www.zhipin.com/web/user/", "https://www.zhipin.com/web/user/?ka=header-login"})
    void loginPageIsNotAcceptedAsAuthenticated(String url) {
        when(page.url()).thenReturn(url);
        when(user.isVisible()).thenReturn(true);
        assertEquals(LOGGED_OUT, BossLoginProbe.detect(page).state());
    }

    @ParameterizedTest
    @ValueSource(strings={"https://www.zhipin.com/web/user/safe/verify-slider",
            "https://www.zhipin.com/web/passport/zp/verify.html", "https://www.zhipin.com/security-check"})
    void verificationPageRequiresManualAction(String url) {
        when(page.url()).thenReturn(url);
        when(user.isVisible()).thenReturn(true);
        assertEquals(UNKNOWN, BossLoginProbe.detect(page).state());
        assertEquals("verification", BossLoginProbe.detect(page).pageType());
        verify(page, never()).locator(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings={"about:blank", "https://zhipin.com.example.invalid/web/geek/jobs", "https://example.invalid"})
    void otherPagesCannotProveBossAuthentication(String url) {
        when(page.url()).thenReturn(url);
        assertEquals(UNKNOWN, BossLoginProbe.detect(page).state());
        verify(page, never()).locator(anyString());
    }

    @Test
    void closedPageAndNullAreUnknown() {
        assertEquals(UNKNOWN, BossLoginProbe.detect(null).state());
        when(page.isClosed()).thenReturn(true);
        assertEquals("closed", BossLoginProbe.detect(page).pageType());
    }

    @Test
    void navigationExceptionIsUnknown() {
        when(user.isVisible()).thenThrow(new IllegalStateException("page changed"));
        assertEquals(UNKNOWN, BossLoginProbe.detect(page).state());
    }

    @Test
    void delayedHeaderUsesBoundedPolling() {
        when(user.isVisible()).thenReturn(false, false, true);
        assertEquals(LOGGED_IN, BossLoginProbe.awaitKnown(page, 16, 250).state());
        verify(page, times(2)).waitForTimeout(250);
        verify(user, never()).isVisible(any(Locator.IsVisibleOptions.class));
    }

    @Test
    void missingHeaderStopsAtProbeLimit() {
        assertEquals(UNKNOWN, BossLoginProbe.awaitKnown(page, 4, 250).state());
        verify(page, times(3)).waitForTimeout(250);
    }

    @Test
    void explicitLogoutNeedsNoWait() {
        when(page.url()).thenReturn("https://www.zhipin.com/web/user/");
        assertEquals(LOGGED_OUT, BossLoginProbe.awaitKnown(page, 16, 250).state());
        verify(page, never()).waitForTimeout(anyDouble());
    }

    @Test
    void closingDuringPollDoesNotThrowOrRequestLogin() {
        doThrow(new IllegalStateException("closed")).when(page).waitForTimeout(250);
        assertEquals(UNKNOWN, BossLoginProbe.awaitKnown(page, 16, 250).state());
        verify(page, never()).navigate(anyString());
    }
}
