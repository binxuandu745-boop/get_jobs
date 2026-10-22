package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.OfflineScreenshotService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure geometry and mocked Playwright only: no website, browser launch, or paid recognition. */
class BossJobScreenshotCaptureTest {
    @Test
    void oneImageContainsTheWholeShortJobAndRestoresScroll() {
        FakeSurface surface = new FakeSurface(360, 600);

        List<OfflineScreenshotService.Upload> result = capture(surface);

        assertEquals(1, result.size());
        assertEquals("boss-job-1.png", result.getFirst().sourceName());
        assertEquals(1, surface.restores);
        assertEquals(List.of(), surface.scrollRequests);
        assertEquals(360, surface.captured.getFirst().height());
    }

    @Test
    void longJobHasOverlapAndSixImagesMayExactlyCompleteCoverage() {
        // 600 + 5 * (600 - 96), so the sixth image reaches the exact end.
        FakeSurface surface = new FakeSurface(3120, 600);

        assertEquals(6, capture(surface).size());

        assertEquals(List.of(504.0, 1008.0, 1512.0, 2016.0, 2520.0), surface.scrollRequests);
        assertEquals(1, surface.restores);
    }

    @Test
    void sixImagesWithRemainingTextFailWithoutReturningPartialImages() {
        FakeSurface surface = new FakeSurface(3200, 600);

        var failure = assertThrows(BossJobScreenshotCapture.CaptureException.class, () -> capture(surface));

        assertTrue(failure.getMessage().contains("6张"));
        assertEquals(6, surface.captured.size());
        assertEquals(1, surface.restores);
    }

    @Test
    void aStoppedNestedScrollerCannotBeMistakenForTheEndOfTheJob() {
        FakeSurface surface = new FakeSurface(1400, 600);
        surface.ignoreScroll = true;

        var failure = assertThrows(BossJobScreenshotCapture.CaptureException.class, () -> capture(surface));

        assertTrue(failure.getMessage().contains("未前进"));
        assertEquals(1, surface.captured.size());
        assertEquals(1, surface.restores);
    }

    @Test
    void hiddenBottomOfAnOversizedContainerFailsRatherThanSilentlyTruncating() {
        FakeSurface surface = new FakeSurface(1400, 600);
        surface.maxScroll = 500;

        var failure = assertThrows(BossJobScreenshotCapture.CaptureException.class, () -> capture(surface));

        assertTrue(failure.getMessage().contains("滚动容器已停止"));
        assertEquals(2, surface.captured.size());
        assertEquals(1, surface.restores);
    }

    @Test
    void unexpectedScrollJumpThatSkipsTextFailsBeforeAnotherScreenshot() {
        FakeSurface surface = new FakeSurface(1800, 600);
        surface.extraScroll = 200;

        var failure = assertThrows(BossJobScreenshotCapture.CaptureException.class, () -> capture(surface));

        assertTrue(failure.getMessage().contains("未覆盖"));
        assertEquals(1, surface.captured.size());
    }

    @Test
    void layoutGrowthAfterACaptureIsMeasuredAndTheNewBottomMustBeCovered() {
        FakeSurface surface = new FakeSurface(900, 600);
        surface.growAfterFirstImage = 1400;

        assertEquals(3, capture(surface).size());
        assertEquals(1400, surface.lastVisibleEnd);
    }

    @Test
    void layoutChangingWithinTheCapturedViewFails() {
        FakeSurface surface = new FakeSurface(350, 600);
        surface.growAfterFirstImage = 450;

        assertThrows(BossJobScreenshotCapture.CaptureException.class, () -> capture(surface));
        assertEquals(1, surface.restores);
    }

    @Test
    void metadataAndNestedDescriptionUseSeparatePhasesWithinTheSameSixImageBudget() {
        FakeSurface surface = new FakeSurface(1300, 600);
        surface.metadataHeight = 250;

        assertEquals(4, capture(surface).size());

        assertEquals(List.of(0, 1), surface.preparedPhases);
        assertEquals(List.of(504.0, 700.0), surface.scrollRequests);
    }

    @Test
    void metadataCannotLeaveNoBudgetForUncapturedDescription() {
        FakeSurface surface = new FakeSurface(3120, 600);
        surface.metadataHeight = 250;

        assertThrows(BossJobScreenshotCapture.CaptureException.class, () -> capture(surface));
        assertEquals(6, surface.captured.size());
    }

