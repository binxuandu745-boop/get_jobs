package com.getjobs.application.screenshot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.getjobs.application.screenshot.PageDiagnosticResult.State.*;
import static org.junit.jupiter.api.Assertions.*;

class PageDiagnosticResultTest {
    @ParameterizedTest
    @ValueSource(strings = {"没有更多了", "没有更多岗位了", "已经到底了", "暂无相关职位", "没有符合条件的职位", "未找到相关岗位，换个搜索条件试试"})
    void acceptsOnlyExplicitListEndOrEmptyResultText(String visible) {
        var result = PageDiagnosticResult.parse(output("EXHAUSTED", visible));
        assertEquals(EXHAUSTED, result.state());
        assertEquals(List.of(visible), result.evidence());
    }

    @ParameterizedTest
    @ValueSource(strings = {"没有新卡片", "列表空白", "滚动条已到底", "卡片仍显示15个", "没有更多岗位的提示未出现", "没有更多职位的提示", "未知", "本次超时60000毫秒"})
    void doesNotInferEndFromAbsentNewCardsOrModelExplanation(String text) {
        assertEquals(UNKNOWN, PageDiagnosticResult.parse(output("EXHAUSTED", text)).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"安全验证", "当前IP地址可能存在异常访问行为", "请完成以下人机验证", "访问受限", "您的账号已封禁", "操作异常", "请完成验证码后继续"})
    void securityEvidenceOverridesAnExhaustedClaim(String text) {
        var result = PageDiagnosticResult.parse(output("EXHAUSTED", "没有更多了", text));
        assertEquals(SECURITY_VERIFICATION, result.state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXHAUSTED", "SECURITY_VERIFICATION", "LOADING", "UNKNOWN"})
    void emptyEvidenceNeverConfirmsAnyState(String state) {
        assertEquals(UNKNOWN, PageDiagnosticResult.parse(output(state)).state());
    }

    @Test
    void loadingRequiresVisibleEvidenceAndCannotClearConflictingEndText() {
        assertEquals(LOADING, PageDiagnosticResult.parse(output("LOADING", "正在加载，请稍候")).state());
        assertEquals(UNKNOWN, PageDiagnosticResult.parse(output("LOADING", "列表空白")).state());
        assertEquals(UNKNOWN, PageDiagnosticResult.parse(output("EXHAUSTED", "没有更多了", "加载中")).state());
        assertEquals(UNKNOWN, PageDiagnosticResult.parse(output("EXHAUSTED", "没有更多了，加载中")).state());
        assertEquals(UNKNOWN, PageDiagnosticResult.parse(output("LOADING", "没有更多了", "加载中")).state());
    }

    @Test
    void claimedSecurityWithoutSpecificEvidenceStaysUnconfirmed() {
        assertEquals(UNKNOWN, PageDiagnosticResult.parse(output("SECURITY_VERIFICATION", "没有新卡片")).state());
        assertEquals(SECURITY_VERIFICATION, PageDiagnosticResult.parse(output("UNKNOWN", "安全验证")).state());
        assertEquals(UNKNOWN, PageDiagnosticResult.parse(output("UNKNOWN", "没有更多了")).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"state\":\"EXHAUSTED\",\"state\":\"UNKNOWN\",\"evidence\":[\"没有更多了\"]}",
            "{\"state\":\"EXHAUSTED\",\"evidence\":[\"没有更多了\"]}{}",
            "{\"state\":\"EXHAUSTED\",\"evidence\":[\"没有更多了\"],\"action\":\"continue\"}",
            "{\"state\":\"SAFE\",\"evidence\":[\"没有更多了\"]}",
            "{\"state\":\"exhausted\",\"evidence\":[\"没有更多了\"]}",
            "{\"state\":\"EXHAUSTED\"}",
            "{\"state\":\"EXHAUSTED\",\"evidence\":null}",
            "{\"state\":\"EXHAUSTED\",\"evidence\":\"没有更多了\"}",
            "{\"state\":\"EXHAUSTED\",\"evidence\":[123]}",
            "{\"state\":\"EXHAUSTED\",\"evidence\":[\" \" ]}",
            "{\"state\":\"EXHAUSTED\",\"evidence\":[\"没有更多了\\nsecret\"]}",
            "[]", "null", "not-json", ""
    })
    void rejectsMalformedOrUntrustedOutputWithoutLeakingIt(String text) {
        var failure = assertThrows(IllegalArgumentException.class, () -> PageDiagnosticResult.parse(text));
        assertEquals("页面诊断输出格式无效", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void enforcesBoundsAndImmutableEvidence() {
        assertThrows(IllegalArgumentException.class, () -> PageDiagnosticResult.parse(null));
        assertThrows(IllegalArgumentException.class, () -> PageDiagnosticResult.parse(" ".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> PageDiagnosticResult.parse(output("EXHAUSTED", "字".repeat(201))));
        assertThrows(IllegalArgumentException.class, () -> PageDiagnosticResult.parse(output("EXHAUSTED", "1", "2", "3", "4", "5", "6", "7")));
        var result = PageDiagnosticResult.parse(output("EXHAUSTED", "没有更多了"));
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().add("override"));
    }

    private static String output(String state, String... text) {
        return new JSONObject().put("state", state).put("evidence", new JSONArray(List.of(text))).toString();
    }
}
