package com.getjobs.application.screenshot;

/** Safe, fixed diagnostics. Never attach upstream bodies, URLs, credentials or causes. */
public final class ScreenshotVisionException extends RuntimeException {
    private final String code;

    public ScreenshotVisionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
