package com.getjobs.application.screenshot;

import com.getjobs.application.service.ConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Separate settings: reuse public endpoint/model defaults, never change greeting settings. */
@Service
public class ScreenshotVisionConfigService {
    static final String BASE = "SCREENSHOT_AI_BASE_URL";
    static final String MODEL = "SCREENSHOT_AI_MODEL";
    static final String KEY = "SCREENSHOT_AI_API_KEY";
    private final ConfigService configService;

    public record PublicConfig(String baseUrl, String model, boolean apiKeyConfigured,
                               String keySource, String configSource, boolean ready) { }

    public record ConfigUpdate(String baseUrl, String model, String apiKey, boolean clearApiKey) {
        @Override public String toString() { return "ConfigUpdate[apiKey=<redacted>]"; }
    }

    public ScreenshotVisionConfigService(ConfigService configService) {
        this.configService = configService;
    }

    public PublicConfig publicConfig() {
        String configuredBase = trim(configService.getConfigValue(BASE));
        String configuredModel = trim(configService.getConfigValue(MODEL));
        String base = configuredBase.isEmpty() ? trim(configService.getConfigValue("BASE_URL")) : configuredBase;
        String model = configuredModel.isEmpty() ? trim(configService.getConfigValue("MODEL")) : configuredModel;
        if (base.isEmpty()) base = "https://api.deepseek.com";
        if (model.isEmpty()) model = "deepseek-flash";
        String savedKey = trim(configService.getConfigValue(KEY));
        boolean keyConfigured = !savedKey.isEmpty() && validKey(savedKey);
        boolean valid = true;
        try { base = validateBaseUrl(base); }
        catch (IllegalArgumentException invalid) { base = ""; valid = false; }
        try { model = validateModel(model); }
        catch (IllegalArgumentException invalid) { model = ""; valid = false; }
        return new PublicConfig(base, model, keyConfigured, keyConfigured ? "SCREENSHOT" : "MISSING",
                configuredBase.isEmpty() && configuredModel.isEmpty() ? "EXISTING" : "SCREENSHOT",
                valid && keyConfigured);
    }

    @Transactional
    public PublicConfig save(ConfigUpdate update) {
        if (update == null) throw new IllegalArgumentException("配置不能为空");
        String base = validateBaseUrl(update.baseUrl());
        String model = validateModel(update.model());
        String key = trim(update.apiKey());
        if (!validKey(key)) {
            throw new IllegalArgumentException("API Key格式无效，请检查是否含空白字符");
        }
        if (update.clearApiKey() && !key.isEmpty()) {
            throw new IllegalArgumentException("不能同时清除和设置API Key");
        }
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(BASE, base);
        changes.put(MODEL, model);
        if (update.clearApiKey() || !key.isEmpty()) changes.put(KEY, update.clearApiKey() ? "" : key);
        if (configService.batchUpdateConfigs(changes) != changes.size()) {
            throw new IllegalStateException("截图识别配置保存未完成");
        }
        return publicConfig();
    }

    public VisionCredentials requiredConfig() {
        PublicConfig view = publicConfig();
        if (!view.ready()) throw new ScreenshotVisionException("MISSING_CONFIG", "请先在截图识别页面保存有效地址、模型和API Key");
        String key = trim(configService.getConfigValue(KEY));
        if (key.isEmpty()) throw new ScreenshotVisionException("MISSING_CONFIG", "截图识别API Key尚未配置");
        return new VisionCredentials(validateBaseUrl(view.baseUrl()), validateModel(view.model()), key);
    }

    static String validateBaseUrl(String value) {
        String base = trim(value);
        if (base.isEmpty() || base.length() > 2048) throw new IllegalArgumentException("API地址不能为空或过长");
        try {
            URI uri = URI.create(base);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host) || "::1".equals(host);
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() > 65535 || !("https".equalsIgnoreCase(scheme)
                    || ("http".equalsIgnoreCase(scheme) && local))) throw new IllegalArgumentException();
            String normalized = host.toLowerCase(Locale.ROOT);
            for (String recruitmentHost : new String[]{"zhipin.com", "liepin.com", "51job.com", "zhaopin.com"}) {
                if (normalized.equals(recruitmentHost) || normalized.endsWith("." + recruitmentHost)) {
                    throw new IllegalArgumentException();
                }
            }
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return base;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("请填写AI服务HTTPS地址；本地模型可使用HTTP回环地址，不接受招聘网站地址或带密钥参数的URL");
        }
    }

    private static String validateModel(String value) {
        String model = trim(value);
        if (model.isEmpty() || model.length() > 160 || model.startsWith("sk-")
                || model.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("模型名称不能为空、过长或含控制字符");
        }
        return model;
    }

    private static boolean validKey(String key) {
        return key.length() <= 4096 && key.chars().noneMatch(ch ->
                Character.isWhitespace(ch) || Character.isISOControl(ch));
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
