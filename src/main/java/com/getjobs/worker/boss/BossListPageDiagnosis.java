package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.PageDiagnosticResult;
import com.getjobs.application.screenshot.ScreenshotVisionClient;
import com.getjobs.application.screenshot.ScreenshotVisionException;
import com.getjobs.application.screenshot.VisionImage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotScale;
import com.microsoft.playwright.options.ScreenshotType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** One bounded, read-only viewport observation after list loading has timed out. */
@Component
public final class BossListPageDiagnosis {
    static final String DOCUMENT_ORIGIN_SCRIPT = "() => performance.timeOrigin";
    static final int POLL_INTERVAL_MS = 250;
    static final int CAPTURE_TIMEOUT_MS = 5_000;
    static final Duration DIAGNOSIS_TIMEOUT = Duration.ofSeconds(20);

    private final ScreenshotVisionClient vision;
    private final Duration timeout;

    @Autowired
    public BossListPageDiagnosis(ScreenshotVisionClient vision) {
        this(vision, DIAGNOSIS_TIMEOUT);
    }

    BossListPageDiagnosis(ScreenshotVisionClient vision, Duration timeout) {
        this.vision = Objects.requireNonNull(vision, "vision");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout");
    }

    /** All browser calls and supplied checks stay on the calling Playwright thread. */
    public PageDiagnosticResult diagnose(Page page, Runnable checkAllowed) {
        long deadline = System.nanoTime() + timeout.toNanos();
        FutureTask<String> request = null;
        try {
            if (page == null || checkAllowed == null) {
                throw failure("PAGE_DIAGNOSIS_UNAVAILABLE", "页面诊断缺少可用页面或状态检查");
            }
            allowed(checkAllowed);
            double origin = documentOrigin(page);
            allowed(checkAllowed);
            requireRemaining(deadline);
            byte[] image = capture(page, deadline);
            allowed(checkAllowed);
            requireSameDocument(page, origin);
            if (image == null || image.length == 0 || image.length > VisionImage.MAX_IMAGE_BYTES) {
                throw failure("PAGE_DIAGNOSIS_CAPTURE_FAILED", "页面诊断截图为空或超过大小限制");
            }
            requireRemaining(deadline);

            // The HTTP client can be interrupted without issuing Playwright calls on another thread.
            request = new FutureTask<>(() -> vision.diagnosePage(image, "image/png"));
            Thread.ofVirtual().name("boss-list-page-diagnosis").start(request);
            while (!request.isDone()) {
                allowed(checkAllowed);
                long remaining = requireRemaining(deadline);
                page.waitForTimeout(Math.min(POLL_INTERVAL_MS, Math.max(1, remaining / 1_000_000L)));
            }
            allowed(checkAllowed);
            requireSameDocument(page, origin);
            requireRemaining(deadline);
            String response = completedResponse(request);
            PageDiagnosticResult result = PageDiagnosticResult.parse(response);
            allowed(checkAllowed);
            requireSameDocument(page, origin);
            requireRemaining(deadline);
            return result;
        } catch (BossTaskPausedException | CancellationException | ScreenshotVisionException expected) {
            throw expected;
        } catch (RuntimeException failed) {
            // Provider/browser exceptions may contain request URLs, screenshot data or credentials.
            throw failure("PAGE_DIAGNOSIS_FAILED", "页面截图诊断未能完成，请检查页面后手动确认");
        } finally {
            if (request != null) request.cancel(true);
        }
    }

    private static byte[] capture(Page page, long deadline) {
        try {
            long remainingMillis = Math.max(1, requireRemaining(deadline) / 1_000_000L);
            return page.screenshot(new Page.ScreenshotOptions().setFullPage(false)
                    .setType(ScreenshotType.PNG).setScale(ScreenshotScale.CSS)
                    .setTimeout(Math.min(CAPTURE_TIMEOUT_MS, remainingMillis)));
        } catch (BossTaskPausedException | CancellationException | ScreenshotVisionException expected) {
            throw expected;
        } catch (RuntimeException failed) {
            throw failure("PAGE_DIAGNOSIS_CAPTURE_FAILED", "页面诊断截图失败，本次没有重复截图");
        }
    }

    private static double documentOrigin(Page page) {
        Object value = page.evaluate(DOCUMENT_ORIGIN_SCRIPT);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() <= 0) {
            throw failure("PAGE_DIAGNOSIS_DOCUMENT_CHANGED", "无法核对截图所属页面，请手动检查");
        }
        return number.doubleValue();
    }

    private static void requireSameDocument(Page page, double expectedOrigin) {
        if (Double.compare(documentOrigin(page), expectedOrigin) != 0) {
            throw failure("PAGE_DIAGNOSIS_DOCUMENT_CHANGED", "截图诊断期间页面已刷新或切换，请手动检查");
        }
    }

    private static String completedResponse(FutureTask<String> request) {
        try {
            return request.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("用户取消页面诊断");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ScreenshotVisionException safe) throw safe;
            if (cause instanceof CancellationException cancelled) throw cancelled;
            if (cause instanceof BossTaskPausedException paused) throw paused;
            throw failure("PAGE_DIAGNOSIS_FAILED", "视觉模型未能完成页面诊断，本次没有自动重试");
        }
    }

    private static void allowed(Runnable checkAllowed) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("用户取消页面诊断");
        checkAllowed.run();
    }

    private static long requireRemaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw failure("PAGE_DIAGNOSIS_TIMEOUT", "页面截图诊断超时，本次没有自动重试");
        }
        return remaining;
    }

    private static ScreenshotVisionException failure(String code, String message) {
        return new ScreenshotVisionException(code, message);
    }
}
