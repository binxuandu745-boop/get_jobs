package com.getjobs.application.screenshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossScreenshotSettingsServiceTest {
    private ConfigService repository;
    private ObjectMapper mapper;
    private Map<String, String> values;
    private BossScreenshotSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConfigService.class);
        mapper = new ObjectMapper();
        values = new HashMap<>();
        when(repository.getConfigValue(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(repository.batchUpdateConfigs(anyMap())).thenAnswer(call -> {
            Map<String, String> updates = call.getArgument(0);
            values.putAll(updates);
            return updates.size();
        });
        service = new BossScreenshotSettingsService(repository, mapper, true);
    }

    @Test
    void anUnconfiguredDatabaseUsesTheConfiguredDefaultAndExistingRuleDefaults() {
        assertEquals(new BossScreenshotSettingsService.Settings(true, ScreenshotRules.defaults()), service.current());
        assertFalse(new BossScreenshotSettingsService(repository, mapper, false).current().enabled());
        verify(repository, never()).batchUpdateConfigs(anyMap());
    }

    @Test
    void savingPersistsOnlyTheTwoAutomationKeysAndSurvivesANewServiceInstance() {
        var rules = new ScreenshotRules(List.of("Java"), false, new BigDecimal("160"),
                new BigDecimal("6500"), List.of("杭州"), null);
        var settings = new BossScreenshotSettingsService.Settings(false, rules);
        assertEquals(settings, service.save(settings));
        assertEquals(settings, new BossScreenshotSettingsService(repository, mapper, true).current());
        assertEquals(2, values.size());
        assertEquals("false", values.get("BOSS_SCREENSHOT_ENABLED"));
        assertNotNull(values.get("BOSS_SCREENSHOT_RULES"));
        verify(repository, times(1)).batchUpdateConfigs(argThat(map -> map.size() == 2
                && map.containsKey("BOSS_SCREENSHOT_ENABLED") && map.containsKey("BOSS_SCREENSHOT_RULES")));
    }

    @Test
    void corruptedStoredSettingsCannotSilentlyEnableDefaultRules() throws Exception {
        String validRules = mapper.writeValueAsString(ScreenshotRules.defaults());
        for (String invalid : List.of("", "null", "{}", validRules + " {}",
                validRules.replace("\"roleTerms\":[", "\"unexpected\":true,\"roleTerms\":["),
                validRules.replace("\"requireInternship\":true", "\"requireInternship\":null"),
                validRules.replace("\"requireInternship\":true", "\"requireInternship\":\"true\""),
                validRules.replace("\"requireInternship\":true", "\"requireInternship\":true,\"requireInternship\":false"),
                validRules.replace("\"cities\":[]", "\"cities\":null"),
                validRules.replace("\"graduationYear\":2028", "\"graduationYear\":2028.0"),
                validRules.replace("\"graduationYear\":2028", "\"graduationYear\":\"2028\""),
                validRules.replace("\"minimumDailySalary\":125", "\"minimumDailySalary\":-1"))) {
            values.put("BOSS_SCREENSHOT_RULES", invalid);
            assertThrows(IllegalStateException.class, service::current, invalid);
        }
        values.remove("BOSS_SCREENSHOT_RULES");
        for (String invalid : List.of("", "1", "TRUE", "false ")) {
            values.put("BOSS_SCREENSHOT_ENABLED", invalid);
            assertThrows(IllegalStateException.class, service::current);
        }
        verify(repository, never()).batchUpdateConfigs(anyMap());
    }

    @Test
    void requestParserRejectsAmbiguousCoercedAndIncompleteInputBeforeSaving() throws Exception {
        String valid = mapper.writeValueAsString(new BossScreenshotSettingsService.Settings(true, ScreenshotRules.defaults()));
        assertTrue(service.parseSettings(valid).enabled());
        for (String invalid : List.of("null", "{}", valid + "{}", valid.replace("\"enabled\":true", "\"enabled\":null"),
                valid.replace("\"enabled\":true", "\"enabled\":\"true\""),
                valid.replace("\"enabled\":true", "\"enabled\":true,\"enabled\":false"),
                valid.replace("\"enabled\":true", "\"enabled\":true,\"other\":1"),
                valid.replace("\"roleTerms\":[\"java\",\"后端\",\"服务端\"]", "\"roleTerms\":[5]"))) {
            assertThrows(IllegalArgumentException.class, () -> service.parseSettings(invalid));
        }
        verify(repository, never()).batchUpdateConfigs(anyMap());
    }

    @Test
    void incompleteWritesAreNotReportedAsSuccessful() {
        when(repository.batchUpdateConfigs(anyMap())).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.save(
                new BossScreenshotSettingsService.Settings(false, ScreenshotRules.defaults())));
        assertThrows(IllegalArgumentException.class, () -> service.save(null));
        assertThrows(IllegalArgumentException.class, () -> new BossScreenshotSettingsService.Settings(true, null));
    }
}
