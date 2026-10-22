package com.getjobs.application.screenshot;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

/** Explicit, bounded local screening rules. These rules do not initiate applications. */
public record ScreenshotRules(
        List<String> roleTerms,
        boolean requireInternship,
        BigDecimal minimumDailySalary,
        BigDecimal minimumMonthlySalary,
        List<String> cities,
        Integer graduationYear) {

    public ScreenshotRules {
        roleTerms = validateList(roleTerms, "岗位关键词", false);
        cities = validateList(cities, "城市", true);
        minimumDailySalary = validateAmount(minimumDailySalary, "最低日薪");
        minimumMonthlySalary = validateAmount(minimumMonthlySalary, "最低月薪");
        if (graduationYear != null && (graduationYear < 2000 || graduationYear > 2100)) {
            throw new IllegalArgumentException("毕业年份必须在2000至2100之间");
        }
    }

    public static ScreenshotRules defaults() {
        return new ScreenshotRules(List.of("java", "后端", "服务端"), true,
                new BigDecimal("125"), new BigDecimal("5000"), List.of(), 2028);
    }

    private static List<String> validateList(List<String> values, String label, boolean allowEmpty) {
        if (values == null) {
            if (allowEmpty) return List.of();
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (values.size() > 20 || (!allowEmpty && values.isEmpty())) {
            throw new IllegalArgumentException(label + "须包含1至20项（城市允许为空）");
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 80) {
                throw new IllegalArgumentException(label + "的每一项须为1至80个字符");
            }
            cleaned.add(value.trim());
        }
        return List.copyOf(cleaned);
    }

    private static BigDecimal validateAmount(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(label + "须为0至1000000之间、最多两位小数的金额");
        }
        return value;
    }
}