    @Test
    void cancellationAfterScrollIsPreservedAndPreventsFurtherScreenshots() {
        FakeSurface surface = new FakeSurface(1400, 600);
        CancellationException cancellation = new CancellationException("stopped");
        Runnable check = () -> { if (!surface.scrollRequests.isEmpty()) throw cancellation; };

        assertSame(cancellation, assertThrows(CancellationException.class,
                () -> BossJobScreenshotCapture.capture(surface, check)));

        assertEquals(1, surface.captured.size());
        assertEquals(1, surface.restores);
    }

    @Test
    void verificationAfterCaptureIsPreservedAndNoImageSetEscapes() {
        FakeSurface surface = new FakeSurface(1400, 600);
        BossTaskPausedException verification = BossTaskPausedException.verificationRequired();
        Runnable check = () -> { if (!surface.captured.isEmpty()) throw verification; };

        assertSame(verification, assertThrows(BossTaskPausedException.class,
                () -> BossJobScreenshotCapture.capture(surface, check)));

        assertEquals(1, surface.restores);
        assertTrue(surface.scrollRequests.isEmpty());
    }

    @Test
    void screenshotFailureStillRestoresAndDoesNotRetry() {
        FakeSurface surface = new FakeSurface(1400, 600);
        surface.screenshotFailure = new IllegalStateException("capture failed");

        assertSame(surface.screenshotFailure, assertThrows(IllegalStateException.class, () -> capture(surface)));

        assertEquals(1, surface.restores);
        assertEquals(1, surface.captured.size());
    }

    @Test
    void identityIsCheckedBeforeAndAfterEveryBrowserStep() {
        FakeSurface surface = new FakeSurface(1400, 600);
        surface.changeIdentityAfterCapture = true;

        assertThrows(BossJobScreenshotCapture.CaptureException.class, () -> capture(surface));

        assertEquals(1, surface.captured.size());
        assertTrue(surface.scrollRequests.isEmpty());
        assertEquals(1, surface.restores);
    }

    @Test
    void pageUrlMismatchDoesNotReadGeometryOrTakeAScreenshot() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://www.zhipin.com/job_detail/other.html");

        assertThrows(BossJobScreenshotCapture.CaptureException.class,
                () -> BossJobScreenshotCapture.capture(page, "expected", () -> {}));

        verify(page, never()).evaluate(anyString(), any());
        verify(page, never()).screenshot(any(Page.ScreenshotOptions.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.zhipin.com/web/geek/jobs", "https://www.zhipin.com/web/geek/chat",
            "https://evil.example/job_detail/a.html", "http://www.zhipin.com/job_detail/a.html",
            "https://www.zhipin.com.evil.example/job_detail/a.html", "https://www.zhipin.com/job_detail/b.html",
            "https://user@www.zhipin.com/job_detail/a.html", "https://www.zhipin.com:444/job_detail/a.html"})
    void onlyTheExpectedHttpsBossDetailPageIsAccepted(String url) {
        assertFalse(BossJobScreenshotCapture.matchesJobUrl(url, "a"));
    }

    @Test
    void detailTrackingQueryDoesNotChangeJobIdentity() {
        assertTrue(BossJobScreenshotCapture.matchesJobUrl("https://www.zhipin.com/job_detail/a.html?securityId=opaque", "a"));
    }

    @Test
    void byteLimitIsInclusiveAndOversizedOrUndecodableImagesAreRejected() {
        byte[] valid = png(20, 20);
        assertDoesNotThrow(() -> BossJobScreenshotCapture.validateImage(
                Arrays.copyOf(valid, OfflineScreenshotService.MAX_IMAGE_BYTES)));
        assertThrows(BossJobScreenshotCapture.CaptureException.class,
                () -> BossJobScreenshotCapture.validateImage(new byte[OfflineScreenshotService.MAX_IMAGE_BYTES + 1]));
        assertThrows(BossJobScreenshotCapture.CaptureException.class,
                () -> BossJobScreenshotCapture.validateImage(new byte[]{1, 2, 3}));
    }

    @Test
    void actualPixelDimensionsAreValidatedIndependentlyOfGeometry() {
        assertDoesNotThrow(() -> BossJobScreenshotCapture.validateImage(png(4000, 4000)));
        assertThrows(BossJobScreenshotCapture.CaptureException.class,
                () -> BossJobScreenshotCapture.validateImage(png(4001, 4000)));
    }

