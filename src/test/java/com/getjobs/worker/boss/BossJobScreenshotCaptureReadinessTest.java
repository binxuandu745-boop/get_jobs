package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.OfflineScreenshotService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mocked browser probes and waits only; the production PageSurface and retry policy are exercised. */
class BossJobScreenshotCaptureReadinessTest {
    private static final String READY = script("READINESS_SCRIPT");
    private static final String INIT = script("INIT_SCRIPT");
    private static final String MEASURE = script("MEASURE_SCRIPT");
    private static final String RESTORE = script("RESTORE_SCRIPT");
    private Page page;
    private AtomicLong waited;
    private AtomicReference<String> url;
    private byte[] firstImage;

    @BeforeEach
    void setUp() throws Exception {
        page = mock(Page.class);
        waited = new AtomicLong();
        url = new AtomicReference<>("https://www.zhipin.com/job_detail/expected.html");
        when(page.url()).thenAnswer(ignored -> url.get());
        when(page.locator(anyString())).thenReturn(mock(Locator.class));
        when(page.evaluate(eq(READY), any())).thenReturn(ready(100, 17));
        when(page.evaluate(eq(INIT), any())).thenReturn(1);
        when(page.evaluate(eq(MEASURE), any())).thenReturn(geometry(400));
        firstImage = png(1);
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn(firstImage);
        doAnswer(call -> { waited.addAndGet(Math.round((Double) call.getArgument(0))); return null; })
                .when(page).waitForTimeout(anyDouble());
    }

