package com.getjobs.worker.liepin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LiepinPageSafetyTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "https://safe.liepin.com/intercept/ip/captcha/dispatch",
            "https://safe.liepin.com/intercept/ip/captcha/dispatch/?sourceUrl=secret",
            "https://SAFE.LIEPIN.COM/intercept/ip/captcha/dispatch?__xxx=secret"
    })
    void preciseLiepinVerificationRouteStopsWithoutFurtherPageOperations(String url) {
        Page page = mock(Page.class);
        when(page.url()).thenReturn(url);

        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> LiepinPageSafety.requireUsablePage(page));

        assertEquals(LiepinTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        assertFalse(failure.getMessage().contains("secret"));
        verify(page, never()).locator(anyString());
        verify(page, never()).waitForTimeout(anyDouble());
        verify(page, never()).navigate(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.liepin.com/zhaopin/?key=安全验证&redirect=/intercept/ip/captcha/dispatch",
            "https://safe.liepin.com/jobs?key=captcha&redirect=/intercept/ip/captcha/dispatch",
            "https://safe.liepin.com/intercept/ip/captcha/dispatch-job",
            "https://safe.liepin.com/jobs/intercept/ip/captcha/dispatch",
            "https://example.invalid/intercept/ip/captcha/dispatch",
            "https://safe.liepin.com.example.invalid/intercept/ip/captcha/dispatch",
            "https://www.liepin.com/zhaopin/#/intercept/ip/captcha/dispatch",
            "about:blank", "", "https://bad host/"
    })
    void ordinaryLoadingAndQueryWordsAreNotVerificationEvidence(String url) {
        Page page = normalPage(false);
        when(page.url()).thenReturn(url);

        assertDoesNotThrow(() -> LiepinPageSafety.requireUsablePage(page));

        verify(page, never()).waitForTimeout(anyDouble());
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void visibleCaptchaOnOrdinaryUrlStopsWithoutNavigation() {
        Page page = normalPage(true);

        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> LiepinPageSafety.requireUsablePage(page));

        assertEquals(LiepinTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(page, never()).waitForTimeout(anyDouble());
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void missingPagePausesAsUnavailableWithoutClaimingCaptcha() {
        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> LiepinPageSafety.requireUsablePage(null));

        assertEquals(LiepinTaskPausedException.Reason.PAGE_UNAVAILABLE, failure.getReason());
        assertTrue(failure.getMessage().contains("尚未确认"));
    }

    @Test
    void closedPagePausesImmediatelyWithoutReadOrWait() {
        Page page = mock(Page.class);
        when(page.isClosed()).thenReturn(true);

        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> LiepinPageSafety.requireUsablePage(page));

        assertEquals(LiepinTaskPausedException.Reason.PAGE_UNAVAILABLE, failure.getReason());
        verify(page).isClosed();
        verifyNoMoreInteractions(page);
    }

    @Test
    void transientUrlFailureRecoversAfterOneReadonlyRecheck() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("temporary navigation"))
                .thenReturn("https://www.liepin.com/zhaopin/");

        assertDoesNotThrow(() -> LiepinPageSafety.requireUsablePage(page));

        verify(page, times(2)).url();
        verify(page).waitForTimeout(500);
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void transientVisibilityFailureRecoversAfterOneReadonlyRecheck() {
        Page page = normalPage(false);
        Locator verification = page.locator(LiepinPageSafety.VERIFICATION_SELECTOR);
        when(verification.isVisible()).thenThrow(new IllegalStateException("temporary DOM"))
                .thenReturn(false);

        assertDoesNotThrow(() -> LiepinPageSafety.requireUsablePage(page));

        verify(verification, times(2)).isVisible();
        verify(page).waitForTimeout(500);
        verify(page, never()).navigate(anyString());
    }

    @Test
    void threeFailedProbesPauseAsTechnicalFailureAndDoNotLeakExceptionContent() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("https://example.invalid/?token=secret body"));

        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> LiepinPageSafety.requireUsablePage(page));

        assertEquals(LiepinTaskPausedException.Reason.READ_FAILED, failure.getReason());
        assertTrue(failure.getMessage().contains("尚未确认"));
        assertTrue(failure.getMessage().contains("IllegalStateException"));
        assertFalse(failure.getMessage().contains("token"));
        assertFalse(failure.getMessage().contains("example.invalid"));
        assertFalse(failure.getMessage().contains("body"));
        assertNull(failure.getCause());
        verify(page, times(3)).url();
        verify(page, times(2)).waitForTimeout(500);
        verify(page, never()).locator(anyString());
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void verificationFoundDuringRecoveryStopsInsteadOfRetryingMore() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("temporary navigation"))
                .thenReturn("https://safe.liepin.com/intercept/ip/captcha/dispatch");

        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> LiepinPageSafety.requireUsablePage(page));

        assertEquals(LiepinTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(page, times(2)).url();
        verify(page).waitForTimeout(500);
        verify(page, never()).locator(anyString());
    }

    @Test
    void failedRecoveryWaitStillHasOnlyBoundedReadonlyProbes() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("temporary navigation"))
                .thenReturn("https://www.liepin.com/zhaopin/");
        doThrow(new IllegalStateException("temporary wait")).when(page).waitForTimeout(500);

        assertDoesNotThrow(() -> LiepinPageSafety.requireUsablePage(page));

        verify(page, times(2)).url();
        verify(page).waitForTimeout(500);
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void pageClosingBetweenRechecksStopsBeforeAnotherUrlRead() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("temporary navigation"));
        when(page.isClosed()).thenReturn(false, true);

        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> LiepinPageSafety.requireUsablePage(page));

        assertEquals(LiepinTaskPausedException.Reason.PAGE_UNAVAILABLE, failure.getReason());
        verify(page, times(2)).isClosed();
        verify(page, times(1)).url();
        verify(page).waitForTimeout(500);
    }

    @Test
    void interruptionCancelsBeforeAnyPageOperationAndPreservesFlag() {
        Page page = mock(Page.class);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> LiepinPageSafety.requireUsablePage(page));
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(page);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationDuringRecoveryWaitStopsBeforeFurtherProbe() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("temporary navigation"));
        AtomicBoolean stopped = new AtomicBoolean();
        doAnswer(invocation -> {
            stopped.set(true);
            return null;
        }).when(page).waitForTimeout(500);

        assertThrows(CancellationException.class, () -> LiepinPageSafety.requireUsablePage(page, () -> {
            if (stopped.get()) throw new CancellationException("用户取消任务");
        }));

        verify(page, times(1)).url();
        verify(page, times(1)).isClosed();
        verify(page).waitForTimeout(500);
        verify(page, never()).locator(anyString());
    }

    @Test
    void cancellationFromProbeIsPassedThroughWithoutWrappingOrRetry() {
        Page page = mock(Page.class);
        CancellationException cancellation = new CancellationException("用户取消任务");
        when(page.url()).thenThrow(cancellation);

        var failure = assertThrows(CancellationException.class,
                () -> LiepinPageSafety.requireUsablePage(page));

        assertSame(cancellation, failure);
        verify(page, never()).waitForTimeout(anyDouble());
        verify(page, never()).locator(anyString());
    }

    private static Page normalPage(boolean verificationVisible) {
        Page page = mock(Page.class);
        Locator verification = mock(Locator.class);
        when(page.url()).thenReturn("https://www.liepin.com/zhaopin/");
        when(page.locator(LiepinPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        when(verification.isVisible()).thenReturn(verificationVisible);
        return page;
    }
}
