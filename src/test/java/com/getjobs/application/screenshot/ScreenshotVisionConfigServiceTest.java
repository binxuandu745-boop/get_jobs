package com.getjobs.application.screenshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScreenshotVisionConfigServiceTest {
    private ConfigService repository;
    private ScreenshotVisionConfigService config;
    private Map<String, String> values;

    @BeforeEach
    void setUp() {
        repository = mock(ConfigService.class);
        values = new HashMap<>(Map.of("BASE_URL", "https://api.deepseek.com", "MODEL", "deepseek-flash",
                "API_KEY", "old-greeting-secret"));
        when(repository.getConfigValue(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(repository.batchUpdateConfigs(anyMap())).thenAnswer(call -> {
            Map<String, String> updates = call.getArgument(0);
            values.putAll(updates);
            return updates.size();
        });
        config = new ScreenshotVisionConfigService(repository);
    }

    @Test
    void oldPublicSettingsAreReusedButTheOldSecretIsNotReadOrReturned() throws Exception {
        var view = config.publicConfig();
        assertEquals("https://api.deepseek.com", view.baseUrl());
        assertEquals("deepseek-flash", view.model());
        assertFalse(view.apiKeyConfigured());
        assertFalse(view.ready());
        assertEquals("MISSING", view.keySource());
        assertFalse(new ObjectMapper().writeValueAsString(view).contains("old-greeting-secret"));
        verify(repository, never()).getConfigValue("API_KEY");
    }

    @Test
    void saveAndBlankKeyPreservationCannotModifyGreetingSettings() throws Exception {
        var view = config.save(new ScreenshotVisionConfigService.ConfigUpdate(
                "https://api.deepseek.com/", "deepseek-flash", "new-vision-secret", false));
        assertTrue(view.ready());
        config.save(new ScreenshotVisionConfigService.ConfigUpdate(
                "https://api.deepseek.com", "deepseek-flash", "", false));
        assertEquals("new-vision-secret", config.requiredConfig().apiKey());
        assertEquals("old-greeting-secret", values.get("API_KEY"));
        assertFalse(new ObjectMapper().writeValueAsString(view).contains("new-vision-secret"));
        assertFalse(config.requiredConfig().toString().contains("new-vision-secret"));
    }

    @Test
    void clearingActuallyDisablesScreenshotRequestsWithoutFallingBackToTheOldKey() {
        config.save(new ScreenshotVisionConfigService.ConfigUpdate(
                "https://api.deepseek.com", "deepseek-flash", "new-secret", false));
        config.save(new ScreenshotVisionConfigService.ConfigUpdate(
                "https://api.deepseek.com", "deepseek-flash", "", true));
        assertFalse(config.publicConfig().ready());
        assertThrows(ScreenshotVisionException.class, config::requiredConfig);
        verify(repository, never()).getConfigValue("API_KEY");
    }

    @Test
    void unsafeAddressesAndConflictingKeyOperationsAreRejectedBeforePersistence() {
        for (String address : new String[]{"http://remote.example", "https://user:secret@api.example",
                "https://api.example?key=secret", "https://www.zhipin.com", "file:///etc/passwd"}) {
            assertThrows(IllegalArgumentException.class, () -> config.save(
                    new ScreenshotVisionConfigService.ConfigUpdate(address, "vision", "", false)));
        }
        assertThrows(IllegalArgumentException.class, () -> config.save(
                new ScreenshotVisionConfigService.ConfigUpdate("https://api.example", "vision", "key", true)));
        verify(repository, never()).batchUpdateConfigs(anyMap());
    }

    @Test
    void malformedOldPublicSettingsCannotEchoAnAccidentallyStoredSecret() throws Exception {
        values.put("BASE_URL", "https://api.example?key=old-secret");
        values.put("MODEL", "sk-accidental-model-secret");
        String publicJson = new ObjectMapper().writeValueAsString(config.publicConfig());
        assertFalse(publicJson.contains("old-secret"));
        assertFalse(publicJson.contains("accidental-model-secret"));
        assertFalse(config.publicConfig().ready());
    }

    @Test
    void controlCharactersInKeysAreRejectedBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> config.save(
                new ScreenshotVisionConfigService.ConfigUpdate("https://api.example", "vision", "key\u0000value", false)));
        verify(repository, never()).batchUpdateConfigs(anyMap());
    }
}
