package com.getjobs.application.screenshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local-only screening: absent conditions pass, while unreadable or contradictory evidence rejects. */
public class ScreenshotEvaluator {
    private static final Pattern SALARY = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*([k万]?)\\s*(?:[-~至—–]\\s*(\\d+(?:\\.\\d+)?)\\s*([k万]?))?"
                    + "\\s*(?:元)?\\s*(?:(?:[/／]|每)\\s*(天|日|月|年|小时|时))?(?:[·•]\\s*\\d+薪)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVA_TERM = Pattern.compile("(?<![a-z0-9_])java(?![a-z0-9_])");
    private static final Pattern UNDISCLOSED_SALARY = Pattern.compile(
            "^(?:薪资|薪酬|工资)?(?:面议|待定|保密|未提供|未注明|未标明|暂无|暂未提供|不详)(?:[/／]?(?:天|日|月))?$");
    private static final Pattern NOT_INTERNSHIP = Pattern.compile(
            "非实习|不(?:接受|接收|招聘|招收|考虑|招)\\s*(?:在校)?实习");
    private static final Map<String, String> UNCERTAIN_LABELS = Map.ofEntries(
            Map.entry("salaryText", "薪资原文"), Map.entry("salaryMin", "薪资金额下限"),
            Map.entry("salaryMax", "薪资金额上限"), Map.entry("salaryUnit", "薪资计薪单位"),
            Map.entry("city", "工作城市"), Map.entry("graduationYears", "毕业届次"),
            Map.entry("jobTitle", "岗位名称"), Map.entry("company", "公司名称"),
            Map.entry("description", "职位描述"), Map.entry("daysPerWeek", "每周到岗天数"),
            Map.entry("durationMonths", "实习时长"), Map.entry("education", "学历要求"),
            Map.entry("skills", "技能标签"));
    private static final Set<String> SALARY_FIELDS =
            Set.of("salaryText", "salaryMin", "salaryMax", "salaryUnit");

    public ScreenshotEvaluation evaluate(ScreenshotFields fields, ScreenshotRules rules) {
        if (fields == null || rules == null) throw new IllegalArgumentException("截图字段和筛选规则不能为空");
        List<String> mismatches = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!hasSubstantiveInformation(fields)) {
            return new ScreenshotEvaluation("MISMATCH", List.of("未识别到任何有效岗位信息，按无法识别处理"), List.of());
        }
        String title = fields.jobTitle();
        if (missingText(title)) {
            missing(warnings, "岗位名称");
        } else {
            String normalizedTitle = title.toLowerCase(Locale.ROOT);
            boolean roleMatched = rules.roleTerms().stream()
                    .anyMatch(term -> matchesRoleTerm(normalizedTitle, term.toLowerCase(Locale.ROOT)));
            if (!roleMatched) mismatches.add("岗位名称不包含所配置的目标方向关键词");
        }
        if (rules.requireInternship()) {
            String internshipText = ((title == null ? "" : title) + " "
                    + (fields.description() == null ? "" : fields.description())).toLowerCase(Locale.ROOT);
            if (NOT_INTERNSHIP.matcher(internshipText).find()) {
                mismatches.add("截图明确注明不接受实习或非实习岗位");
            } else if (!internshipText.contains("实习") && !internshipText.contains("intern")) {
                missing(warnings, "实习标识");
            }
        }

