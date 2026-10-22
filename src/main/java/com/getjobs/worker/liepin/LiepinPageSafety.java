package com.getjobs.worker.liepin;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** Read-only checks; never navigate, refresh, click a challenge, or solve a captcha. */
public final class LiepinPageSafety {
    private static final Logger log = LoggerFactory.getLogger(LiepinPageSafety.class);
    static final int MAX_PROBE_ATTEMPTS = 3;
    static final double PROBE_RECHECK_DELAY_MILLIS = 500;

    // Exact visible prompts and captcha panels, rather than arbitrary job text or login text.
    static final String VERIFICATION_SELECTOR = String.join(", ",
            "h1:text-is('安全验证'):visible", "h2:text-is('安全验证'):visible",
            "h3:text-is('安全验证'):visible", ":text-is('点击验证'):visible",
            ":text-is('请完成安全验证'):visible", ":text-is('点击按钮进行验证'):visible",
            ".geetest_panel:visible", ".geetest_panel_box:visible",
            ".nc-container .nc_scale:visible", ".yidun_panel:visible",
            ".yidun_popup:visible", ".tcaptcha-transform:visible");

    private LiepinPageSafety() { }

    static boolean isVerificationUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            // This is Liepin's observed captcha dispatch endpoint; query words are irrelevant.
            return host != null && "safe.liepin.com".equals(host.toLowerCase(Locale.ROOT))
                    && ("/intercept/ip/captcha/dispatch".equals(path)
                        || "/intercept/ip/captcha/dispatch/".equals(path));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static void requireUsablePage(Page page) {
        requireUsablePage(page, null);
    }

    /** The callback is also checked around the bounded recovery waits. */
    public static void requireUsablePage(Page page, Runnable checkStopped) {
        requireNotCancelled(checkStopped);
        if (page == null) throw LiepinTaskPausedException.pageUnavailable();

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_PROBE_ATTEMPTS; attempt++) {
            requireNotCancelled(checkStopped);
            try {
                boolean closed = page.isClosed();
                requireNotCancelled(checkStopped);
                if (closed) throw LiepinTaskPausedException.pageUnavailable();

                String url = page.url();
                requireNotCancelled(checkStopped);
                if (isVerificationUrl(url)) throw LiepinTaskPausedException.verificationRequired();

                boolean verificationVisible = page.locator(VERIFICATION_SELECTOR).first().isVisible();
                requireNotCancelled(checkStopped);
                if (verificationVisible) throw LiepinTaskPausedException.verificationRequired();

                // A loading/blank page without visible captcha evidence is not a security stop.
                return;
            } catch (LiepinTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                requireNotCancelled(checkStopped);
                lastFailure = failure;
                if (attempt < MAX_PROBE_ATTEMPTS) {
                    log.debug("猎聘页面安全探测临时失败，进行只读复查（{}/{}，异常类型：{}）",
                            attempt, MAX_PROBE_ATTEMPTS, LiepinTaskPausedException.exceptionType(failure));
                    waitBeforeRecheck(page, checkStopped);
                }
            }
        }

        log.warn("猎聘页面安全探测持续失败（{} 次，异常类型：{}）",
                MAX_PROBE_ATTEMPTS, LiepinTaskPausedException.exceptionType(lastFailure));
        throw LiepinTaskPausedException.readFailed(lastFailure);
    }

    private static void waitBeforeRecheck(Page page, Runnable checkStopped) {
        requireNotCancelled(checkStopped);
        try {
            page.waitForTimeout(PROBE_RECHECK_DELAY_MILLIS);
        } catch (LiepinTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            requireNotCancelled(checkStopped);
            // A failed wait must not cause a navigation or an unbounded retry.
            log.debug("猎聘页面只读复查等待未完成（异常类型：{}）",
                    LiepinTaskPausedException.exceptionType(failure));
        }
        requireNotCancelled(checkStopped);
    }

    private static void requireNotCancelled(Runnable checkStopped) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("用户取消任务");
        if (checkStopped != null) checkStopped.run();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("用户取消任务");
    }
}
