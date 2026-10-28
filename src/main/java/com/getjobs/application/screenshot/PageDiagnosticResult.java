package com.getjobs.application.screenshot;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Observations only. No model output can instruct browser actions or override a local safety stop. */
public record PageDiagnosticResult(State state, List<String> evidence) {
    public enum State { EXHAUSTED, SECURITY_VERIFICATION, LOADING, UNKNOWN }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_TEXT_LENGTH = 4096;
    private static final Set<String> FIELDS = Set.of("state", "evidence");
    private static final List<String> SAFETY_MARKERS = List.of("安全验证", "人机验证", "机器人验证", "验证码",
            "异常访问", "访问异常", "访问过于频繁", "访问频繁", "操作频繁", "操作异常", "行为异常",
            "访问受限", "限制访问", "访问被拒绝", "账号异常", "账户异常", "账号封禁", "已封禁",
            "账号被封", "完成验证", "请先验证", "滑块验证", "扫码验证");
    private static final List<String> END_MARKERS = List.of("没有更多了", "没有更多职位", "没有更多岗位",
            "已经到底", "已到底", "到底了", "暂无职位", "暂无岗位", "暂无相关职位", "暂无相关岗位",
            "没有找到相关职位", "没有找到相关岗位", "未找到相关职位", "未找到相关岗位",
            "没有符合条件的职位", "没有符合条件的岗位", "暂时没有符合条件的职位", "暂时没有符合条件的岗位",
            "未找到符合条件的职位", "未找到符合条件的岗位", "无匹配职位", "无匹配岗位",
            "暂无搜索结果", "没有搜索结果", "暂无相关结果", "已展示全部职位", "全部职位已展示", "所有职位已展示");
    private static final List<String> LOADING_MARKERS = List.of("加载中", "正在加载", "加载更多中", "请稍候", "请稍等");

    public PageDiagnosticResult {
        if (state == null || evidence == null) throw invalid();
        evidence = List.copyOf(evidence);
    }

    /** Rejects malformed output; unsupported or contradictory observations remain UNKNOWN. */
    public static PageDiagnosticResult parse(String modelText) {
        if (modelText == null || modelText.isBlank() || modelText.length() > MAX_TEXT_LENGTH) throw invalid();
        final JsonNode root;
        try { root = MAPPER.readTree(modelText.trim()); }
        catch (Exception malformed) { throw invalid(); }
        if (root == null || !root.isObject() || root.size() != 2) throw invalid();
        var names = root.fieldNames();
        while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
        JsonNode stateNode = root.get("state");
        if (stateNode == null || !stateNode.isTextual()) throw invalid();
        final State declared;
        try { declared = State.valueOf(stateNode.textValue()); }
        catch (IllegalArgumentException unsupported) { throw invalid(); }
        JsonNode evidenceNode = root.get("evidence");
        if (evidenceNode == null || !evidenceNode.isArray() || evidenceNode.size() > 6) throw invalid();
        List<String> observations = new ArrayList<>();
        for (JsonNode item : evidenceNode) {
            if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > 200
                    || item.textValue().chars().anyMatch(Character::isISOControl)) throw invalid();
            observations.add(item.textValue().trim());
        }
        List<String> evidence = List.copyOf(observations);
        if (evidence.isEmpty()) return new PageDiagnosticResult(State.UNKNOWN, evidence);
        // Safety always wins over exhaustion, even when the model supplied the wrong state.
        if (evidence.stream().anyMatch(text -> SAFETY_MARKERS.stream().anyMatch(text::contains))) {
            return new PageDiagnosticResult(State.SECURITY_VERIFICATION, evidence);
        }
        boolean loading = evidence.stream().anyMatch(text -> LOADING_MARKERS.stream().anyMatch(text::contains));
        boolean exhausted = evidence.stream().anyMatch(PageDiagnosticResult::explicitEndText);
        State verified = switch (declared) {
            case EXHAUSTED -> exhausted && !loading ? State.EXHAUSTED : State.UNKNOWN;
            case LOADING -> loading && !exhausted ? State.LOADING : State.UNKNOWN;
            // A claimed safety stop without readable evidence is unconfirmed, never cleared.
            case SECURITY_VERIFICATION, UNKNOWN -> State.UNKNOWN;
        };
        return new PageDiagnosticResult(verified, evidence);
    }

    private static boolean explicitEndText(String text) {
        if (List.of("未出现", "没有出现", "未显示", "没有显示", "未看到", "没有看到", "并非", "不是")
                .stream().anyMatch(text::contains)) return false;
        for (String marker : END_MARKERS) {
            if (!text.startsWith(marker)) continue;
            String tail = text.substring(marker.length()).trim();
            if (tail.startsWith("了")) tail = tail.substring(1).trim();
            if (tail.isEmpty() || "，。！？；：,.!?:;".indexOf(tail.charAt(0)) >= 0) return true;
        }
        return false;
    }

    private static IllegalArgumentException invalid() {
        // Never expose provider output, page text or image content through exception messages.
        return new IllegalArgumentException("页面诊断输出格式无效");
    }
}