    @Test
    void missingContentIsPolledBeforeInitializationAndScreenshot() {
        AtomicInteger probes = new AtomicInteger();
        when(page.evaluate(eq(READY), any())).thenAnswer(ignored -> probes.incrementAndGet() <= 5
                ? Map.of("ready", false, "reason", "DESCRIPTION") : ready(100, 17));
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenAnswer(ignored -> {
            assertTrue(waited.get() >= 4500, "content readiness and both stability windows must precede capture");
            return firstImage;
        });

        assertEquals(1, capture().size());

        verify(page, times(1)).evaluate(eq(INIT), any());
        verify(page, times(1)).screenshot(any(Page.ScreenshotOptions.class));
        verify(page, times(1)).evaluate(eq(RESTORE), any());
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FONTS", "BUSY", "HEADER", "TITLE", "SALARY"})
    void fontsAndOtherLoadingStatesAreWaitedRatherThanImmediatelyRejected(String reason) {
        AtomicInteger probes = new AtomicInteger();
        when(page.evaluate(eq(READY), any())).thenAnswer(ignored -> probes.incrementAndGet() <= 2
                ? Map.of("ready", false, "reason", reason) : ready(100, 17));

        assertEquals(1, capture().size());

        assertTrue(waited.get() >= 3000);
    }

    @Test
    void unchangedGeometryWithRefreshingTextStillNeedsAStableContentHash() {
        AtomicInteger probes = new AtomicInteger();
        when(page.evaluate(eq(READY), any())).thenAnswer(ignored -> {
            int probe = probes.incrementAndGet();
            return ready(100, probe <= 4 ? probe : 17);
        });

        assertEquals(1, capture().size());

        assertTrue(waited.get() >= 4000);
    }

    @Test
    void scrollingIsFollowedByItsOwnStableGeometryWindow() {
        AtomicInteger measured = new AtomicInteger();
        when(page.evaluate(eq(MEASURE), any())).thenAnswer(ignored -> {
            int probe = measured.incrementAndGet();
            Map<String, Object> g = new java.util.HashMap<>(geometry(400));
            g.put("x", probe == 2 ? 110.0 : 100.0);
            return g;
        });
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenAnswer(ignored -> {
            assertTrue(waited.get() >= 3000);
            return firstImage;
        });

        assertEquals(1, capture().size());
    }

    @Test
    void neverReadyContentExhaustsTheSharedBudgetWithoutTakingAPicture() {
        when(page.evaluate(eq(READY), any())).thenReturn(Map.of("ready", false, "reason", "FONTS"));

        var failure = assertThrows(BossJobScreenshotCapture.CaptureException.class, this::capture);

        assertTrue(failure.getMessage().contains("30秒"));
        assertTrue(failure.getMessage().contains("字体仍在加载"));
        assertEquals(30_000, waited.get());
        verify(page, never()).evaluate(eq(INIT), any());
        verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
    }

    @Test
    void continuouslyMovingLayoutCannotPassByDriftingWithinToleranceEveryPoll() {
        AtomicInteger probes = new AtomicInteger();
        when(page.evaluate(eq(READY), any())).thenAnswer(ignored -> ready(100 + probes.incrementAndGet() * 2, 17));

        assertThrows(BossJobScreenshotCapture.CaptureException.class, this::capture);

        assertEquals(30_000, waited.get());
        verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
    }

    @Test
    void pictureTimeoutIsCappedByTheRemainingSharedBudget() {
        AtomicInteger probes = new AtomicInteger();
        when(page.evaluate(eq(READY), any())).thenAnswer(ignored -> probes.incrementAndGet() <= 52
                ? Map.of("ready", false, "reason", "DESCRIPTION") : ready(100, 17));
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenAnswer(call -> {
            Page.ScreenshotOptions options = call.getArgument(0);
            assertTrue(options.timeout > 0 && options.timeout <= 2000);
            return firstImage;
        });

        assertEquals(1, capture().size());
        assertTrue(waited.get() >= 28_000);
    }

    @Test
    void aLayoutChangeAfterTheFirstImageDiscardsThatWholeSetAndCapturesAgain() throws Exception {
        AtomicInteger measured = new AtomicInteger();
        when(page.evaluate(eq(MEASURE), any())).thenAnswer(ignored -> geometry(measured.incrementAndGet() == 5 ? 450 : 400));
        byte[] secondImage = png(2);
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn(firstImage, secondImage);

        var result = capture();

        assertEquals(1, result.size());
        assertArrayEquals(secondImage, result.getFirst().image());
        assertEquals("boss-job-1.png", result.getFirst().sourceName());
        verify(page, times(2)).evaluate(eq(INIT), any());
        verify(page, times(2)).evaluate(eq(RESTORE), any());
        verify(page, times(2)).screenshot(any(Page.ScreenshotOptions.class));
    }

    @Test
    void initializationRerenderMayRecoverOnceWithoutLosingItsControlledCause() {
        PlaywrightException rerender = new PlaywrightException("Error: Job detail header or description unavailable; private URL token");
        when(page.evaluate(eq(INIT), any())).thenThrow(rerender).thenReturn(1);

        assertEquals(1, capture().size());

        verify(page, times(2)).evaluate(eq(INIT), any());
        verify(page, times(1)).screenshot(any(Page.ScreenshotOptions.class));
    }

    @Test
    void persistentKnownJavascriptFailureHasASpecificSafeReasonAndInternalCause() {
        PlaywrightException clipped = new PlaywrightException("Error: Job content clipped horizontally https://private/?token=secret");
        when(page.evaluate(eq(INIT), any())).thenThrow(clipped);

        var failure = assertThrows(BossJobScreenshotCapture.CaptureException.class, this::capture);

        assertTrue(failure.getMessage().contains("定位岗位内容"));
        assertTrue(failure.getMessage().contains("超出窗口宽度"));
        assertFalse(failure.getMessage().contains("private"));
        assertFalse(failure.getMessage().contains("secret"));
        assertSame(clipped, failure.getCause());
        verify(page, times(1)).evaluate(eq(INIT), any());
        verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
    }

    @Test
    void restoreFailurePreventsAnotherCaptureAttempt() {
        AtomicInteger measured = new AtomicInteger();
        when(page.evaluate(eq(MEASURE), any())).thenAnswer(ignored -> geometry(measured.incrementAndGet() == 5 ? 450 : 400));
        when(page.evaluate(eq(RESTORE), any())).thenThrow(new PlaywrightException("private restore token"));

        var failure = assertThrows(BossJobScreenshotCapture.CaptureException.class, this::capture);

        assertTrue(failure.getMessage().contains("恢复滚动位置"));
        assertFalse(failure.getMessage().contains("private"));
        verify(page, times(1)).evaluate(eq(INIT), any());
        verify(page, times(1)).screenshot(any(Page.ScreenshotOptions.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationAndVerificationDuringReadinessAreNeverRetried(boolean cancel) {
        RuntimeException stopped = cancel ? new CancellationException("stopped") : BossTaskPausedException.verificationRequired();
        Runnable guard = () -> { if (waited.get() >= 750) throw stopped; };

        assertSame(stopped, assertThrows(RuntimeException.class,
                () -> BossJobScreenshotCapture.capture(page, "expected", guard)));

        verify(page, never()).evaluate(eq(INIT), any());
        verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
    }

    @Test
    void changingJobsWhileWaitingStopsBeforeInitializationAndDoesNotRetry() {
        doAnswer(call -> {
            waited.addAndGet(Math.round((Double) call.getArgument(0)));
            if (waited.get() >= 750) url.set("https://www.zhipin.com/job_detail/other.html");
            return null;
        }).when(page).waitForTimeout(anyDouble());

        var failure = assertThrows(BossJobScreenshotCapture.CaptureException.class, this::capture);

        assertTrue(failure.getMessage().contains("岗位身份已改变"));
        verify(page, never()).evaluate(eq(INIT), any());
        verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
    }

    private List<OfflineScreenshotService.Upload> capture() {
        return BossJobScreenshotCapture.capture(page, "expected", () -> {});
    }

    private static Map<String, Object> ready(double x, long hash) {
        return Map.of("ready", true, "geometry", List.of(x, 80.0, 700.0, 400.0), "contentHash", hash);
    }

    private static Map<String, Object> geometry(double height) {
        return Map.of("contentTop", 0.0, "contentBottom", height, "visibleStart", 0.0, "visibleEnd", height,
                "scrollTop", 0.0, "maxScrollTop", 0.0, "x", 100.0, "y", 80.0, "width", 700.0, "height", height);
    }

    private static byte[] png(int pixel) throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, pixel);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static String script(String name) {
        try {
            Field field = BossJobScreenshotCapture.class.getDeclaredField(name);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException invalid) { throw new ExceptionInInitializerError(invalid); }
    }
}
