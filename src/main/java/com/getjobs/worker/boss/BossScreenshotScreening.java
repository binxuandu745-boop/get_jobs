package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.BossScreenshotSettingsService;
import com.getjobs.application.screenshot.OfflineScreenshotService;
import com.getjobs.application.screenshot.ScreenshotAnalysisResult;
import com.getjobs.application.screenshot.ScreenshotRules;
import com.getjobs.application.screenshot.ScreenshotVisionConfigService;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bridges browser captures to the same analysis service used by manual uploads. */
@Component
@RequiredArgsConstructor
public class BossScreenshotScreening {
    private final BossScreenshotSettingsService settings;
    private final ScreenshotVisionConfigService visionConfig;
    private final OfflineScreenshotService analysis;

    public BossScreenshotSettingsService.Settings beginTask() {
        var snapshot = settings.current();
        if (snapshot.enabled()) visionConfig.requiredConfig();
        return snapshot;
    }

    public ScreenshotAnalysisResult analyze(Page detailPage, String jobId, ScreenshotRules rules,
                                            Runnable checkAllowed) throws IOException {
        checkAllowed.run();
        requireJobPage(detailPage, jobId);
        List<OfflineScreenshotService.Upload> images = BossJobScreenshotCapture.capture(
                detailPage, jobId, checkAllowed);
        checkAllowed.run();
        requireJobPage(detailPage, jobId);
        // One ordered image set, one model call, one saved result. No loopback HTTP or retries.
        List<OfflineScreenshotService.Upload> named = new ArrayList<>();
        String sourceId = jobId.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (sourceId.length() > 80) sourceId = sourceId.substring(0, 80);
        for (int i = 0; i < images.size(); i++) named.add(new OfflineScreenshotService.Upload(
                images.get(i).image(), "boss-" + sourceId + "-" + (i + 1) + ".png"));
        ScreenshotAnalysisResult result = analysis.analyze(List.copyOf(named), rules);
        checkAllowed.run();
        requireJobPage(detailPage, jobId);
        return result;
    }

    static void requireJobPage(Page page, String expectedId) {
        if (page == null || expectedId == null || expectedId.isBlank()
                || !BossJobScreenshotCapture.matchesJobUrl(page.url(), expectedId)) {
            throw BossTaskPausedException.detailsUnavailable("截图识别前后岗位身份发生变化或无法核对");
        }
    }

    /**
     * 点击“立即沟通”之后，同一个标签页常常已经变成该岗位的聊天页。此时仍要求详情地址会把
     * 正常沟通误判成换了岗位，所以改为接受同站聊天页；聊天地址带 jobId/encryptJobId 时
     * 必须与当前岗位一致。点击之前的检查仍然只用 {@link #requireJobPage}。
     */
    static void requireBoundJobOrChat(Page page, String expectedId) {
        if (page == null || page.isClosed() || expectedId == null || expectedId.isBlank()) {
            throw BossTaskPausedException.detailsUnavailable("聊天页已关闭，无法核对岗位身份");
        }
        String url = page.url();
        if (BossJobScreenshotCapture.matchesJobUrl(url, expectedId)) return;
        if (isSameJobChatUrl(url, expectedId)) return;
        throw BossTaskPausedException.detailsUnavailable("页面已离开岗位详情，且无法确认是该岗位的聊天页");
    }

    /** Same-site chat page, optionally carrying the job id; never another job or another host. */
    static boolean isSameJobChatUrl(String url, String expectedId) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"www.zhipin.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 || uri.getUserInfo() != null) return false;
            String path = uri.getPath();
            if (path == null || !(path.equals("/web/geek/chat") || path.startsWith("/web/geek/chat/"))) {
                return false;
            }
            String query = uri.getRawQuery();
            if (query == null) return true;
            for (String parameter : query.split("&")) {
                int separator = parameter.indexOf('=');
                if (separator < 0) continue;
                String key = URLDecoder.decode(parameter.substring(0, separator), StandardCharsets.UTF_8);
                if (!"jobId".equals(key) && !"encryptJobId".equals(key)) continue;
                String value = URLDecoder.decode(parameter.substring(separator + 1), StandardCharsets.UTF_8);
                return expectedId.equals(value);
            }
            // 聊天地址没带岗位 ID 时，绑定页对象与点击前的校验就是依据。
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
