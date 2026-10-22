package com.getjobs.worker.liepin;

import com.getjobs.worker.boss.JobTargetFilter;
import com.getjobs.worker.boss.SalaryThreshold;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evaluates only advertised job facts; UNKNOWN results require review before contacting anyone. */
public final class LiepinTargetFilter {
    public static final List<String> DEFAULT_ROLE_TERMS = List.of("Java", "后端", "服务端");
    public static final int DEFAULT_MONTHLY_MINIMUM = 4500;
    public static final int DEFAULT_DAILY_MINIMUM = 125;
    public static final boolean DEFAULT_REQUIRE_INTERNSHIP = false;

    private static final Set<String> TITLE_PLACEHOLDERS = Set.of(
            "岗位", "公司", "职位", "岗位名称", "职位名称", "未知", "未知岗位", "未知职位", "待确认");
    private static final Pattern NEGATED_INTERNSHIP = Pattern.compile(
            "不(?:接受|招(?:聘)?|支持|提供|考虑)\\s*实习|非实习|实习(?:生)?(?:勿投|免投)");
    private static final Pattern SALARY_FORMAT = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*([k万]?)(?:\\s*[-~至]\\s*(\\d+(?:\\.\\d+)?)\\s*([k万]?))?"
                    + "\\s*元?\\s*(?:/(?:月|天|日|年|小时)|月薪|年薪)?"
                    + "\\s*(?:[·•*×]\\s*\\d+\\s*薪|[（(]?\\s*\\d+\\s*薪\\s*[）)]?)?\\s*$");

    private LiepinTargetFilter() {}

    public enum Decision {
        ACCEPT, REJECT, UNKNOWN
    }

    public record Evaluation(Decision decision, String reason) {}

    public static Evaluation evaluate(String title, String description, String salary) {
        return evaluate(title, description, salary, DEFAULT_ROLE_TERMS,
                DEFAULT_REQUIRE_INTERNSHIP, DEFAULT_MONTHLY_MINIMUM, DEFAULT_DAILY_MINIMUM);
    }

    /** Daily and monthly minimums are independent; zero daily minimum does not enable daily offers. */
    public static Evaluation evaluate(String title, String description, String salary,
                                      List<String> roleTerms, boolean requireInternship,
                                      int monthlyMinimum, int dailyMinimum) {
        String name = title == null ? "" : title.strip();
        if (name.isEmpty() || TITLE_PLACEHOLDERS.contains(name)) {
            return new Evaluation(Decision.UNKNOWN, "岗位标题缺失或为占位内容，待确认");
        }
        if (!JobTargetFilter.matches(name, description, roleTerms, false)) {
            return new Evaluation(Decision.REJECT, "岗位标题不符合目标方向");
        }
        if (requireInternship && (NEGATED_INTERNSHIP.matcher(name).find()
                || (description != null && NEGATED_INTERNSHIP.matcher(description).find()))) {
            return new Evaluation(Decision.REJECT, "岗位明确不接受实习，不符合实习要求");
        }
        if (!JobTargetFilter.matches(name, description, roleTerms, requireInternship)) {
            return new Evaluation(Decision.REJECT, "岗位未明确标注实习，不符合实习要求");
        }

        String salaryText = salary == null ? "" : salary.strip().toLowerCase(Locale.ROOT);
        if (salaryText.isEmpty()) {
            return new Evaluation(Decision.UNKNOWN, "薪资缺失，待确认");
        }
        // Match the complete monetary expression so an ID or URL cannot qualify as salary.
        Matcher salaryFormat = SALARY_FORMAT.matcher(salaryText);
        if (!salaryFormat.matches()) {
            return new Evaluation(Decision.UNKNOWN, "薪资格式或计薪单位无法判别，待确认");
        }
        if (salaryFormat.group(3) != null) {
            String lowerUnit = salaryFormat.group(2);
            String upperUnit = salaryFormat.group(4);
            if ((!lowerUnit.isEmpty() && !lowerUnit.equals(upperUnit))
                    || new BigDecimal(salaryFormat.group(1)).compareTo(new BigDecimal(salaryFormat.group(3))) > 0) {
                return new Evaluation(Decision.UNKNOWN, "薪资范围单位混用或上下限不明确，待确认");
            }
        }
        if (salaryText.contains("/年") || salaryText.contains("年薪") || salaryText.contains("/小时")) {
            return new Evaluation(Decision.UNKNOWN, "年薪或时薪无法与月薪、日薪阈值直接比较，待确认");
        }
        boolean daily = salaryText.contains("/天") || salaryText.contains("/日");
        if (!daily && !salaryText.contains("k") && !salaryText.contains("万")
                && !salaryText.contains("/月") && !salaryText.contains("月薪")) {
            return new Evaluation(Decision.UNKNOWN, "薪资计薪单位无法判别，待确认");
        }
        if (daily && dailyMinimum <= 0) {
            return new Evaluation(Decision.REJECT, "未配置独立日薪阈值，跳过日薪岗位");
        }
        if (!SalaryThreshold.matches(salaryText, monthlyMinimum, dailyMinimum)) {
            return new Evaluation(Decision.REJECT, daily ? "日薪下限低于配置阈值" : "月薪下限低于配置阈值");
        }
        return new Evaluation(Decision.ACCEPT, "岗位方向与薪资下限符合配置");
    }
}