        evaluateSalary(fields, rules, mismatches, warnings);
        if (!rules.cities().isEmpty()) {
            if (missingText(fields.city())) {
                missing(warnings, "工作城市");
            } else if (rules.cities().stream().noneMatch(city -> equivalentCity(city, fields.city()))) {
                mismatches.add("工作城市不在所配置的城市范围内");
            }
        }
        if (rules.graduationYear() != null) {
            if (fields.graduationYears().isEmpty()) {
                missing(warnings, "毕业届次");
            } else if (!fields.graduationYears().contains(rules.graduationYear())) {
                mismatches.add("截图注明的毕业届次不包含目标届次");
            }
        }
        markUncertainFields(fields.uncertainFields(), rules, mismatches, warnings);
        if (!mismatches.isEmpty()) return new ScreenshotEvaluation("MISMATCH", mismatches, warnings);
        return new ScreenshotEvaluation("MATCH", List.of(warnings.isEmpty()
                ? "已识别信息满足当前Java筛选规则" : "已给信息未见明确不符；缺失条件按符合处理"), warnings);
    }

    private static void evaluateSalary(ScreenshotFields fields, ScreenshotRules rules,
                                       List<String> mismatches, List<String> warnings) {
        boolean amountMissing = fields.salaryMin() == null;
        boolean unitMissing = missingText(fields.salaryUnit()) || fields.salaryUnit().equals("UNKNOWN");
        BigDecimal comparisonAmount = fields.salaryMin();
        String comparisonUnit = unitMissing ? null : fields.salaryUnit();
        boolean parsedAmount = false;
        boolean parsedUnit = false;
        if (!unitMissing && !fields.salaryUnit().equals("DAY") && !fields.salaryUnit().equals("MONTH")) {
            mismatches.add("结构化薪资计薪单位格式无效");
            return;
        }
        if ((fields.salaryMin() != null && fields.salaryMin().signum() < 0)
                || (fields.salaryMax() != null && fields.salaryMax().signum() < 0)
                || (fields.salaryMin() != null && fields.salaryMax() != null
                && fields.salaryMin().compareTo(fields.salaryMax()) > 0)) {
            mismatches.add("结构化薪资金额或上下限格式无效");
            return;
        }
        if (missingText(fields.salaryText())) {
            missing(warnings, "薪资原文");
        } else if (UNDISCLOSED_SALARY.matcher(fields.salaryText().replaceAll("\\s+", "")).matches()) {
            if (fields.salaryMin() != null || fields.salaryMax() != null) {
                mismatches.add("薪资原文未提供金额，却出现结构化金额，存在矛盾");
                return;
            }
            amountMissing = true;
        } else {
            SalaryObservation observed = parseSalary(fields.salaryText());
            if (observed == null) {
                mismatches.add("薪资原文无法解析或格式不支持，按识别失败处理");
                return;
            }
            if (observed.unit().equals("UNSUPPORTED")) {
                mismatches.add("年薪或时薪等计薪单位暂不支持，未进行日薪月薪换算");
                return;
            }
            if ((fields.salaryMin() != null && observed.minimum().compareTo(fields.salaryMin()) != 0)
                    || (fields.salaryMax() != null && observed.maximum().compareTo(fields.salaryMax()) != 0)
                    || (!unitMissing && !observed.unit().equals("UNKNOWN")
                    && !observed.unit().equals(fields.salaryUnit()))) {
                mismatches.add("薪资原文与结构化金额或单位不一致，按识别失败处理");
                return;
            }
            // Clear source text remains evidence even when the model omitted the corresponding
            // structured field. Use it only for comparison; never fill or rewrite the observation.
            comparisonAmount = observed.minimum();
            parsedAmount = true;
            if (!observed.unit().equals("UNKNOWN")) {
                comparisonUnit = observed.unit();
                parsedUnit = true;
            } else {
                // Missing periods never cause a guessed daily/monthly conversion.
                missing(warnings, "薪资原文计薪单位");
            }
        }
        if (amountMissing) missing(warnings, parsedAmount ? "结构化薪资金额下限" : "薪资金额下限");
        if (unitMissing) missing(warnings, parsedUnit ? "结构化薪资计薪单位" : "薪资计薪单位");
        if (comparisonAmount == null || comparisonUnit == null) return;
        BigDecimal minimum = comparisonUnit.equals("DAY")
                ? rules.minimumDailySalary() : rules.minimumMonthlySalary();
        if (comparisonAmount.compareTo(minimum) < 0) {
            mismatches.add(comparisonUnit.equals("DAY")
                    ? "岗位日薪下限低于最低日薪要求" : "岗位月薪下限低于最低月薪要求");
        }
    }

    /**
     * An unclear field rejects the job only when this run's decision depends on it. The site itself
     * truncates long job titles, so that signal is recorded without ending screening.
     */
    private static void markUncertainFields(List<String> uncertain, ScreenshotRules rules,
                                            List<String> mismatches, List<String> warnings) {
        LinkedHashSet<String> blocking = new LinkedHashSet<>();
        LinkedHashSet<String> advisory = new LinkedHashSet<>();
        boolean unknownField = false;
        for (String field : uncertain) {
            String label = UNCERTAIN_LABELS.get(field);
            if (label == null) {
                unknownField = true;
                continue;
            }
            boolean decisionDependsOnField = SALARY_FIELDS.contains(field)
                    || (field.equals("city") && !rules.cities().isEmpty())
                    || (field.equals("graduationYears") && rules.graduationYear() != null);
            (decisionDependsOnField ? blocking : advisory).add(label);
        }
        for (String label : blocking) {
            mismatches.add("模型标记" + label + "识别不清或存在歧义，按识别失败处理");
        }
        for (String label : advisory) {
            warnings.add("模型标记" + label + "识别不清，按提醒保留");
        }
        if (unknownField) warnings.add("模型标记了未识别的字段，按提醒保留");
    }

    private static SalaryObservation parseSalary(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("，", "").replace(",", "");
        Matcher matcher = SALARY.matcher(normalized);
        if (!matcher.matches()) return null;
        String firstScale = matcher.group(2);
        String secondScale = matcher.group(4);
        if (matcher.group(3) != null) {
            if (firstScale.isEmpty() && secondScale != null && !secondScale.isEmpty()) firstScale = secondScale;
            if (secondScale == null || secondScale.isEmpty()) secondScale = firstScale;
        }
        BigDecimal minimum = scale(new BigDecimal(matcher.group(1)), firstScale);
        BigDecimal maximum = matcher.group(3) == null ? minimum : scale(new BigDecimal(matcher.group(3)), secondScale);
        if (minimum.compareTo(maximum) > 0) return null;
        String period = matcher.group(5);
        String unit = "UNKNOWN";
        if ("天".equals(period) || "日".equals(period)) unit = "DAY";
        else if ("月".equals(period)) unit = "MONTH";
        else if (period != null) unit = "UNSUPPORTED";
        else if (period == null && (!firstScale.isEmpty() || (secondScale != null && !secondScale.isEmpty()))) unit = "MONTH";
        return new SalaryObservation(minimum, maximum, unit);
    }

    private static BigDecimal scale(BigDecimal amount, String unit) {
        if ("k".equals(unit)) return amount.multiply(BigDecimal.valueOf(1000));
        if ("万".equals(unit)) return amount.multiply(BigDecimal.valueOf(10000));
        return amount;
    }

    private static boolean equivalentCity(String requested, String observed) {
        return requested.trim().replaceFirst("市$", "").equalsIgnoreCase(observed.trim().replaceFirst("市$", ""));
    }

    private static boolean matchesRoleTerm(String title, String term) {
        // JavaScript is a different language; matching the substring "java" is not evidence of Java work.
        if (term.equals("java")) return JAVA_TERM.matcher(title).find();
        return title.contains(term);
    }

    private static boolean hasSubstantiveInformation(ScreenshotFields fields) {
        return !missingText(fields.jobTitle()) || !missingText(fields.company()) || !missingText(fields.city())
                || !missingText(fields.salaryText()) || fields.salaryMin() != null || fields.salaryMax() != null
                || (!missingText(fields.salaryUnit()) && !fields.salaryUnit().equals("UNKNOWN"))
                || fields.daysPerWeek() != null || fields.durationMonths() != null || !missingText(fields.education())
                || !fields.skills().isEmpty() || !fields.graduationYears().isEmpty() || !missingText(fields.description());
    }

    private static boolean missingText(String text) { return text == null || text.isBlank(); }

    private static void missing(List<String> warnings, String field) {
        warnings.add(field + "缺失，按符合处理");
    }

    private record SalaryObservation(BigDecimal minimum, BigDecimal maximum, String unit) { }
}
