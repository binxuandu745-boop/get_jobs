package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.PageDiagnosticResult;
import com.getjobs.application.screenshot.ScreenshotVisionClient;
import com.getjobs.application.screenshot.ScreenshotVisionException;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.ScreenshotScale;
import com.microsoft.playwright.options.ScreenshotType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One mocked image and one mocked model call; never opens a browser, saves a file or sends HTTP. */
class BossListPageDiagnosisTest {
    private static final byte[] IMAGE = {1, 2, 3};
    private static final String RESPONSE = "test-page-diagnostic-response";
    private Page page;
    private ScreenshotVisionClient vision;
    private BossListPageDiagnosis diagnosis;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        vision = mock(ScreenshotVisionClient.class);
        diagnosis = new BossListPageDiagnosis(vision);
        Thread caller = Thread.currentThread();
        when(page.evaluate(BossListPageDiagnosis.DOCUMENT_ORIGIN_SCRIPT)).thenAnswer(invocation -> {
            assertSame(caller, Thread.currentThread());
            return 12345.5;
        });
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenAnswer(invocation -> {
            assertSame(caller, Thread.currentThread());
            return IMAGE;
        });
        doAnswer(invocation -> {
            assertSame(caller, Thread.currentThread());
            Thread.sleep(1);
            return null;
        }).when(page).waitForTimeout(anyDouble());
    }

    @Test
    void capturesOneViewportAndRunsModelAwayFromPlaywrightThread() {
        Thread caller = Thread.currentThread();
        when(vision.diagnosePage(same(IMAGE), eq("image/png"))).thenAnswer(invocation -> {
            assertNotSame(caller, Thread.currentThread());
            assertTrue(Thread.currentThread().isVirtual());
            return RESPONSE;
        });
        PageDiagnosticResult parsed = mock(PageDiagnosticResult.class);
        try (MockedStatic<PageDiagnosticResult> parser = mockStatic(PageDiagnosticResult.class)) {
            parser.when(() -> PageDiagnosticResult.parse(RESPONSE)).thenReturn(parsed);
            assertSame(parsed, diagnosis.diagnose(page, () -> assertSame(caller, Thread.currentThread())));
            parser.verify(() -> PageDiagnosticResult.parse(RESPONSE), times(1));
        }

        ArgumentCaptor<Page.ScreenshotOptions> options = ArgumentCaptor.forClass(Page.ScreenshotOptions.class);
        verify(page, times(1)).screenshot(options.capture());
        assertEquals(Boolean.FALSE, options.getValue().fullPage);
        assertEquals(ScreenshotType.PNG, options.getValue().type);
        assertEquals(ScreenshotScale.CSS, options.getValue().scale);
        assertTrue(options.getValue().timeout > 0 && options.getValue().timeout <= 5000);
        assertNull(options.getValue().path);
        verify(page, times(4)).evaluate(BossListPageDiagnosis.DOCUMENT_ORIGIN_SCRIPT);
        verify(vision, times(1)).diagnosePage(same(IMAGE), eq("image/png"));
        verify(page, never()).navigate(anyString());
        verify(page, never()).mouse();
        verify(page, never()).reload();
    }

    @Test
    void preexistingCancellationStopsBeforeAnyBrowserOrModelCall() {
        CancellationException cancelled = new CancellationException("cancelled");
        assertSame(cancelled, assertThrows(CancellationException.class,
                () -> diagnosis.diagnose(page, () -> { throw cancelled; })));
        verifyNoInteractions(page, vision);
    }

    @Test
    void safetyVerificationWhileModelRunsPropagatesAndInterruptsRequest() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = blockingModel(started);
        BossTaskPausedException stopped = BossTaskPausedException.verificationRequired();

        assertSame(stopped, assertThrows(BossTaskPausedException.class,
                () -> diagnosis.diagnose(page, () -> {
                    if (started.getCount() == 0) throw stopped;
                })));

        assertTrue(interrupted.await(1, TimeUnit.SECONDS), "unfinished HTTP work must be interrupted");
        verify(page, times(1)).screenshot(any(Page.ScreenshotOptions.class));
        verify(vision, times(1)).diagnosePage(same(IMAGE), eq("image/png"));
    }

    @Test
    void cancellationWhileModelRunsPropagatesAndInterruptsRequest() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = blockingModel(started);
        CancellationException stopped = new CancellationException("cancelled");

        assertSame(stopped, assertThrows(CancellationException.class,
                () -> diagnosis.diagnose(page, () -> {
                    if (started.getCount() == 0) throw stopped;
                })));

        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        verify(vision, times(1)).diagnosePage(same(IMAGE), eq("image/png"));
    }

    @Test
    void sameUrlReloadDuringModelWorkRejectsTheOldScreenshot() {
        AtomicReference<Double> origin = new AtomicReference<>(12345.5);
        when(page.evaluate(BossListPageDiagnosis.DOCUMENT_ORIGIN_SCRIPT)).thenAnswer(ignored -> origin.get());
        when(vision.diagnosePage(same(IMAGE), eq("image/png"))).thenAnswer(ignored -> {
            origin.set(45678.0);
            return RESPONSE;
        });
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> diagnosis.diagnose(page, () -> { }));

        assertEquals("PAGE_DIAGNOSIS_DOCUMENT_CHANGED", failure.getCode());
        verify(vision, times(1)).diagnosePage(same(IMAGE), eq("image/png"));
    }

    @Test
    void reloadWhileTakingScreenshotPreventsCallingTheModel() {
        when(page.evaluate(BossListPageDiagnosis.DOCUMENT_ORIGIN_SCRIPT)).thenReturn(12345.5, 67890.0);
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> diagnosis.diagnose(page, () -> { }));

        assertEquals("PAGE_DIAGNOSIS_DOCUMENT_CHANGED", failure.getCode());
        verifyNoInteractions(vision);
    }

    @Test
    void missingDocumentOriginIsNotTreatedAsStable() {
        when(page.evaluate(BossListPageDiagnosis.DOCUMENT_ORIGIN_SCRIPT)).thenReturn(null);
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> diagnosis.diagnose(page, () -> { }));

        assertEquals("PAGE_DIAGNOSIS_DOCUMENT_CHANGED", failure.getCode());
        verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
        verifyNoInteractions(vision);
    }

    @Test
    void timeoutInterruptsAnUnfinishedModelWithoutRetrying() throws InterruptedException {
        diagnosis = new BossListPageDiagnosis(vision, Duration.ofMillis(150));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = blockingModel(started);

        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> diagnosis.diagnose(page, () -> { }));

        assertEquals("PAGE_DIAGNOSIS_TIMEOUT", failure.getCode());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        verify(page, times(1)).screenshot(any(Page.ScreenshotOptions.class));
        verify(vision, times(1)).diagnosePage(same(IMAGE), eq("image/png"));
    }

    @Test
    void captureFailureIsSafeAndNeverCallsTheModel() {
        when(page.screenshot(any(Page.ScreenshotOptions.class)))
                .thenThrow(new TimeoutError("raw-private-url?token=secret"));
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> diagnosis.diagnose(page, () -> { }));

        assertEquals("PAGE_DIAGNOSIS_CAPTURE_FAILED", failure.getCode());
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        verify(page, times(1)).screenshot(any(Page.ScreenshotOptions.class));
        verifyNoInteractions(vision);
    }

    @Test
    void modelFailureDoesNotExposeRawProviderText() {
        when(vision.diagnosePage(same(IMAGE), eq("image/png")))
                .thenThrow(new IllegalStateException("raw model secret"));
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> diagnosis.diagnose(page, () -> { }));

        assertEquals("PAGE_DIAGNOSIS_FAILED", failure.getCode());
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        verify(vision, times(1)).diagnosePage(same(IMAGE), eq("image/png"));
    }

    @Test
    void safeModelConfigurationErrorRemainsActionable() {
        ScreenshotVisionException missing = new ScreenshotVisionException("MISSING_CONFIG", "请配置截图模型");
        when(vision.diagnosePage(same(IMAGE), eq("image/png"))).thenThrow(missing);
        assertSame(missing, assertThrows(ScreenshotVisionException.class,
                () -> diagnosis.diagnose(page, () -> { })));
    }

    @Test
    void invalidModelJsonIsRejectedByTheParser() {
        when(vision.diagnosePage(same(IMAGE), eq("image/png"))).thenReturn(RESPONSE);
        ScreenshotVisionException invalid = new ScreenshotVisionException("INVALID_PAGE_DIAGNOSIS", "无效诊断响应");
        try (MockedStatic<PageDiagnosticResult> parser = mockStatic(PageDiagnosticResult.class)) {
            parser.when(() -> PageDiagnosticResult.parse(RESPONSE)).thenThrow(invalid);
            assertSame(invalid, assertThrows(ScreenshotVisionException.class,
                    () -> diagnosis.diagnose(page, () -> { })));
        }
    }

    @Test
    void browserFailureWhileWaitingInterruptsTheModelAndRemainsSafe() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = blockingModel(started);
        doAnswer(ignored -> {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            throw new IllegalStateException("raw browser secret");
        }).when(page).waitForTimeout(anyDouble());

        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> diagnosis.diagnose(page, () -> { }));

        assertEquals("PAGE_DIAGNOSIS_FAILED", failure.getCode());
        assertFalse(failure.getMessage().contains("secret"));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    private CountDownLatch blockingModel(CountDownLatch started) {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(vision.diagnosePage(same(IMAGE), eq("image/png"))).thenAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return RESPONSE;
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw cancelled;
            }
        });
        return interrupted;
    }
}
