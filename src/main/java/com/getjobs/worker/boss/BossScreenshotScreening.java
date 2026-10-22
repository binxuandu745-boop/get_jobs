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
}
