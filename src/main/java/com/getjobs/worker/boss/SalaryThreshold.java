package com.getjobs.worker.boss;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the advertised lower salary bound; unknown units require manual review. */
public final class SalaryThreshold {
    private static final Pattern AMOUNT = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)?)\\s*(?:[-~至]\\s*\\d+(?:\\.\\d+)?)?\\s*([kK万]?)");

    private SalaryThreshold() {}

    public static boolean matches(String salary, int monthlyMinimum, int dailyMinimum) {
        if (monthlyMinimum <= 0 && dailyMinimum <= 0) return true;
        if (salary == null || salary.isBlank()) return false;
        String text = salary.trim().toLowerCase(Locale.ROOT);
        if (text.contains("/年") || text.contains("年薪") || text.contains("/小时")) return false;
        Matcher matcher = AMOUNT.matcher(text);
        if (!matcher.find()) return false;
        boolean daily = text.contains("/天") || text.contains("/日");
        if (daily && dailyMinimum <= 0) return false;
        String unit = matcher.group(2);
        if (!daily && unit.isEmpty() && !text.contains("/月") && !text.contains("月薪")) return false;
        BigDecimal amount = new BigDecimal(matcher.group(1));
        if ("k".equals(unit)) amount = amount.multiply(BigDecimal.valueOf(1000));
        if ("万".equals(unit)) amount = amount.multiply(BigDecimal.valueOf(10000));
        return amount.compareTo(BigDecimal.valueOf(daily ? dailyMinimum : monthlyMinimum)) >= 0;
    }
}
