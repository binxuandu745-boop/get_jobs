package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.BossScreenshotSettingsService;
import com.getjobs.application.screenshot.OfflineScreenshotService;
import com.getjobs.application.screenshot.ScreenshotAnalysisResult;
import com.getjobs.application.screenshot.ScreenshotEvaluation;
import com.getjobs.application.screenshot.ScreenshotRules;
import com.getjobs.application.screenshot.ScreenshotVisionConfigService;
import com.getjobs.application.screenshot.ScreenshotVisionException;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises ordering, binding and fail-stop behavior without a real browser or model. */
class BossScreenshotScreeningTest {
    private static final String ID = "job-123";
    private static final String URL = "https://www.zhipin.com/job_detail/" + ID + ".html";
    private BossScreenshotSettingsService settings;
    private ScreenshotVisionConfigService credentials;
    private OfflineScreenshotService analysis;
    private BossScreenshotScreening screening;
    private Page page;
    private ScreenshotRules rules;

    @BeforeEach
    void setUp() {
        settings = mock(BossScreenshotSettingsService.class);
        credentials = mock(ScreenshotVisionConfigService.class);
        analysis = mock(OfflineScreenshotService.class);
        screening = new BossScreenshotScreening(settings, credentials, analysis);
        page = mock(Page.class);
        when(page.url()).thenReturn(URL);
        rules = ScreenshotRules.defaults();
    }

    @Test
    void enabledTaskRequiresIndependentVisionConfigurationBeforeStarting() {
        var snapshot = new BossScreenshotSettingsService.Settings(true, rules);
        when(settings.current()).thenReturn(snapshot);
        when(credentials.requiredConfig()).thenThrow(new ScreenshotVisionException("MISSING_CONFIG", "missing"));

        assertThrows(ScreenshotVisionException.class, screening::beginTask);

        verify(credentials, times(1)).requiredConfig();
        verifyNoInteractions(analysis, page);
    }

    @Test
    void disabledTaskDoesNotRequireVisionCredentials() {
        var snapshot = new BossScreenshotSettingsService.Settings(false, rules);
        when(settings.current()).thenReturn(snapshot);

        assertSame(snapshot, screening.beginTask());

        verifyNoInteractions(credentials, analysis, page);
    }

    @Test
    void orderedMultiImageSetIsSubmittedInOneAnalysisCallWithoutLoopbackHttp() throws Exception {
        List<OfflineScreenshotService.Upload> images = images();
        ScreenshotAnalysisResult expected = result();
        Runnable allowed = mock(Runnable.class);
        when(analysis.analyze(anyList(), same(rules))).thenReturn(expected);
        try (MockedStatic<BossJobScreenshotCapture> capture = captureMock()) {
            capture.when(() -> BossJobScreenshotCapture.capture(page, ID, allowed)).thenReturn(images);

            assertSame(expected, screening.analyze(page, ID, rules, allowed));

            capture.verify(() -> BossJobScreenshotCapture.capture(page, ID, allowed), times(1));
        }
        verify(analysis, times(1)).analyze(argThat(actual -> actual.size() == 2
                && actual.get(0).sourceName().equals("boss-job-123-1.png")
                && actual.get(1).sourceName().equals("boss-job-123-2.png")
                && actual.get(0).image() == images.get(0).image()
                && actual.get(1).image() == images.get(1).image()), same(rules));
        verify(allowed, atLeast(3)).run();
    }

    @Test
    void cancellationAfterModelResponseCannotReturnMatch() throws Exception {
        List<OfflineScreenshotService.Upload> images = images();
        AtomicBoolean stopped = new AtomicBoolean();
        Runnable allowed = () -> { if (stopped.get()) throw new CancellationException("stopped"); };
        when(analysis.analyze(anyList(), same(rules))).thenAnswer(ignored -> {
            stopped.set(true);
            return result();
        });
        try (MockedStatic<BossJobScreenshotCapture> capture = captureMock()) {
            capture.when(() -> BossJobScreenshotCapture.capture(page, ID, allowed)).thenReturn(images);

            assertThrows(CancellationException.class, () -> screening.analyze(page, ID, rules, allowed));
        }
        verify(analysis, times(1)).analyze(anyList(), same(rules));
    }

