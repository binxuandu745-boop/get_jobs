package com.getjobs.worker.manager;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.net.URI;
import java.util.Locale;

/** Read-only login detection. Missing DOM signals are not proof of logout. */
public final class BossLoginProbe {
    // Do not require <li>: the current header can use a different container tag.
    static final String USER_SELECTOR = ".nav-figure .label-text, .nav-figure img, .nav-figure .avatar";
    static final String LOGIN_SELECTOR = ".nav-sign a, .nav-sign, .btn-sign";

    public enum State { LOGGED_IN, LOGGED_OUT, UNKNOWN }

    public record Result(State state, String message, String pageType) { }

    private BossLoginProbe() { }

    public static Result unknown(String message, String pageType) {
        return new Result(State.UNKNOWN, message, pageType);
    }

    public static Result detect(Page page) {
        if (page == null || page.isClosed()) {
            return unknown("浏览器页面已关闭，尚未确认登录", "closed");
        }
        try {
            URI uri = URI.create(page.url());
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("zhipin.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".zhipin.com"))) {
                return unknown("页面尚未进入 Boss 直聘", "other");
            }
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("verify") || path.contains("security") || path.contains("/safe/")
                    || path.contains("/passport/")) {
                return unknown("页面需要本人完成安全验证", "verification");
            }

            boolean userVisible = page.locator(USER_SELECTOR).first().isVisible();
            Locator login = page.locator(LOGIN_SELECTOR).first();
            boolean loginVisible = false;
            if (login.isVisible()) {
                String text = login.textContent(new Locator.TextContentOptions().setTimeout(500));
                loginVisible = text != null && (text.contains("登录") || text.contains("注册"));
            }
            if (userVisible && loginVisible) {
                return unknown("页面登录信息正在切换，等待重新确认", "transition");
            }
            if (userVisible && !path.startsWith("/web/user/")) {
                return new Result(State.LOGGED_IN, "已识别页面用户信息", "authenticated");
            }
            if (loginVisible || path.startsWith("/web/user/")) {
                return new Result(State.LOGGED_OUT, "页面显示登录入口，请本人登录", "login");
            }
            return unknown("尚未找到明确的登录标志，等待页面加载或检查当前标签页", "pending");
        } catch (Exception ignored) {
            // A navigation or a failed probe must not erase an authenticated session.
            return unknown("页面检测暂时失败，等待下一次确认", "pending");
        }
    }

    /** Bound polling explicitly: Locator.isVisible() itself never waits. */
    public static Result awaitKnown(Page page, int maxProbes, long intervalMillis) {
        Result result = unknown("等待页面登录信息", "pending");
        for (int attempt = 0; attempt < Math.max(1, maxProbes); attempt++) {
            result = detect(page);
            if (result.state() != State.UNKNOWN || !"pending".equals(result.pageType())
                    || attempt + 1 >= maxProbes || Thread.currentThread().isInterrupted()) {
                return result;
            }
            try { page.waitForTimeout(intervalMillis); }
            catch (Exception ignored) { return unknown("页面正在关闭或跳转，等待下一次确认", "pending"); }
        }
        return result;
    }
}
