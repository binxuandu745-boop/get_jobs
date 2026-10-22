package com.getjobs.application.screenshot;

import java.util.List;

/** Recognizes a user-supplied image only; never reads or operates a browser. */
@FunctionalInterface
public interface VisionModelClient {
    String recognize(byte[] image, String mimeType);

    /** Existing single-image implementations remain usable without silently dropping images. */
    default String recognize(List<VisionImage> images) {
        List<VisionImage> checked = VisionImage.checkedImages(images);
        if (checked.size() != 1) {
            throw new ScreenshotVisionException("MULTI_IMAGE_UNSUPPORTED", "当前截图识别客户端不支持多图识别");
        }
        VisionImage image = checked.getFirst();
        return recognize(image.bytes(), image.mimeType());
    }
}
