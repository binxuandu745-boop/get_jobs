package com.getjobs.application.screenshot;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses bounded observation JSON. Screenshot text and model output are untrusted data. */
public class ScreenshotFieldParser {
    static final int MAX_MODEL_TEXT_LENGTH = 32_000;
    private static final Set<String> ALLOWED_FIELDS = Set.of("jobTitle", "company", "city", "salaryText",
            "salaryMin", "salaryMax", "salaryUnit", "daysPerWeek", "durationMonths", "education",
            "skills", "graduationYears", "description", "uncertainFields");
    private static final Pattern FENCED_JSON = Pattern.compile(
            "\\A```(?:json)?\\s*\\R([\\s\\S]*?)\\R```\\z", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ScreenshotFields parse(String modelText) {
        if (modelText == null || modelText.isBlank() || modelText.length() > MAX_MODEL_TEXT_LENGTH) {
            throw new IllegalArgumentException("AI返回为空或超过允许长度");
        }
        String source = modelText.trim();
        Matcher fence = FENCED_JSON.matcher(source);
        if (fence.matches()) source = fence.group(1).trim();
        JsonNode root;
        try {
            root = mapper.readTree(source);
        } catch (Exception invalid) {
            // Never reflect the model body, screenshot text or provider response into an error.
            throw new IllegalArgumentException("AI返回不是有效的单个JSON对象");
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("AI返回必须是JSON对象");
        root.fieldNames().forEachRemaining(name -> {
            if (!ALLOWED_FIELDS.contains(name)) throw new IllegalArgumentException("AI返回包含未支持的字段");
        });
        BigDecimal minimum = amount(root, "salaryMin");
        BigDecimal maximum = amount(root, "salaryMax");
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("薪资下限不能大于上限");
        }
        String unit = text(root, "salaryUnit", 20);
        unit = unit == null ? "UNKNOWN" : unit.toUpperCase(Locale.ROOT);
        if (!Set.of("DAY", "MONTH", "UNKNOWN").contains(unit)) {
            throw new IllegalArgumentException("薪资单位必须是DAY、MONTH或UNKNOWN");
        }
        return new ScreenshotFields(text(root, "jobTitle", 200), text(root, "company", 200),
                text(root, "city", 100), text(root, "salaryText", 120), minimum, maximum, unit,
                integer(root, "daysPerWeek", 1, 7), integer(root, "durationMonths", 1, 120),
                text(root, "education", 80), strings(root, "skills", 40, 80), years(root),
                text(root, "description", 8_000), strings(root, "uncertainFields", 32, 100));
    }

    private static String text(JsonNode root, String key, int maximumLength) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || node.textValue().length() > maximumLength) {
            throw new IllegalArgumentException("字段类型或长度不合法：" + key);
        }
        String value = node.textValue().trim();
        return value.isEmpty() ? null : value;
    }

    private static BigDecimal amount(JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("金额必须是数字或null：" + key);
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("金额超出允许范围：" + key);
        }
        return value;
    }

    private static Integer integer(JsonNode root, String key, int minimum, int maximum) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < minimum || node.intValue() > maximum) {
            throw new IllegalArgumentException("整数超出允许范围：" + key);
        }
        return node.intValue();
    }

    private static List<String> strings(JsonNode root, String key, int maximumItems, int maximumLength) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > maximumItems) {
            throw new IllegalArgumentException("列表类型或长度不合法：" + key);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > maximumLength) {
                throw new IllegalArgumentException("列表元素不合法：" + key);
            }
            result.add(item.textValue().trim());
        }
        return List.copyOf(result);
    }

    private static List<Integer> years(JsonNode root) {
        JsonNode node = root.get("graduationYears");
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > 10) throw new IllegalArgumentException("毕业年份列表不合法");
        List<Integer> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isIntegralNumber() || !item.canConvertToInt()
                    || item.intValue() < 2000 || item.intValue() > 2100) {
                throw new IllegalArgumentException("毕业年份必须是2000至2100之间的整数");
            }
            if (!result.contains(item.intValue())) result.add(item.intValue());
        }
        return List.copyOf(result);
    }
}