    @Test
    void changedDetailIdentityAfterModelResponseCannotReturnMatch() throws Exception {
        List<OfflineScreenshotService.Upload> images = images();
        Runnable allowed = () -> { };
        when(analysis.analyze(anyList(), same(rules))).thenAnswer(ignored -> {
            when(page.url()).thenReturn("https://www.zhipin.com/job_detail/other-job.html");
            return result();
        });
        try (MockedStatic<BossJobScreenshotCapture> capture = captureMock()) {
            capture.when(() -> BossJobScreenshotCapture.capture(page, ID, allowed)).thenReturn(images);

            assertThrows(BossTaskPausedException.class, () -> screening.analyze(page, ID, rules, allowed));
        }
    }

    @Test
    void modelFailureIsNotAutomaticallyRetried() throws Exception {
        List<OfflineScreenshotService.Upload> images = images();
        Runnable allowed = () -> { };
        ScreenshotVisionException failure = new ScreenshotVisionException("BUSY", "busy");
        when(analysis.analyze(anyList(), same(rules))).thenThrow(failure);
        try (MockedStatic<BossJobScreenshotCapture> capture = captureMock()) {
            capture.when(() -> BossJobScreenshotCapture.capture(page, ID, allowed)).thenReturn(images);

            assertSame(failure, assertThrows(ScreenshotVisionException.class,
                    () -> screening.analyze(page, ID, rules, allowed)));
            capture.verify(() -> BossJobScreenshotCapture.capture(page, ID, allowed), times(1));
        }
        verify(analysis, times(1)).analyze(anyList(), same(rules));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.zhipin.com/job_detail/other.html",
            "https://www.zhipin.com/job_detail/job-123-extra.html",
            "https://www.zhipin.com/web/geek/jobs",
            "https://www.zhipin.com/web/geek/chat?jobId=job-123",
            "https://evil.example/job_detail/job-123.html",
            "http://www.zhipin.com/job_detail/job-123.html"
    })
    void incorrectPageIsRejectedBeforeCapturingOrCallingModel(String url) {
        when(page.url()).thenReturn(url);
        Runnable allowed = mock(Runnable.class);
        try (MockedStatic<BossJobScreenshotCapture> capture = captureMock()) {
            assertThrows(BossTaskPausedException.class, () -> screening.analyze(page, ID, rules, allowed));
            capture.verify(() -> BossJobScreenshotCapture.capture(page, ID, allowed), never());
        }
        verifyNoInteractions(analysis);
    }

    @Test
    void chatPageForTheBoundJobIsAcceptedAfterStartingTheConversation() {
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/chat?jobId=" + ID + "&uid=hr-1");

        assertDoesNotThrow(() -> BossScreenshotScreening.requireBoundJobOrChat(page, ID));
    }

    @Test
    void chatPageStillOnTheDetailUrlIsAccepted() {
        assertDoesNotThrow(() -> BossScreenshotScreening.requireBoundJobOrChat(page, ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.zhipin.com/web/geek/chat?jobId=other-job",
            "https://www.zhipin.com/web/geek/chat?encryptJobId=other-job",
            "https://www.zhipin.com/web/geek/chat?jobId=",
            "https://www.zhipin.com/web/geek/jobs?jobId=job-123",
            "https://evil.example/web/geek/chat?jobId=job-123",
            "http://www.zhipin.com/web/geek/chat?jobId=job-123"
    })
    void otherPagesAndOtherJobsAreStillRejectedAfterTheConversationStarts(String url) {
        when(page.url()).thenReturn(url);

        assertThrows(BossTaskPausedException.class,
                () -> BossScreenshotScreening.requireBoundJobOrChat(page, ID));
    }

    private static List<OfflineScreenshotService.Upload> images() {
        return List.of(new OfflineScreenshotService.Upload(new byte[]{1}, "first-capture.png"),
                new OfflineScreenshotService.Upload(new byte[]{2}, "second-capture.png"));
    }

    private static MockedStatic<BossJobScreenshotCapture> captureMock() {
        MockedStatic<BossJobScreenshotCapture> capture = mockStatic(BossJobScreenshotCapture.class);
        // Keep URL identity validation real while replacing only the browser capture entry point.
        capture.when(() -> BossJobScreenshotCapture.matchesJobUrl(anyString(), anyString())).thenCallRealMethod();
        return capture;
    }

    private static ScreenshotAnalysisResult result() {
        return new ScreenshotAnalysisResult("result-id", "COMPLETE", "2026-10-22T00:00:00Z", "boss.png", "hash",
                null, new ScreenshotEvaluation("MATCH", List.of(), List.of()), null, null, "mock-vision");
    }
}
