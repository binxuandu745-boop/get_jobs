package com.getjobs.application.screenshot;

import java.util.List;
import java.util.Set;

/** Local Java screening result only; MATCH is not permission to send anything. */
public record ScreenshotEvaluation(String decision, List<String> reasons, List<String> warnings) {
    private static final Set<String> DECISIONS = Set.of("MATCH", "MISMATCH", "REVIEW", "ERROR");

    public ScreenshotEvaluation {
        if (!DECISIONS.contains(decision)) throw new IllegalArgumentException("不支持的识别结果状态");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
