package com.getjobs.worker.manager;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.regex.Pattern;

import static com.getjobs.worker.manager.LiepinLoginProbe.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiepinLoginProbeTest {
    private static final String JOBS_URL = "https://www.liepin.com/zhaopin/";
    private Page page;
    private Locator user;
    private Locator login;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        user = mock(Locator.class);
        login = mock(Locator.class);
        when(page.url()).thenReturn(JOBS_URL);
        when(page.locator(LiepinLoginProbe.USER_SELECTOR)).thenReturn(user);
        when(page.locator(LiepinLoginProbe.LOGIN_SELECTOR)).thenReturn(login);
        when(user.first()).thenReturn(user);
        when(login.first()).thenReturn(login);
    }

    @AfterEach
    void everyProbeIsStrictlyReadOnly() {
        verify(page, never()).navigate(anyString());
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page, never()).reload();
        verify(page, never()).reload(any(Page.ReloadOptions.class));
        verify(page, never()).context();
        verify(page, never()).getByText(anyString());
        verify(page, never()).getByText(anyString(), any(Page.GetByTextOptions.class));
        verify(page, never()).getByText(any(Pattern.class));
        verify(page, never()).getByText(any(Pattern.class), any(Page.GetByTextOptions.class));

        for (var invocation : mockingDetails(page).getInvocations()) {
            String method = invocation.getMethod().getName();
            assertTrue(Set.of("isClosed", "url", "locator").contains(method),
                    "Unexpected page operation: " + method);
            if ("locator".equals(method)) {
                assertEquals(1, invocation.getArguments().length);
                assertTrue(Set.of(LiepinLoginProbe.USER_SELECTOR, LiepinLoginProbe.LOGIN_SELECTOR)
                        .contains(invocation.getArgument(0)), "Unexpected login evidence selector");
            } else {
                assertEquals(0, invocation.getArguments().length);
            }
        }
        for (Locator evidence : new Locator[]{user, login}) {
            for (var invocation : mockingDetails(evidence).getInvocations()) {
                assertTrue(Set.of("first", "isVisible").contains(invocation.getMethod().getName()),
                        "Login evidence must only be checked for visibility");
                assertEquals(0, invocation.getArguments().length,
                        "Login evidence must not use waiting visibility options");
            }
        }
    }

    @Test
    void selectorsAreRestrictedToVisibleHeaderUserEvidenceAndLoginEntry() {
        assertEquals("#header-quick-menu-user-info:visible, img.header-quick-menu-user-photo:visible, "
                + ".header-quick-menu-user-photo:visible", LiepinLoginProbe.USER_SELECTOR);
        assertEquals("#header-quick-menu-login:visible", LiepinLoginProbe.LOGIN_SELECTOR);
        assertFalse(LiepinLoginProbe.USER_SELECTOR.contains("text"));
        assertFalse(LiepinLoginProbe.LOGIN_SELECTOR.contains("text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "#header-quick-menu-user-info:visible",
            "img.header-quick-menu-user-photo:visible",
            ".header-quick-menu-user-photo:visible"
    })
    void visibleUserMenuOrEitherAvatarIsPositiveEvidence(String selector) {
        assertTrue(LiepinLoginProbe.USER_SELECTOR.contains(selector));
        when(user.isVisible()).thenReturn(true);

        assertEquals(LOGGED_IN, LiepinLoginProbe.inspect(page).state());

        verify(page).locator(LiepinLoginProbe.USER_SELECTOR);
        verify(user).first();
        verify(user).isVisible();
        verify(page, never()).locator(LiepinLoginProbe.LOGIN_SELECTOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://liepin.com/",
            "https://www.liepin.com/zhaopin/",
            "https://m.liepin.com/jobs",
            "https://a.b.liepin.com/jobs",
            "http://www.liepin.com/zhaopin/",
            "https://WWW.LIEPIN.COM:443/zhaopin/"
    })
    void officialRootAndSubdomainsAllowPositiveEvidence(String url) {
        when(page.url()).thenReturn(url);
        when(user.isVisible()).thenReturn(true);

        assertEquals(LOGGED_IN, LiepinLoginProbe.inspect(page).state());
    }

    @Test
    void positiveUserEvidenceWinsOverVisibleLoginEntry() {
        when(user.isVisible()).thenReturn(true);
        when(login.isVisible()).thenReturn(true);

        assertEquals(LOGGED_IN, LiepinLoginProbe.inspect(page).state());

        verify(page, never()).locator(LiepinLoginProbe.LOGIN_SELECTOR);
        verifyNoInteractions(login);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.liepin.com/login",
            "https://www.liepin.com/login/qr",
            "https://www.liepin.com/user/login",
            "https://www.liepin.com/user/login/qr?token=url-secret"
    })
    void positiveUserEvidenceWinsOverLoginPath(String url) {
        when(page.url()).thenReturn(url);
        when(user.isVisible()).thenReturn(true);
        when(login.isVisible()).thenReturn(true);

        assertEquals(LOGGED_IN, LiepinLoginProbe.inspect(page).state());

        verify(user).isVisible();
        verify(page, never()).locator(LiepinLoginProbe.LOGIN_SELECTOR);
    }

    @Test
    void visibleHeaderLoginEntryProvesLoggedOutWithoutReadingText() {
        when(login.isVisible()).thenReturn(true);

        assertEquals(LOGGED_OUT, LiepinLoginProbe.inspect(page).state());

        verify(user).first();
        verify(user).isVisible();
        verify(login).first();
        verify(login).isVisible();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.liepin.com/login",
            "https://www.liepin.com/login/",
            "https://www.liepin.com/login/qr",
            "https://liepin.com/login?redirect=%2Fzhaopin",
            "https://www.liepin.com/user/login",
            "https://www.liepin.com/user/login/",
            "https://www.liepin.com/user/login/password",
            "https://m.liepin.com/user/login/?source=header"
    })
    void exactLoginPathOrItsChildrenProveLoggedOutWithoutHeader(String url) {
        when(page.url()).thenReturn(url);

        assertEquals(LOGGED_OUT, LiepinLoginProbe.inspect(page).state());

        verify(user).isVisible();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.liepin.com/loginhelp",
            "https://www.liepin.com/login-history",
            "https://www.liepin.com/login.html",
            "https://www.liepin.com/user/login-history",
            "https://www.liepin.com/user/loginhelp",
            "https://www.liepin.com/jobs/login",
            "https://www.liepin.com/zhaopin/?redirect=/login",
            "https://www.liepin.com/zhaopin/#/user/login"
    })
    void similarPathsQueryAndFragmentDoNotProveLogout(String url) {
        when(page.url()).thenReturn(url);

        assertEquals(UNKNOWN, LiepinLoginProbe.inspect(page).state());
    }

    @Test
    void missingOrLoadingHeaderIsUnknown() {
        assertEquals(UNKNOWN, LiepinLoginProbe.inspect(page).state());

        verify(user).isVisible();
        verify(login).isVisible();
    }

    @Test
    void absenceOfPositiveEvidenceIsNotCachedAsLogout() {
        when(user.isVisible()).thenReturn(false, true);

        assertEquals(UNKNOWN, LiepinLoginProbe.inspect(page).state());
        assertEquals(LOGGED_IN, LiepinLoginProbe.inspect(page).state());

        verify(user, times(2)).isVisible();
    }

    @Test
    void earlierPositiveEvidenceIsNotReusedWhenHeaderDisappears() {
        when(user.isVisible()).thenReturn(true, false);

        assertEquals(LOGGED_IN, LiepinLoginProbe.inspect(page).state());
        assertEquals(UNKNOWN, LiepinLoginProbe.inspect(page).state());
    }

    @Test
    void missingLocatorIsUnknownRatherThanAnUncaughtFailure() {
        when(page.locator(LiepinLoginProbe.USER_SELECTOR)).thenReturn(null);

        assertEquals(UNKNOWN, assertDoesNotThrow(() -> LiepinLoginProbe.inspect(page)).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://safe.liepin.com/intercept/ip/captcha/dispatch",
            "https://safe.liepin.com/intercept/ip/captcha/dispatch/?token=url-secret",
            "https://safe.liepin.com/",
            "https://safe.liepin.com/jobs",
            "https://safe.liepin.com/login",
            "http://SAFE.LIEPIN.COM/user/login/qr"
    })
    void everySafetyHostPathIsUnknownBeforeAnyHeaderRead(String url) {
        when(page.url()).thenReturn(url);
        when(user.isVisible()).thenReturn(true);
        when(login.isVisible()).thenReturn(true);

        var result = LiepinLoginProbe.inspect(page);

        assertEquals(UNKNOWN, result.state());
        assertNotNull(result.message());
        assertFalse(result.message().contains(url));
        assertFalse(result.message().contains("url-secret"));
        verify(page, never()).locator(anyString());
        verifyNoInteractions(user, login);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://liepin.com.evil.invalid/login",
            "https://www.liepin.com.evil.invalid/user/login",
            "https://evilliepin.com/login",
            "https://liepin.comevil.invalid/login",
            "https://example.invalid/?redirect=https://www.liepin.com/login",
            "https://liepin.com@evil.invalid/login",
            "https://safe.liepin.com.evil.invalid/login",
            "https://example.invalid/login"
    })
    void unrelatedOrSpoofedHostsCannotUsePositiveOrLogoutEvidence(String url) {
        when(page.url()).thenReturn(url);
        when(user.isVisible()).thenReturn(true);
        when(login.isVisible()).thenReturn(true);

        assertEquals(UNKNOWN, LiepinLoginProbe.inspect(page).state());

        verify(page, never()).locator(anyString());
        verifyNoInteractions(user, login);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "about:blank",
            "file:///www.liepin.com/login",
            "ftp://www.liepin.com/login",
            "javascript:location='https://www.liepin.com/login'",
            "data:text/html,https://www.liepin.com/login",
            "//www.liepin.com/login",
            "/login",
            "https://bad host/login?token=url-secret",
            "https:///login"
    })
    void nonHttpSchemesRelativeAndMalformedUrlsAreUnknown(String url) {
        when(page.url()).thenReturn(url);
        when(user.isVisible()).thenReturn(true);
        when(login.isVisible()).thenReturn(true);

        var result = assertDoesNotThrow(() -> LiepinLoginProbe.inspect(page));

        assertEquals(UNKNOWN, result.state());
        assertNotNull(result.message());
        assertFalse(result.message().contains("url-secret"));
        assertFalse(result.message().contains("bad host"));
        verify(page, never()).locator(anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n", " \r\n "})
    void missingOrBlankUrlIsUnknownWithoutReadingHeader(String url) {
        when(page.url()).thenReturn(url);
        when(user.isVisible()).thenReturn(true);

        assertEquals(UNKNOWN, LiepinLoginProbe.inspect(page).state());

        verify(page, never()).locator(anyString());
    }

    @Test
    void nullPageIsUnknownWithoutAccessingPage() {
        assertEquals(UNKNOWN, assertDoesNotThrow(() -> LiepinLoginProbe.inspect(null)).state());

        verifyNoInteractions(page, user, login);
    }

    @Test
    void closedPageIsUnknownBeforeUrlAndHeaderRead() {
        when(page.isClosed()).thenReturn(true);
        when(user.isVisible()).thenReturn(true);

        assertEquals(UNKNOWN, LiepinLoginProbe.inspect(page).state());

        verify(page).isClosed();
        verify(page, never()).url();
        verify(page, never()).locator(anyString());
        verifyNoInteractions(user, login);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "isClosed", "url", "userLocator", "userFirst", "userVisibility",
            "loginLocator", "loginFirst", "loginVisibility"
    })
    void readExceptionsReturnUnknownWithOnlySanitizedType(String operation) {
        RuntimeException failure = new IllegalStateException(
                "raw-secret https://www.liepin.com/login?token=url-secret",
                new IllegalArgumentException("cause-secret"));
        switch (operation) {
            case "isClosed" -> when(page.isClosed()).thenThrow(failure);
            case "url" -> when(page.url()).thenThrow(failure);
            case "userLocator" -> when(page.locator(LiepinLoginProbe.USER_SELECTOR)).thenThrow(failure);
            case "userFirst" -> when(user.first()).thenThrow(failure);
            case "userVisibility" -> when(user.isVisible()).thenThrow(failure);
            case "loginLocator" -> when(page.locator(LiepinLoginProbe.LOGIN_SELECTOR)).thenThrow(failure);
            case "loginFirst" -> when(login.first()).thenThrow(failure);
            case "loginVisibility" -> when(login.isVisible()).thenThrow(failure);
            default -> fail("Unexpected operation: " + operation);
        }

        var result = assertDoesNotThrow(() -> LiepinLoginProbe.inspect(page));

        assertEquals(UNKNOWN, result.state());
        assertNotNull(result.message());
        assertTrue(result.message().contains("IllegalStateException"));
        assertFalse(result.message().contains("java.lang."));
        assertFalse(result.message().contains("raw-secret"));
        assertFalse(result.message().contains("cause-secret"));
        assertFalse(result.message().contains("IllegalArgumentException"));
        assertFalse(result.message().contains("url-secret"));
        assertFalse(result.message().contains("https://"));
        assertFalse(result.message().contains("liepin.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IllegalArgumentException", "UnsupportedOperationException", "RuntimeException"})
    void sanitizedFailureTypeMatchesTheActualExceptionClass(String type) {
        RuntimeException failure = switch (type) {
            case "IllegalArgumentException" -> new IllegalArgumentException("raw-secret");
            case "UnsupportedOperationException" -> new UnsupportedOperationException("raw-secret");
            case "RuntimeException" -> new RuntimeException("raw-secret");
            default -> throw new AssertionError("Unexpected exception type: " + type);
        };
        when(page.url()).thenThrow(failure);

        var result = assertDoesNotThrow(() -> LiepinLoginProbe.inspect(page));

        assertEquals(UNKNOWN, result.state());
        assertTrue(result.message().contains(type));
        assertFalse(result.message().contains("raw-secret"));
        assertFalse(result.message().contains("java.lang."));
        verify(page, never()).locator(anyString());
    }
}
