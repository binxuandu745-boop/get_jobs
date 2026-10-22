package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BossPageSafetyTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.zhipin.com/web/user/safe/verify-slider",
            "https://www.zhipin.com/web/passport/zp/verify.html",
            "https://www.zhipin.com/web/passport/zp/security.html",
            "https://www.zhipin.com/security-check"
    })
    void verificationUrlStopsWithoutFurtherPageOperations(String url) {
        Page page = mock(Page.class);
        when(page.url()).thenReturn(url);
        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossPageSafety.requireUsablePage(page));
        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(page, never()).locator(anyString());
        verify(page, never()).waitForTimeout(anyDouble());
        verify(page, never()).navigate(anyString());
    }

    @Test
    void visibleVerificationContentOnOrdinaryUrlAlsoStops() {
        Page page = normalPage(true);
        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossPageSafety.requireUsablePage(page));
        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(page, never()).waitForTimeout(anyDouble());
        verify(page, never()).navigate(anyString());
    }

    @Test
    void ordinaryPageDoesNotPauseOrNavigate() {
        Page page = normalPage(false);
        assertDoesNotThrow(() -> BossPageSafety.requireUsablePage(page));
        verify(page, never()).waitForTimeout(anyDouble());
        verify(page, never()).navigate(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"about:blank", "https://www.zhipin.com/web/geek/jobs", ""})
    void noVisibleVerificationWhileLoadingOrMissingDoesNotPause(String url) {
        Page page = normalPage(false);
        when(page.url()).thenReturn(url);

        assertDoesNotThrow(() -> BossPageSafety.requireUsablePage(page));

        verify(page, never()).waitForTimeout(anyDouble());
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void missingPageStopsWithoutRechecks() {
        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossPageSafety.requireUsablePage(null));

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
    }

    @Test
    void closedPageStopsAsReadFailureNotConfirmedVerification() {
        Page page = mock(Page.class);
        when(page.isClosed()).thenReturn(true);
        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossPageSafety.requireUsablePage(page));
        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        verify(page, times(1)).isClosed();
        verify(page, never()).url();
        verify(page, never()).waitForTimeout(anyDouble());
        verifyNoMoreInteractions(page);
    }

    @Test
    void oneUrlProbeFailureRecoversAfterReadonlyRecheck() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("temporary navigation failure"))
                .thenReturn("https://www.zhipin.com/web/geek/jobs");

        assertDoesNotThrow(() -> BossPageSafety.requireUsablePage(page));

        verify(page, times(2)).url();
        verify(page, times(1)).waitForTimeout(500);
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void oneVisibilityProbeFailureRecoversAfterReadonlyRecheck() {
        Page page = normalPage(false);
        Locator verification = page.locator(BossPageSafety.VERIFICATION_SELECTOR);
        when(verification.isVisible()).thenThrow(new IllegalStateException("temporary DOM failure"))
                .thenReturn(false);
        clearInvocations(page);

        assertDoesNotThrow(() -> BossPageSafety.requireUsablePage(page));

        verify(verification, times(2)).isVisible();
        verify(page, times(1)).waitForTimeout(500);
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void threeFailedProbesPauseWithoutClaimingVerificationOrLeakingDetails() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("https://example.invalid/?token=secret"));

        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossPageSafety.requireUsablePage(page));

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        assertTrue(failure.getMessage().contains("尚未确认"));
        assertTrue(failure.getMessage().contains("持续失败"));
        assertTrue(failure.getMessage().contains("3 次"));
        assertTrue(failure.getMessage().contains("IllegalStateException"));
        assertFalse(failure.getMessage().contains("token"));
        assertFalse(failure.getMessage().contains("example.invalid"));
        verify(page, times(3)).url();
        verify(page, times(2)).waitForTimeout(500);
        verify(page, never()).locator(anyString());
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void verificationUrlAppearingDuringRecheckStopsImmediately() {
        Page page = mock(Page.class);
        when(page.url()).thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn("https://www.zhipin.com/web/user/safe/verify-slider");

        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossPageSafety.requireUsablePage(page));

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        var operations = inOrder(page);
        operations.verify(page).isClosed();
        operations.verify(page).url();
        operations.verify(page).waitForTimeout(500);
        operations.verify(page).isClosed();
        operations.verify(page).url();
        operations.verifyNoMoreInteractions();
    }

    @Test
    void verificationContentAppearingDuringRecheckStopsWithoutAnotherWait() {
        Page page = normalPage(false);
        Locator verification = page.locator(BossPageSafety.VERIFICATION_SELECTOR);
        when(verification.isVisible()).thenThrow(new IllegalStateException("temporary DOM failure"))
                .thenReturn(true);
        clearInvocations(page);

        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossPageSafety.requireUsablePage(page));

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(verification, times(2)).isVisible();
        verify(page, times(1)).waitForTimeout(500);
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void transientWaitFailureStillUsesBoundedReadonlyRecheck() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn("https://www.zhipin.com/web/geek/jobs");
        doThrow(new IllegalStateException("temporary wait failure")).when(page).waitForTimeout(500);

        assertDoesNotThrow(() -> BossPageSafety.requireUsablePage(page));

        verify(page, times(2)).url();
        verify(page, times(1)).waitForTimeout(500);
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void pageClosingBetweenRechecksStopsBeforeAnotherUrlProbe() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("temporary failure"));
        when(page.isClosed()).thenReturn(false, true);

        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossPageSafety.requireUsablePage(page));

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        verify(page, times(2)).isClosed();
        verify(page, times(1)).url();
        verify(page, times(1)).waitForTimeout(500);
        verify(page, never()).locator(anyString());
    }

    @Test
    void existingInterruptionCancelsBeforeAnyPageOperationAndKeepsInterruptFlag() {
        Page page = mock(Page.class);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> BossPageSafety.requireUsablePage(page));
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(page);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionDuringRecheckWaitCancelsWithoutFurtherPageOperations() {
        Page page = normalPage(false);
        when(page.url()).thenThrow(new IllegalStateException("temporary failure"));
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(page).waitForTimeout(500);

        try {
            assertThrows(CancellationException.class, () -> BossPageSafety.requireUsablePage(page));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(page, times(1)).isClosed();
            verify(page, times(1)).url();
            verify(page, times(1)).waitForTimeout(500);
            verify(page, never()).locator(anyString());
            verify(page, never()).navigate(anyString());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationFromProbePropagatesWithoutWrappingOrRechecking() {
        Page page = mock(Page.class);
        CancellationException cancellation = new CancellationException("cancelled");
        when(page.url()).thenThrow(cancellation);

        var failure = assertThrows(CancellationException.class,
                () -> BossPageSafety.requireUsablePage(page));

        assertSame(cancellation, failure);
        verify(page, times(1)).url();
        verify(page, never()).waitForTimeout(anyDouble());
        verify(page, never()).locator(anyString());
    }

    @Test
    void verificationWordsInQueryDoNotMakeAnOrdinaryPathAVerificationUrl() {
        assertFalse(BossPageSafety.isVerificationUrl(
                "https://www.zhipin.com/web/geek/jobs?query=security-check"));
    }

    private Page normalPage(boolean verificationVisible) {
        Page page = mock(Page.class);
        Locator verification = mock(Locator.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        when(verification.isVisible()).thenReturn(verificationVisible);
        return page;
    }
}
