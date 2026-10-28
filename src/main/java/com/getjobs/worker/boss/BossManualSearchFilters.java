package com.getjobs.worker.boss;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The supported, URL-visible BOSS search choices captured after the user finishes selecting them.
 * Tracking parameters and page-only state are intentionally not interpreted as search filters.
 */
public final class BossManualSearchFilters {
    private static final Set<String> CODE_FILTERS = Set.of("city", "position", "jobType", "experience",
            "salary", "degree", "scale", "industry", "stage");
    private final BossSearchContext selectedContext;
    private final Map<String, String> selected;
    private final boolean cityChanged;
    private final boolean queryChanged;

    private BossManualSearchFilters(BossSearchContext initial, BossSearchContext selectedContext) {
        validateCodes(initial.filters());
        validateCodes(selectedContext.filters());
        this.selectedContext = selectedContext;
        this.selected = selectedContext.filters();
        this.cityChanged = !initial.filters().get("city").equals(selected.get("city"));
        this.queryChanged = !initial.filters().get("query").equals(selected.get("query"));
    }

    /** Both URLs must be valid BOSS search pages with no conflicting repeated filter parameters. */
    public static BossManualSearchFilters capture(String initialUrl, String selectedUrl) {
        return new BossManualSearchFilters(BossSearchContext.fromUrl(initialUrl),
                BossSearchContext.fromUrl(selectedUrl));
    }

    /** Validate raw configuration before buildSearchUrl concatenates unescaped codes. */
    public static void validateInitialConfig(BossConfig config) {
        if (config == null) throw new IllegalArgumentException("Boss 搜索配置不能为空");
        validateRawCodes("city", config.getCityCode());
        validateRawCode("position", config.getPosition());
        validateRawCode("jobType", config.getJobType());
        validateRawCodes("experience", config.getExperience());
        validateRawCodes("salary", config.getSalary());
        validateRawCodes("degree", config.getDegree());
        validateRawCodes("scale", config.getScale());
        validateRawCodes("industry", config.getIndustry());
        validateRawCodes("stage", config.getStage());
    }

    static void normalizeSearchInputs(BossConfig config) {
        if (config == null) throw new IllegalArgumentException("Boss 搜索配置不能为空");
        List<String> cities = config.getCityCode();
        if (cities == null || cities.stream().noneMatch(value -> value != null && !value.isBlank())) {
            config.setCityCode(List.of("0"));
        } else if (cities.stream().anyMatch(value -> value == null || value.isBlank())) {
            config.setCityCode(cities.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).toList());
        }
        List<String> keywords = config.getKeywords();
        if (keywords == null || keywords.stream().noneMatch(value -> value != null && !value.isBlank())) {
            config.setKeywords(List.of(""));
        } else if (keywords.stream().anyMatch(Objects::isNull)) {
            config.setKeywords(keywords.stream().map(value -> value == null ? "" : value).toList());
        }
    }

    private static void validateRawCodes(String name, List<String> values) {
        if (values != null) for (String value : values) validateRawCode(name, value);
    }

    private static void validateRawCode(String name, String value) {
        if (value != null && !value.isEmpty()
                && !value.matches("[A-Za-z0-9_-]+(?:,[A-Za-z0-9_-]+)*")) {
            throw new IllegalArgumentException("Boss 搜索筛选代码无法安全识别：" + name);
        }
    }

    /**
     * Carry the selected filters into each fresh round. An unchanged city or query is left to the
     * existing round city and configured keywords; an explicit change takes precedence.
     */
    public void applyTo(BossConfig config) {
        if (config == null) throw new IllegalArgumentException("Boss 搜索配置不能为空");
        config.setPosition(selected.get("position"));
        config.setJobType(selected.get("jobType"));
        config.setExperience(codes("experience"));
        config.setSalary(codes("salary"));
        config.setDegree(codes("degree"));
        config.setScale(codes("scale"));
        config.setIndustry(codes("industry"));
        config.setStage(codes("stage"));
        if (cityChanged) {
            List<String> cities = codes("city");
            config.setCityCode(cities.isEmpty() ? List.of("0") : cities);
        }
        if (queryChanged) config.setKeywords(List.of(selected.get("query")));
        // Fresh automatic rounds reload the database configuration independently.
        normalizeSearchInputs(config);
    }

    public boolean cityChanged() { return cityChanged; }

    /** Empty means an explicitly unlimited location when {@link #cityChanged()} is true. */
    public String selectedCity() { return selected.get("city"); }

    /** Compare supported filters semantically, ignoring URL order and unrelated tracking keys. */
    public boolean matchesSelectedUrl(String url) { return selectedContext.matches(url); }

    private List<String> codes(String name) {
        String value = selected.get(name);
        return value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
    }

    private static void validateCodes(Map<String, String> filters) {
        // Existing Boss.buildSearchUrl concatenates codes without URL encoding. Restrict them to
        // safe code characters so a decoded '&', '#', or '=' cannot become an extra query field.
        for (String name : CODE_FILTERS) {
            String value = filters.get(name);
            if (!value.isEmpty() && !value.matches("[A-Za-z0-9_-]+(?:,[A-Za-z0-9_-]+)*")) {
                throw new IllegalArgumentException("Boss 搜索筛选代码无法安全识别：" + name);
            }
        }
    }
}
