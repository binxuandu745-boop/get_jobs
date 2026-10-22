package com.getjobs.application.screenshot;

/** Ordered provenance for one original image in a single job's screenshot set. */
public record ScreenshotSource(int index, String sourceName, String imageSha256) { }