    @Test
    void totalByteLimitIsInclusiveButExceededTotalFails() {
        FakeSurface exact = new FakeSurface(1200, 400);
        exact.imageBytes = Arrays.copyOf(png(20, 20), OfflineScreenshotService.MAX_IMAGE_BYTES);
        assertEquals(4, countExpectedImages(1200, 400));
        assertThrows(BossJobScreenshotCapture.CaptureException.class, () -> capture(exact));

        FakeSurface accepted = new FakeSurface(1200, 600);
        accepted.imageBytes = exact.imageBytes;
        assertEquals(3, capture(accepted).size());
    }

    @Test
    void callbacksRunThroughoutCaptureAndCleanup() {
        FakeSurface surface = new FakeSurface(1400, 600);
        AtomicInteger checks = new AtomicInteger();

        BossJobScreenshotCapture.capture(surface, checks::incrementAndGet);

        assertTrue(checks.get() >= 20);
        assertEquals(1, surface.restores);
    }

    private static int countExpectedImages(double content, double viewport) {
        return (int) (1 + Math.ceil(Math.max(0, content - viewport) / (viewport - Math.min(96, viewport / 3))));
    }

    private static List<OfflineScreenshotService.Upload> capture(FakeSurface surface) {
        return BossJobScreenshotCapture.capture(surface, () -> {});
    }

    private static byte[] png(int width, int height) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png", bytes);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private static final class FakeSurface implements BossJobScreenshotCapture.CaptureSurface {
        double contentHeight;
        final double viewportHeight;
        double position;
        double maxScroll;
        double extraScroll;
        double metadataHeight;
        double growAfterFirstImage;
        double lastVisibleEnd;
        int restores;
        boolean ignoreScroll;
        boolean changeIdentityAfterCapture;
        RuntimeException screenshotFailure;
        byte[] imageBytes = png(20, 20);
        final List<Double> scrollRequests = new ArrayList<>();
        final List<Integer> preparedPhases = new ArrayList<>();
        final List<BossJobScreenshotCapture.Clip> captured = new ArrayList<>();

        FakeSurface(double contentHeight, double viewportHeight) {
            this.contentHeight = contentHeight;
            this.viewportHeight = viewportHeight;
            this.maxScroll = Math.max(0, contentHeight - viewportHeight);
        }

        @Override public void requireSameJob() {
            if (changeIdentityAfterCapture && !captured.isEmpty()) {
                throw newCaptureFailureFromUrl();
            }
        }

        private BossJobScreenshotCapture.CaptureException newCaptureFailureFromUrl() {
            Page page = mock(Page.class);
            when(page.url()).thenReturn("https://www.zhipin.com/web/geek/chat");
            return assertThrows(BossJobScreenshotCapture.CaptureException.class,
                    () -> BossJobScreenshotCapture.capture(page, "expected", () -> {}));
        }

        @Override public int initialize() { return metadataHeight > 0 ? 2 : 1; }

        @Override public void prepare(int phase) {
            preparedPhases.add(phase);
            position = 0;
        }

        @Override public BossJobScreenshotCapture.Geometry measure(int phase) {
            double height = metadataHeight > 0 && phase == 0 ? metadataHeight : contentHeight;
            double end = Math.min(height, position + viewportHeight);
            lastVisibleEnd = end;
            return new BossJobScreenshotCapture.Geometry(0, height, position, end, position,
                    metadataHeight > 0 && phase == 0 ? 0 : maxScroll,
                    new BossJobScreenshotCapture.Clip(100, 80, 700, end - position));
        }

        @Override public void scrollTo(int phase, double requested) {
            scrollRequests.add(requested);
            if (!ignoreScroll) position = Math.min(maxScroll, requested + extraScroll);
        }

        @Override public byte[] screenshot(BossJobScreenshotCapture.Clip clip) {
            captured.add(clip);
            if (screenshotFailure != null) throw screenshotFailure;
            if (captured.size() == 1 && growAfterFirstImage > 0) {
                contentHeight = growAfterFirstImage;
                maxScroll = Math.max(0, contentHeight - viewportHeight);
            }
            return imageBytes;
        }

        @Override public void restore() { restores++; }
    }
}
