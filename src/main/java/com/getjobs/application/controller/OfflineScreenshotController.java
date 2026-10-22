package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonParser;
import com.getjobs.application.screenshot.OfflineScreenshotService;
import com.getjobs.application.screenshot.ScreenshotRules;
import com.getjobs.application.screenshot.ScreenshotVisionConfigService;
import com.getjobs.application.screenshot.ScreenshotVisionException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Local upload-and-analysis API. Intentionally has no browser or delivery-service dependency. */
@RestController
@RequestMapping("/api/offline-screenshots")
@CrossOrigin(origins = {"http://localhost:9527", "http://127.0.0.1:9527", "http://localhost:6866",
        "http://127.0.0.1:6866", "http://localhost:3000", "http://127.0.0.1:3000"},
        allowedHeaders = {"Content-Type", "X-Offline-Screenshot"})
public class OfflineScreenshotController {
    private final OfflineScreenshotService service;
    private final ScreenshotVisionConfigService config;
    private final ObjectMapper mapper;

    public OfflineScreenshotController(OfflineScreenshotService service, ScreenshotVisionConfigService config,
                                       ObjectMapper mapper) {
        this.service = service;
        this.config = config;
        this.mapper = mapper;
    }

    @GetMapping("/config")
    public ResponseEntity<?> configuration(HttpServletRequest request) {
        requireLocal(request, false);
        return ResponseEntity.ok(Map.of("success", true, "data", config.publicConfig()));
    }

    @PostMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> configure(@RequestBody ScreenshotVisionConfigService.ConfigUpdate update,
                                       HttpServletRequest request) {
        requireLocal(request, true);
        return ResponseEntity.ok(Map.of("success", true, "data", config.save(update)));
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyze(@RequestParam(value = "file", required = false) List<MultipartFile> legacyFiles,
                                    @RequestParam(value = "files", required = false) List<MultipartFile> files,
                                    @RequestParam(value = "rules", required = false) String rulesJson,
                                    @RequestParam(value = "consent", defaultValue = "false") boolean consent,
                                    HttpServletRequest request) throws IOException {
        requireLocal(request, true);
        if (!consent) throw new IllegalArgumentException("请先确认允许将本次截图发送到已配置的AI服务");
        if (legacyFiles != null && files != null) {
            throw new IllegalArgumentException("请使用files提交同一岗位截图，不能同时提交file和files");
        }
        if (legacyFiles != null && legacyFiles.size() != 1) {
            throw new IllegalArgumentException("旧版file参数只接受一张截图，多图请使用files");
        }
        List<MultipartFile> selected = files == null ? legacyFiles : files;
        if (selected == null || selected.isEmpty() || selected.size() > OfflineScreenshotService.MAX_IMAGES) {
            throw new IllegalArgumentException("同一岗位请选择1至6张截图");
        }
        long total = 0;
        for (MultipartFile file : selected) {
            if (file == null || file.isEmpty() || file.getSize() > OfflineScreenshotService.MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("每张截图必须非空，且不得超过8MiB");
            }
            total += file.getSize();
        }
        if (total > OfflineScreenshotService.MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("同一岗位截图总大小不得超过24MiB");
        }
        ScreenshotRules rules = ScreenshotRules.defaults();
        if (rulesJson != null && !rulesJson.isBlank()) {
            if (rulesJson.length() > 8192) throw new IllegalArgumentException("筛选条件过长");
            try {
                JsonNode supplied = mapper.readerFor(JsonNode.class)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(rulesJson);
                if (supplied == null || !supplied.isObject()) throw new IllegalArgumentException();
                ObjectNode merged = mapper.valueToTree(ScreenshotRules.defaults());
                merged.setAll((ObjectNode) supplied);
                rules = mapper.readerFor(ScreenshotRules.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(merged.toString());
                if (rules == null) throw new IllegalArgumentException();
            } catch (IOException | IllegalArgumentException invalid) {
                throw new IllegalArgumentException("筛选条件格式或取值无效，请检查薪资、城市和毕业年份");
            }
        }
        // Keep the single-image method for old integrations; a multi-image set is one analysis.
        if (selected.size() == 1) {
            MultipartFile file = selected.getFirst();
            return ResponseEntity.ok(Map.of("success", true,
                    "data", service.analyze(file.getBytes(), file.getOriginalFilename(), rules)));
        }
        List<OfflineScreenshotService.Upload> uploads = new ArrayList<>();
        for (MultipartFile file : selected) {
            uploads.add(new OfflineScreenshotService.Upload(file.getBytes(), file.getOriginalFilename()));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", service.analyze(uploads, rules)));
    }

    @GetMapping("/results/{id}")
    public ResponseEntity<?> result(@PathVariable String id, HttpServletRequest request) throws IOException {
        requireLocal(request, false);
        try {
            return ResponseEntity.ok(Map.of("success", true, "data", service.result(id)));
        } catch (FileNotFoundException | NoSuchFileException missing) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "未找到该离线识别记录");
        }
    }

    private void requireLocal(HttpServletRequest request, boolean mutation) {
        String remote = request.getRemoteAddr();
        String host = request.getServerName();
        boolean loopback = remote != null && (remote.startsWith("127.") || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote));
        boolean localHost = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
        if (!loopback || !localHost || (mutation && !"1".equals(request.getHeader("X-Offline-Screenshot")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "此接口仅供本机截图页面使用");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException failure) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", failure.getMessage() == null
                ? "截图或配置参数无效" : failure.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidJson() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "请求格式无效，请检查输入内容");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "截图过大：每张最多8MiB，同一岗位最多6张、合计24MiB");
    }

    @ExceptionHandler(ScreenshotVisionException.class)
    public ResponseEntity<?> visionUnavailable(ScreenshotVisionException failure) {
        return error(HttpStatus.CONFLICT, failure.getCode(), failure.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> storageUnavailable() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "无法保存或读取离线识别记录，请检查本地目录权限和磁盘空间");
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "code", code, "message", message));
    }
}
