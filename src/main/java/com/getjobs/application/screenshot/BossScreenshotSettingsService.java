package com.getjobs.application.screenshot;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.ConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Persistent rules shared by the BOSS automation panel and each new BOSS task. */
@Service
public class BossScreenshotSettingsService {
    static final String ENABLED = "BOSS_SCREENSHOT_ENABLED";
    static final String RULES = "BOSS_SCREENSHOT_RULES";
    private static final int MAX_SETTINGS_LENGTH = 16_384;
    private static final Set<String> RULE_FIELDS = Set.of("roleTerms", "requireInternship",
            "minimumDailySalary", "minimumMonthlySalary", "cities", "graduationYear");
    private final ConfigService config;
    private final ObjectMapper mapper;
    private final boolean defaultEnabled;

    public record Settings(boolean enabled, ScreenshotRules rules) {
        public Settings {
            if (rules == null) throw new IllegalArgumentException("请填写完整的截图筛选条件");
        }
    }

    public BossScreenshotSettingsService(ConfigService config, ObjectMapper mapper,
            @Value("${getjobs.boss.screenshot-analysis-enabled:true}") boolean defaultEnabled) {
        this.config = config;
        this.mapper = mapper;
        this.defaultEnabled = defaultEnabled;
    }

    @Transactional(readOnly = true)
    public Settings current() {
        String savedEnabled = config.getConfigValue(ENABLED);
        String savedRules = config.getConfigValue(RULES);
        try {
            boolean enabled = defaultEnabled;
            if (savedEnabled != null) {
                if (!"true".equals(savedEnabled) && !"false".equals(savedEnabled)) {
                    throw new IllegalArgumentException();
                }
                enabled = Boolean.parseBoolean(savedEnabled);
            }
            ScreenshotRules rules = savedRules == null ? ScreenshotRules.defaults() : parseRules(readTree(savedRules));
            return new Settings(enabled, rules);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalStateException("已保存的BOSS截图筛选配置无效，请重新保存配置");
        }
    }

    /** Parse the raw body here so MVC coercion cannot turn strings or null into valid settings. */
    public Settings parseSettings(String body) {
        try {
            JsonNode root = readTree(body);
            requireFields(root, Set.of("enabled", "rules"));
            if (!root.get("enabled").isBoolean()) throw new IllegalArgumentException();
            return new Settings(root.get("enabled").booleanValue(), parseRules(root.get("rules")));
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("截图自动筛选配置格式或取值无效，请检查开关、薪资、城市和毕业年份");
        }
    }

    @Transactional
    public Settings save(Settings settings) {
        if (settings == null) throw new IllegalArgumentException("配置不能为空");
        final String json;
        try {
            json = mapper.writeValueAsString(settings.rules());
            // Also validate settings supplied directly by another Java caller.
            parseRules(readTree(json));
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("截图筛选条件格式或取值无效");
        }
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put(ENABLED, Boolean.toString(settings.enabled()));
        updates.put(RULES, json);
        if (config.batchUpdateConfigs(updates) != updates.size()) {
            throw new IllegalStateException("BOSS截图筛选配置保存未完成");
        }
        return settings;
    }

    private JsonNode readTree(String json) throws IOException {
        if (json == null || json.isBlank() || json.length() > MAX_SETTINGS_LENGTH) {
            throw new IllegalArgumentException();
        }
        return mapper.readerFor(JsonNode.class)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
    }

    private ScreenshotRules parseRules(JsonNode root) throws IOException {
        requireFields(root, RULE_FIELDS);
        if (!root.get("roleTerms").isArray() || !root.get("cities").isArray()
                || !root.get("requireInternship").isBoolean()
                || !root.get("minimumDailySalary").isNumber()
                || !root.get("minimumMonthlySalary").isNumber()
                || !(root.get("graduationYear").isNull() || root.get("graduationYear").isIntegralNumber())) {
            throw new IllegalArgumentException();
        }
        for (String list : new String[]{"roleTerms", "cities"}) {
            for (JsonNode item : root.get(list)) {
                if (!item.isTextual()) throw new IllegalArgumentException();
            }
        }
        return mapper.readerFor(ScreenshotRules.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(root.toString());
    }

    private static void requireFields(JsonNode root, Set<String> names) {
        if (root == null || !root.isObject() || root.size() != names.size()) throw new IllegalArgumentException();
        var fields = root.fieldNames();
        while (fields.hasNext()) {
            if (!names.contains(fields.next())) throw new IllegalArgumentException();
        }
        for (String name : names) {
            if (!root.has(name)) throw new IllegalArgumentException();
        }
    }
}
