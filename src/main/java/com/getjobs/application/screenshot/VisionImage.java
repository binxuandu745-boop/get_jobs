package com.getjobs.application.screenshot;

import java.util.ArrayList;
import java.util.List;

/** One user-supplied screenshot in upload order, validated before a model request. */
public record VisionImage(byte[] bytes, String mimeType) {
    public static final int MAX_IMAGES = 6;
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 24 * 1024 * 1024;

    static List<VisionImage> checkedImages(List<VisionImage> images) {
        if (images == null || images.isEmpty() || images.size() > MAX_IMAGES) {
            throw new ScreenshotVisionException("INVALID_IMAGE", "每次需上传1至6张截图");
        }
        List<VisionImage> snapshot = new ArrayList<>(images);
        long totalBytes = 0;
        for (VisionImage image : snapshot) {
            if (image == null || image.bytes() == null || image.bytes().length == 0
                    || image.bytes().length > MAX_IMAGE_BYTES) {
                throw new ScreenshotVisionException("INVALID_IMAGE", "每张截图不能为空或超过8MiB");
            }
            if (!"image/png".equals(image.mimeType()) && !"image/jpeg".equals(image.mimeType())) {
                throw new ScreenshotVisionException("INVALID_IMAGE", "仅接受经过校验的PNG或JPEG截图");
            }
            totalBytes += image.bytes().length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new ScreenshotVisionException("INVALID_IMAGE", "截图总大小不能超过24MiB");
            }
        }
        return List.copyOf(snapshot);
    }
}
