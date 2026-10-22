package com.getjobs.application.screenshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Shared image validation, recognition and storage for manual uploads and BOSS captures. */
@Service
public class OfflineScreenshotService {
    public static final int MAX_IMAGE_BYTES = VisionImage.MAX_IMAGE_BYTES;
    public static final int MAX_IMAGES = VisionImage.MAX_IMAGES;
    public static final int MAX_TOTAL_BYTES = VisionImage.MAX_TOTAL_BYTES;
    private static final Logger log = LoggerFactory.getLogger(OfflineScreenshotService.class);
    private final VisionModelClient client;
    private final ScreenshotVisionConfigService config;
    private final ScreenshotResultStore store;
    private final Semaphore activeAnalysis = new Semaphore(1);

    public OfflineScreenshotService(VisionModelClient client, ScreenshotVisionConfigService config,
                                    ScreenshotResultStore store) {
        this.client = client;
        this.config = config;
        this.store = store;
    }

    public ScreenshotAnalysisResult analyze(byte[] image, String sourceName, ScreenshotRules rules) throws IOException {
        return analyze(List.of(new Upload(image, sourceName)), rules);
    }

    /** All uploads are consecutive views of one job, not separate jobs or separate model calls. */
    public ScreenshotAnalysisResult analyze(List<Upload> uploads, ScreenshotRules rules) throws IOException {
        if (!activeAnalysis.tryAcquire()) {
            throw new ScreenshotVisionException("BUSY", "已有截图正在识别，请等待完成，避免重复付费调用");
        }
        try {
            validateSet(uploads);
            List<VisionImage> images = new ArrayList<>();
            List<ScreenshotResultStore.SourceImage> originals = new ArrayList<>();
            List<ScreenshotSource> sources = new ArrayList<>();
            for (int index = 0; index < uploads.size(); index++) {
                Upload upload = uploads.get(index);
                String format = validateImage(upload.image());
                String mimeType = "png".equals(format) ? "image/png" : "image/jpeg";
                images.add(new VisionImage(upload.image(), mimeType));
                originals.add(new ScreenshotResultStore.SourceImage(upload.image(), format));
                sources.add(new ScreenshotSource(index + 1, safeName(upload.sourceName()), sha256(upload.image())));
            }
            ScreenshotRules effectiveRules = rules == null ? ScreenshotRules.defaults() : rules;
            String id = UUID.randomUUID().toString();
            String model = config.publicConfig().model();
            ScreenshotSource first = sources.getFirst();
            ScreenshotAnalysisResult result;
            try {
                String extracted = images.size() == 1
                        ? client.recognize(images.getFirst().bytes(), images.getFirst().mimeType())
                        : client.recognize(List.copyOf(images));
                ScreenshotFields fields = new ScreenshotFieldParser().parse(extracted);
                ScreenshotEvaluation evaluation = new ScreenshotEvaluator().evaluate(fields, effectiveRules);
                result = new ScreenshotAnalysisResult(id, "COMPLETE", Instant.now().toString(),
                        first.sourceName(), first.imageSha256(), fields, evaluation, null, null, model, sources);
            } catch (ScreenshotVisionException failure) {
                result = failed(id, sources, model, failure.getCode(), failure.getMessage());
            } catch (IllegalArgumentException invalidFields) {
                String reason = validationReason(invalidFields);
                // Parser messages name a field or a rule only; model text never reaches this log.
                log.warn("截图识别输出未通过字段校验：{}（识别记录 {}）", reason, id);
                result = failed(id, sources, model, "INVALID_EXTRACTION",
                        "AI输出未通过字段校验：" + reason);
            }
            store.save(id, originals, result);
            return result;
        } finally {
            activeAnalysis.release();
        }
    }

    public ScreenshotAnalysisResult result(String id) throws IOException { return store.read(id); }

    public record Upload(byte[] image, String sourceName) { }

    private void validateSet(List<Upload> uploads) {
        if (uploads == null || uploads.isEmpty() || uploads.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("同一岗位请选择1至6张截图");
        }
        long total = 0;
        for (Upload upload : uploads) {
            if (upload == null || upload.image() == null || upload.image().length == 0
                    || upload.image().length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("每张截图必须为非空PNG/JPEG，且不得超过8MiB");
            }
            total += upload.image().length;
        }
        if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("同一岗位截图总大小不得超过24MiB");
    }

    private ScreenshotAnalysisResult failed(String id, List<ScreenshotSource> sources, String model,
                                             String code, String message) {
        ScreenshotSource first = sources.getFirst();
        return new ScreenshotAnalysisResult(id, "ERROR", Instant.now().toString(), first.sourceName(),
                first.imageSha256(), null, new ScreenshotEvaluation("MISMATCH",
                List.of("识别失败，按不符合条件处理：" + message), List.of()),
                code, message, model, sources);
    }

    /** Field-parser messages carry a field name or a rule name, never the model body; still bound them. */
    private static String validationReason(IllegalArgumentException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return "字段格式无效";
        String cleaned = message.replaceAll("[\\p{Cntrl}]", " ").trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private String validateImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("请选择非空的PNG/JPEG截图，大小不得超过8MiB");
        }
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("文件不是可识别的PNG/JPEG图片");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!"png".equals(format) && !"jpeg".equals(format) && !"jpg".equals(format)) {
                    throw new IllegalArgumentException("目前仅接受PNG/JPEG截图");
                }
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 16_000_000L) {
                    throw new IllegalArgumentException("截图尺寸无效或超过1600万像素，请裁剪后上传");
                }
                if (reader.read(0) == null) throw new IllegalArgumentException("图片损坏或无法解码");
                return "png".equals(format) ? "png" : "jpg";
            } finally { reader.dispose(); }
        } catch (IOException invalid) {
            throw new IllegalArgumentException("截图损坏或无法读取，请重新保存图片");
        }
    }

    private String safeName(String name) {
        if (name == null || name.isBlank()) return "uploaded-screenshot";
        String safe = name.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (safe.isBlank()) return "uploaded-screenshot";
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    private String sha256(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256不可用"); }
    }
}
