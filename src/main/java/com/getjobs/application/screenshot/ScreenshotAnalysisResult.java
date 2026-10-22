package com.getjobs.application.screenshot;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** No credentials, raw provider responses, browser IDs or delivery commands are retained. */
public record ScreenshotAnalysisResult(String id, String status, String createdAt, String sourceName,
                                       String imageSha256, ScreenshotFields fields,
                                       ScreenshotEvaluation evaluation, String errorCode,
                                       String errorMessage, String model, List<ScreenshotSource> sources) {
    public ScreenshotAnalysisResult {
        // Old single-image records have no sources property and remain readable.
        sources = sources == null ? List.of(new ScreenshotSource(1, sourceName, imageSha256))
                : List.copyOf(sources);
    }

    public ScreenshotAnalysisResult(String id, String status, String createdAt, String sourceName,
                                    String imageSha256, ScreenshotFields fields,
                                    ScreenshotEvaluation evaluation, String errorCode,
                                    String errorMessage, String model) {
        this(id, status, createdAt, sourceName, imageSha256, fields, evaluation,
                errorCode, errorMessage, model, null);
    }

    @JsonProperty(value = "imageCount", access = JsonProperty.Access.READ_ONLY)
    public int imageCount() { return sources.size(); }
}
