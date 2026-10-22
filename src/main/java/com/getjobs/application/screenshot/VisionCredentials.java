package com.getjobs.application.screenshot;

/** Credentials are deliberately never serialized by the screenshot API. */
public record VisionCredentials(String baseUrl, String model, String apiKey) {
    @Override
    public String toString() {
        return "VisionCredentials[baseUrl=<configured>, model=" + model + ", apiKey=<redacted>]";
    }
}
