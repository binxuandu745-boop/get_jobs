package com.getjobs.worker.manager;

import com.microsoft.playwright.Page;

import java.net.URI;
import java.util.Locale;

/** Read-only login evidence. Missing controls never establish an authenticated session. */
public final class LiepinLoginProbe {
    static final String USER_SELECTOR = "#header-quick-menu-user-info:visible, "
            + "img.header-quick-menu-user-photo:visible, .header-quick-menu-user-photo:visible";
    static final String LOGIN_SELECTOR = "#header-quick-menu-login:visible";

    public enum State { LOGGED_IN, LOGGED_OUT, UNKNOWN }

    public record Result(State state, String message) { }

    private LiepinLoginProbe() { }

    public static Result inspect(Page page) {
        if (page == null) return unknown("浏览器页面尚未就绪，尚未确认猎聘登录");
        try {
            if (page.isClosed()) return unknown("浏览器页面已关闭，尚未确认猎聘登录");

            String url = page.url();
            if (url == null || url.isBlank() || "about:blank".equalsIgnoreCase(url.trim())) {
                return unknown("页面仍在加载，尚未确认猎聘登录");
            }
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || !isLiepinHost(host)) {
                return unknown("当前页面尚未进入猎聘，尚未确认登录");
            }
            if ("safe.liepin.com".equalsIgnoreCase(host)) {
                return unknown("页面需要本人检查或完成安全验证，尚未确认猎聘登录");
            }

            // Explicit user evidence wins over a stale login control on a normal official page.
            if (page.locator(USER_SELECTOR).first().isVisible()) {
                return new Result(State.LOGGED_IN, "已识别猎聘页面用户信息");
            }

            if (isLoginPath(uri.getPath()) || page.locator(LOGIN_SELECTOR).first().isVisible()) {
                return new Result(State.LOGGED_OUT, "页面显示猎聘登录入口，请本人登录");
            }
            return unknown("尚未找到明确的猎聘登录标志，等待页面加载或检查当前标签页");
        } catch (RuntimeException failure) {
            // Do not retain the failure or its message: URLs and tokens can appear in driver errors.
            String type = failure.getClass().getSimpleName();
            if (type.isEmpty()) type = failure.getClass().getName();
            return unknown("猎聘登录检测暂时失败（异常类型：" + type + "），等待下一次确认");
        }
    }

    private static boolean isLiepinHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return "liepin.com".equals(normalized) || normalized.endsWith(".liepin.com");
    }

    private static boolean isLoginPath(String path) {
        if (path == null) return false;
        String normalized = path.toLowerCase(Locale.ROOT);
        return "/login".equals(normalized) || normalized.startsWith("/login/")
                || "/user/login".equals(normalized) || normalized.startsWith("/user/login/");
    }

    private static Result unknown(String message) {
        return new Result(State.UNKNOWN, message);
    }
}
