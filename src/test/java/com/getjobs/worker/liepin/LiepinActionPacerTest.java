package com.getjobs.worker.liepin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/** All waits and pages are mocked: no browser, sleep, or remote action is involved. */
class LiepinActionPacerTest {
    @RepeatedTest(3)
    void independentConstructionDefaultsToThreeToFiveSecondsInShortSlices() {
        Page page = usablePage();

        new LiepinActionPacer().pauseBeforeAction(page, "打开岗位", () -> { });

        assertDefaultSlices(page);
    }

    @ParameterizedTest
    @CsvSource({"1,1", "250,250", "251,251", "60000,60000"})
    void validConfiguredRangePreservesTotalAndShortFinalSlice(int min, int max) {
        Page page = usablePage();

        new LiepinActionPacer(min, max).pauseBeforeAction(page, "打开岗位", () -> { });

        assertEquals(min, capturedSlices(page).stream().mapToDouble(Double::doubleValue).sum());
    }

    @ParameterizedTest
    @CsvSource({"0,5000", "-1,5000", "5000,3000", "3000,60001", "2147483647,2147483647"})
    void invalidRangesFallBackToDefaults(int min, int max) {
        Page page = usablePage();

        new LiepinActionPacer(min, max).pauseBeforeAction(page, "打开岗位", () -> { });

        assertDefaultSlices(page);
    }

    @Test
    void initialCancellationPreventsAllPageOperations() {
        Page page = mock(Page.class);
        CancellationException cancellation = new CancellationException("用户取消任务");

        var failure = assertThrows(CancellationException.class,
                () -> new LiepinActionPacer().pauseBeforeAction(page, "打开岗位", () -> {
                    throw cancellation;
                }));

        assertSame(cancellation, failure);
        verifyNoInteractions(page);
    }

    @Test
    void cancellationAfterFirstSlicePreventsAnotherWait() {
        Page page = usablePage();
        AtomicBoolean stopped = new AtomicBoolean();
        doAnswer(invocation -> {
            stopped.set(true);
            return null;
        }).when(page).waitForTimeout(anyDouble());

        assertThrows(CancellationException.class,
                () -> new LiepinActionPacer().pauseBeforeAction(page, "发送招呼", () -> {
                    if (stopped.get()) throw new CancellationException("用户取消任务");
                }));

        verify(page, times(1)).waitForTimeout(250);
    }

    @Test
    void verificationBeforeFirstSlicePreventsWaiting() {
        Page page = usablePage();
        when(page.url()).thenReturn("https://safe.liepin.com/intercept/ip/captcha/dispatch");

        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> new LiepinActionPacer().pauseBeforeAction(page, "发送招呼", () -> { }));

        assertEquals(LiepinTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(page, never()).waitForTimeout(anyDouble());
    }

    @Test
    void verificationAppearingAfterFirstSlicePreventsAnotherWait() {
        Page page = usablePage();
        doAnswer(invocation -> {
            when(page.url()).thenReturn("https://safe.liepin.com/intercept/ip/captcha/dispatch");
            return null;
        }).when(page).waitForTimeout(anyDouble());

        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> new LiepinActionPacer().pauseBeforeAction(page, "发送招呼", () -> { }));

        assertEquals(LiepinTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(page, times(1)).waitForTimeout(250);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Object doesn't exist: frame@stale", "Execution context was destroyed",
            "Execution context destroyed"})
    void staleWaitFailureContinuesOnlyAfterSuccessfulSafetyAndStopChecks(String message) {
        Page page = usablePage();
        doThrow(new IllegalStateException(message)).doNothing().when(page).waitForTimeout(250);

        assertDoesNotThrow(() -> new LiepinActionPacer(500, 500)
                .pauseBeforeAction(page, "打开岗位", () -> { }));

        verify(page, times(2)).waitForTimeout(250);
        verify(page, atLeast(4)).url();
    }

    @Test
    void staleWaitCannotSwallowCancellation() {
        Page page = usablePage();
        AtomicBoolean stopped = new AtomicBoolean();
        doAnswer(invocation -> {
            stopped.set(true);
            throw new IllegalStateException("Object doesn't exist: frame@stale");
        }).when(page).waitForTimeout(250);

        assertThrows(CancellationException.class,
                () -> new LiepinActionPacer(500, 500).pauseBeforeAction(page, "打开岗位", () -> {
                    if (stopped.get()) throw new CancellationException("用户取消任务");
                }));

        verify(page, times(1)).waitForTimeout(250);
    }

    @Test
    void unknownWaitFailurePausesWithoutClaimingCaptchaOrLeakingOriginalDetails() {
        Page page = usablePage();
        doThrow(new IllegalStateException("https://example.invalid/?token=secret body"))
                .when(page).waitForTimeout(250);

        var failure = assertThrows(LiepinTaskPausedException.class,
                () -> new LiepinActionPacer(500, 500).pauseBeforeAction(page, "打开岗位", () -> { }));

        assertEquals(LiepinTaskPausedException.Reason.READ_FAILED, failure.getReason());
        assertTrue(failure.getMessage().contains("尚未确认"));
        assertTrue(failure.getMessage().contains("IllegalStateException"));
        assertFalse(failure.getMessage().contains("token"));
        assertFalse(failure.getMessage().contains("example.invalid"));
        assertFalse(failure.getMessage().contains("body"));
        assertNull(failure.getCause());
        verify(page, times(1)).waitForTimeout(250);
    }

    private static void assertDefaultSlices(Page page) {
        double total = capturedSlices(page).stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(total >= 3000 && total <= 5000, "default total: " + total);
    }

    private static java.util.List<Double> capturedSlices(Page page) {
        ArgumentCaptor<Double> slices = ArgumentCaptor.forClass(Double.class);
        verify(page, atLeastOnce()).waitForTimeout(slices.capture());
        assertTrue(slices.getAllValues().stream().allMatch(slice -> slice > 0 && slice <= 250));
        return slices.getAllValues();
    }

    private static Page usablePage() {
        Page page = mock(Page.class);
        Locator verification = mock(Locator.class);
        when(page.url()).thenReturn("https://www.liepin.com/zhaopin/");
        when(page.locator(LiepinPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        when(verification.isVisible()).thenReturn(false);
        return page;
    }
}
