package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.screenshot.BossScreenshotSettingsService;
import com.getjobs.application.screenshot.ScreenshotRules;
import com.getjobs.application.screenshot.ScreenshotVisionConfigService;
import com.getjobs.application.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No application context or browser is started by these configuration requests. */
class BossScreenshotAutomationControllerTest {
    private MockMvc mvc;
    private ConfigService repository;
    private ScreenshotVisionConfigService vision;
    private Map<String, String> values;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        repository = mock(ConfigService.class);
        vision = mock(ScreenshotVisionConfigService.class);
        values = new HashMap<>();
        mapper = new ObjectMapper();
        when(repository.getConfigValue(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(repository.batchUpdateConfigs(anyMap())).thenAnswer(call -> {
            Map<String, String> updates = call.getArgument(0);
            values.putAll(updates);
            return updates.size();
        });
        when(vision.publicConfig()).thenReturn(new ScreenshotVisionConfigService.PublicConfig(
                "https://vision.example", "vision", false, "MISSING", "SCREENSHOT", false));
        mvc = MockMvcBuilders.standaloneSetup(new BossScreenshotAutomationController(
                new BossScreenshotSettingsService(repository, mapper, true), vision)).build();
    }

    @Test
    void getExposesRulesAndReadinessWithoutAnyModelCredentials() throws Exception {
        mvc.perform(get("/api/boss/screenshot-automation"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.rules.graduationYear").value(2028))
                .andExpect(jsonPath("$.visionReady").value(false))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.baseUrl").doesNotExist());
        verify(repository, never()).batchUpdateConfigs(anyMap());
    }

    @Test
    void localPostSavesTheCompleteRulesAndReturnsTheirPersistedState() throws Exception {
        String body = mapper.writeValueAsString(new BossScreenshotSettingsService.Settings(false, ScreenshotRules.defaults()));
        mvc.perform(post("/api/boss/screenshot-automation").contentType("application/json")
                        .header("X-Offline-Screenshot", "1").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.visionReady").value(false));
        mvc.perform(get("/api/boss/screenshot-automation"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        verify(repository, times(1)).batchUpdateConfigs(anyMap());
    }

    @Test
    void remoteRequestsUntrustedOriginsAndMissingMutationHeadersCannotAccessSettings() throws Exception {
        mvc.perform(post("/api/boss/screenshot-automation").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/boss/screenshot-automation").contentType("application/json")
                        .header("X-Offline-Screenshot", "1").header("Origin", "https://other.example").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/boss/screenshot-automation").with(request -> {
            request.setRemoteAddr("192.168.1.2"); return request;
        })).andExpect(status().isForbidden());
        mvc.perform(get("/api/boss/screenshot-automation").with(request -> {
            request.setServerName("other.example"); return request;
        })).andExpect(status().isForbidden());
        verifyNoInteractions(repository, vision);
    }

    @Test
    void ambiguousAndInvalidRulesReturn400WithoutAnyWrite() throws Exception {
        String valid = mapper.writeValueAsString(new BossScreenshotSettingsService.Settings(true, ScreenshotRules.defaults()));
        for (String invalid : new String[]{"{}", "null", valid + "{}",
                valid.replace("\"enabled\":true", "\"enabled\":true,\"enabled\":false"),
                valid.replace("\"graduationYear\":2028", "\"graduationYear\":2028.5"),
                valid.replace("\"cities\":[]", "\"cities\":null")}) {
            mvc.perform(post("/api/boss/screenshot-automation").contentType("application/json")
                            .header("X-Offline-Screenshot", "1").content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SETTINGS"));
        }
        verify(repository, never()).batchUpdateConfigs(anyMap());
    }

    @Test
    void corruptStoredRulesAndStorageFailuresReturnSanitizedErrors() throws Exception {
        values.put("BOSS_SCREENSHOT_RULES", "private-config-value");
        mvc.perform(get("/api/boss/screenshot-automation"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SETTINGS_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("private-config-value"))));
        when(repository.batchUpdateConfigs(anyMap())).thenThrow(new IllegalStateException("private-database-path"));
        mvc.perform(post("/api/boss/screenshot-automation").contentType("application/json")
                        .header("X-Offline-Screenshot", "1").content(mapper.writeValueAsString(
                                new BossScreenshotSettingsService.Settings(false, ScreenshotRules.defaults()))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("private-database-path"))));
    }
}
