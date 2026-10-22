package com.getjobs.application.controller;

import com.getjobs.application.screenshot.BossScreenshotSettingsService;
import com.getjobs.application.screenshot.ScreenshotVisionConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Local management of BOSS screenshot rules; saving never starts a delivery task. */
@RestController
@RequestMapping("/api/boss/screenshot-automation")
@CrossOrigin(origins = {"http://localhost:9527", "http://127.0.0.1:9527", "http://localhost:6866",
        "http://127.0.0.1:6866", "http://localhost:3000", "http://127.0.0.1:3000"},
        allowedHeaders = {"Content-Type", "X-Offline-Screenshot"})
public class BossScreenshotAutomationController {
    private final BossScreenshotSettingsService settings;
    private final ScreenshotVisionConfigService vision;

    public BossScreenshotAutomationController(BossScreenshotSettingsService settings,
                                               ScreenshotVisionConfigService vision) {
        this.settings = settings;
        this.vision = vision;
    }

    @GetMapping
    public ResponseEntity<?> current(HttpServletRequest request) {
        requireLocal(request, false);
        return response(settings.current());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> save(@RequestBody String body, HttpServletRequest request) {
        requireLocal(request, true);
        return response(settings.save(settings.parseSettings(body)));
    }

    private ResponseEntity<?> response(BossScreenshotSettingsService.Settings value) {
        return ResponseEntity.ok(Map.of("success", true, "data", value,
                "visionReady", vision.publicConfig().ready()));
    }

    private static void requireLocal(HttpServletRequest request, boolean mutation) {
        String remote = request.getRemoteAddr();
        String host = request.getServerName();
        boolean loopback = remote != null && (remote.startsWith("127.") || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote));
        boolean localHost = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
        if (!loopback || !localHost || (mutation && !"1".equals(request.getHeader("X-Offline-Screenshot")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "此接口仅供本机管理页面使用");
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("success", false, "code", "INVALID_SETTINGS",
                "message", "截图自动筛选配置格式或取值无效，请检查开关、薪资、城市和毕业年份"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception failure) {
        if (failure instanceof ResponseStatusException access) throw access;
        return ResponseEntity.internalServerError().body(Map.of("success", false, "code", "SETTINGS_UNAVAILABLE",
                "message", "无法读取或保存BOSS截图筛选配置，请检查本地配置后重试"));
    }
}
