package com.getjobs.worker.boss;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** Read-only checks: never solve a challenge, refresh a page, or retry a blocked request. */
public final class BossPageSafety {
    private static final Logger log = LoggerFactory.getLogger(BossPageSafety.class);
    static final int MAX_PROBE_ATTEMPTS = 3;
    static final double PROBE_RECHECK_DELAY_MILLIS = 500;

    static final String VERIFICATION_SELECTOR = String.join(", ",
            "h1:text-is('安全验证'):visible", "h2:text-is('安全验证'):visible",
            "h3:text-is('安全验证'):visible", ":text-is('点击按钮进行验证'):visible",
            ":text-is('点击按钮进行安全验证'):visible", ".geetest_panel:visible");

    private BossPageSafety() { }

    static boolean isVerificationUrl(String url) {
        if (url == null) return false;
        try {
            String path = URI.create(url).getPath();
            if (path == null) return false;
            path = path.toLowerCase(Locale.ROOT);
            return path.contains("/web/user/safe/")
                    || path.contains("/web/passport/zp/verify")
                    || path.contains("/web/passport/zp/security")
                    || path.contains("security-check");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static void requireUsablePage(Page page) {
        requireNotCancelled();
        if (page == null) {
            throw BossTaskPausedException.detailsUnavailable("浏览器页面已关闭或不可用");
        }

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_PROBE_ATTEMPTS; attempt++) {
            requireNotCancelled();
            try {
                // A closed page is a definite stop, while a transient read failure can recover.
                boolean closed = page.isClosed();
                requireNotCancelled();
                if (closed) {
                    throw BossTaskPausedException.detailsUnavailable("浏览器页面已关闭或不可用");
                }
                String url = page.url();
                requireNotCancelled();
                if (isVerificationUrl(url)) {
                    throw BossTaskPausedException.verificationRequired();
                }
                boolean verificationVisible = page.locator(VERIFICATION_SELECTOR).first().isVisible();
                requireNotCancelled();
                if (verificationVisible) {
                    throw BossTaskPausedException.verificationRequired();
                }
                // Missing verification elements and a loading page are not verification evidence.
                return;
            } catch (BossTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                requireNotCancelled();
                lastFailure = failure;
                if (attempt < MAX_PROBE_ATTEMPTS) {
                    log.debug("Boss 页面安全探测临时失败，进行只读复查（{}/{}，异常类型：{}）",
                            attempt, MAX_PROBE_ATTEMPTS, exceptionType(failure));
                    waitBeforeRecheck(page);
                }
            }
        }

        String failureType = exceptionType(lastFailure);
        log.warn("Boss 页面安全探测持续失败（{} 次，异常类型：{}）", MAX_PROBE_ATTEMPTS, failureType);
        // Only a type is exposed: raw Playwright messages can contain URLs or tokens.
        throw BossTaskPausedException.detailsUnavailable("页面安全探测持续失败（"
                + MAX_PROBE_ATTEMPTS + " 次，异常类型：" + failureType + "）");
    }

    private static void waitBeforeRecheck(Page page) {
        requireNotCancelled();
        try {
            page.waitForTimeout(PROBE_RECHECK_DELAY_MILLIS);
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            requireNotCancelled();
            // A failed wait must not become a refresh, navigation, or an unbounded retry.
            log.debug("Boss 页面只读复查等待未完成（异常类型：{}）", exceptionType(failure));
        }
        requireNotCancelled();
    }

    private static void requireNotCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("用户取消任务");
        }
    }

    private static String exceptionType(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name.isEmpty() ? failure.getClass().getName() : name;
    }
}
