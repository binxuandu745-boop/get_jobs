package com.getjobs.application.screenshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/** Fixed local output directory; the API never accepts a filesystem path or image URL. */
@Component
public class ScreenshotResultStore {
    private final Path root;
    private final ObjectMapper mapper;

    @Autowired
    public ScreenshotResultStore(ObjectMapper mapper) {
        this(Path.of(".local", "offline-screenshots"), mapper);
    }

    ScreenshotResultStore(Path root, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public void save(String id, byte[] image, String extension, ScreenshotAnalysisResult result) throws IOException {
        save(id, List.of(new SourceImage(image, extension)), result);
    }

    public void save(String id, List<SourceImage> images, ScreenshotAnalysisResult result) throws IOException {
        if (images == null || images.isEmpty() || images.size() > VisionImage.MAX_IMAGES) {
            throw new IllegalArgumentException("同一岗位请选择1至6张截图");
        }
        Path directory = directory(id);
        Files.createDirectories(root);
        Files.createDirectory(directory);
        for (int index = 0; index < images.size(); index++) {
            SourceImage image = images.get(index);
            // Keep source.png/jpg for the first image to preserve existing result paths.
            String stem = index == 0 ? "source" : "source-" + (index + 1);
            String imageName = stem + ("png".equals(image.extension()) ? ".png" : ".jpg");
            Files.write(directory.resolve(imageName), image.bytes(), StandardOpenOption.CREATE_NEW);
        }
        Path staged = directory.resolve("result.pending.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(staged.toFile(), result);
        try {
            Files.move(staged, directory.resolve("result.json"), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(staged, directory.resolve("result.json"));
        }
    }

    public record SourceImage(byte[] bytes, String extension) { }

    public ScreenshotAnalysisResult read(String id) throws IOException {
        return mapper.readValue(directory(id).resolve("result.json").toFile(), ScreenshotAnalysisResult.class);
    }

    private Path directory(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("识别记录ID无效");
        }
        return root.resolve(id);
    }
}
