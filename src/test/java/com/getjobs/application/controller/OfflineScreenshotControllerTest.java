package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.screenshot.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Standalone MVC: never creates the application or a Playwright manager. */
class OfflineScreenshotControllerTest {
    private MockMvc mvc;
    private OfflineScreenshotService service;
    private ScreenshotVisionConfigService config;

    @BeforeEach
    void setUp() {
        service = mock(OfflineScreenshotService.class);
        config = mock(ScreenshotVisionConfigService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OfflineScreenshotController(service, config, new ObjectMapper())).build();
    }

    @Test
    void publicConfigurationNeverIncludesTheSavedKey() throws Exception {
        when(config.publicConfig()).thenReturn(new ScreenshotVisionConfigService.PublicConfig(
                "https://api.deepseek.com", "deepseek-flash", true, "SCREENSHOT", "EXISTING", true));
        mvc.perform(get("/api/offline-screenshots/config"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }

    @Test
    void mutationRequiresTheDedicatedHeaderAndRejectsCrossOriginAccess() throws Exception {
        mvc.perform(post("/api/offline-screenshots/config").contentType("application/json")
                .content("{\"baseUrl\":\"https://api.example\",\"model\":\"vision\",\"apiKey\":\"test-key\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/offline-screenshots/config").header("Origin", "https://untrusted.example")
                .header("X-Offline-Screenshot", "1").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(config, service);
    }

    @Test
    void missingConsentAndMalformedRulesCannotSubmitImagesToAI() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "job.png", "image/png", new byte[]{1});
        mvc.perform(multipart("/api/offline-screenshots/analyze").file(file)
                .header("X-Offline-Screenshot", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/offline-screenshots/analyze").file(file)
                .header("X-Offline-Screenshot", "1").param("consent", "true").param("rules", "{bad json"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void onlyLoopbackHostAndRemoteAddressMayReadConfiguration() throws Exception {
        mvc.perform(get("/api/offline-screenshots/config").with(request -> {
            request.setRemoteAddr("203.0.113.1"); return request;
        })).andExpect(status().isForbidden());
        mvc.perform(get("/api/offline-screenshots/config").with(request -> {
            request.setServerName("untrusted.example"); return request;
        })).andExpect(status().isForbidden());
        verifyNoInteractions(config);
    }

    @Test
    void partialRulesKeepTheDefaultInternshipAndGraduationRequirements() throws Exception {
        when(service.analyze(any(byte[].class), anyString(), any(ScreenshotRules.class)))
                .thenThrow(new IllegalArgumentException("test stops after inspecting rules"));
        mvc.perform(multipart("/api/offline-screenshots/analyze")
                .file(new MockMultipartFile("file", "job.png", "image/png", new byte[]{1}))
                .header("X-Offline-Screenshot", "1").param("consent", "true")
                .param("rules", "{\"minimumDailySalary\":150}"))
                .andExpect(status().isBadRequest());
        verify(service).analyze(any(byte[].class), eq("job.png"), argThat(rules ->
                rules.requireInternship() && Integer.valueOf(2028).equals(rules.graduationYear())
                        && rules.minimumDailySalary().intValueExact() == 150));
    }

    @Test
    void orderedFilesAreForwardedAsOneAnalysisWithTheMergedRules() throws Exception {
        when(service.analyze(anyList(), any(ScreenshotRules.class)))
                .thenThrow(new IllegalArgumentException("stop after receiving validated request"));
        mvc.perform(multipart("/api/offline-screenshots/analyze")
                .file(new MockMultipartFile("files", "top.png", "image/png", new byte[]{1}))
                .file(new MockMultipartFile("files", "bottom.jpg", "image/jpeg", new byte[]{2, 3}))
                .header("X-Offline-Screenshot", "1").param("consent", "true")
                .param("rules", "{\"minimumDailySalary\":150}"))
                .andExpect(status().isBadRequest());
        verify(service).analyze(argThat(uploads -> {
            assertEquals(List.of("top.png", "bottom.jpg"), uploads.stream()
                    .map(OfflineScreenshotService.Upload::sourceName).toList());
            assertArrayEquals(new byte[]{1}, uploads.getFirst().image());
            assertArrayEquals(new byte[]{2, 3}, uploads.getLast().image());
            return true;
        }), argThat(rules -> rules.minimumDailySalary().intValueExact() == 150
                && rules.requireInternship() && Integer.valueOf(2028).equals(rules.graduationYear())));
        verifyNoMoreInteractions(service);
    }

    @Test
    void mixedParameterNamesRepeatedLegacyEmptyAndTooManyImagesAreRejectedBeforeService() throws Exception {
        mvc.perform(multipart("/api/offline-screenshots/analyze")
                .file(new MockMultipartFile("file", "first.png", "image/png", new byte[]{1}))
                .file(new MockMultipartFile("files", "second.png", "image/png", new byte[]{1}))
                .header("X-Offline-Screenshot", "1").param("consent", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/offline-screenshots/analyze")
                .file(new MockMultipartFile("file", "first.png", "image/png", new byte[]{1}))
                .file(new MockMultipartFile("file", "second.png", "image/png", new byte[]{1}))
                .header("X-Offline-Screenshot", "1").param("consent", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/offline-screenshots/analyze")
                .header("X-Offline-Screenshot", "1").param("consent", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/offline-screenshots/analyze")
                .file(new MockMultipartFile("files", "empty.png", "image/png", new byte[0]))
                .header("X-Offline-Screenshot", "1").param("consent", "true"))
                .andExpect(status().isBadRequest());
        var tooMany = multipart("/api/offline-screenshots/analyze");
        for (int index = 0; index < 7; index++) {
            tooMany.file(new MockMultipartFile("files", "job-" + index + ".png", "image/png", new byte[]{1}));
        }
        mvc.perform(tooMany.header("X-Offline-Screenshot", "1").param("consent", "true"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void oversizedTotalIsRejectedBeforeReadingUploadedBytes() throws Exception {
        var tooLarge = multipart("/api/offline-screenshots/analyze");
        for (int index = 0; index < 4; index++) {
            MockMultipartFile file = new MockMultipartFile("files", "job-" + index + ".png", "image/png", new byte[]{1}) {
                @Override public long getSize() { return OfflineScreenshotService.MAX_IMAGE_BYTES; }
                @Override public byte[] getBytes() { fail("must reject total before loading image bytes"); return null; }
            };
            tooLarge.file(file);
        }
        mvc.perform(tooLarge.header("X-Offline-Screenshot", "1").param("consent", "true"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
