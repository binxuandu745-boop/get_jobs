package com.getjobs.worker.boss;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Semantic search filters only; tracking, selected job IDs and query ordering are immaterial. */
public final class BossSearchContext {
    private static final Set<String> FILTERS = Set.of("city", "query", "jobType", "experience", "salary",
            "degree", "scale", "industry", "stage");
    private final Map<String, String> filters;

    private BossSearchContext(Map<String, String> filters) { this.filters = Map.copyOf(filters); }

    public static BossSearchContext fromUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost(), path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || !("www.zhipin.com".equalsIgnoreCase(host) || "zhipin.com".equalsIgnoreCase(host))
                    || !(uri.getPort() == -1 || uri.getPort() == 443)
                    || !("/web/geek/jobs".equals(path) || "/web/geek/jobs/".equals(path))) {
                throw new IllegalArgumentException("不是有效的Boss岗位搜索页面");
            }
            Map<String, String> values = new HashMap<>();
            FILTERS.forEach(key -> values.put(key, ""));
            Set<String> seen = new java.util.HashSet<>();
            if (uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&")) {
                    int separator = parameter.indexOf('=');
                    String key = decode(separator < 0 ? parameter : parameter.substring(0, separator));
                    String value = decode(separator < 0 ? "" : parameter.substring(separator + 1));
                    if (!FILTERS.contains(key)) continue;
                    String normalized = "query".equals(key) ? value.trim() : normalizeFilter(value);
                    if (!seen.add(key) && !values.get(key).equals(normalized)) {
                        throw new IllegalArgumentException("Boss搜索筛选包含冲突的重复参数");
                    }
                    values.put(key, normalized);
                }
            }
            return new BossSearchContext(values);
        } catch (NullPointerException invalid) {
            throw new IllegalArgumentException("Boss搜索页面地址不能为空");
        }
    }

    public boolean matches(String url) {
        try { return filters.equals(fromUrl(url).filters); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }

    private static String normalizeFilter(String raw) {
        Set<String> values = Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
        if (values.contains("0")) return "";
        return values.stream().sorted().collect(Collectors.joining(","));
    }
}
