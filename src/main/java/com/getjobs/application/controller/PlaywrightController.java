package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.manager.BrowserModeSwitchBusyException;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.LiepinJobService;
import com.getjobs.worker.service.Job51JobService;
import com.getjobs.worker.service.ZhilianJobService;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playwright管理控制器
 * 用于测试和管理Playwright实例
 */
@RestController
@RequestMapping("/api/playwright")
public class PlaywrightController {

    private final PlaywrightManager playwrightManager;
    private final BossJobService bossJobService;
    @Autowired
    private LiepinJobService liepinJobService;
    @Autowired
    private Job51JobService job51JobService;
    @Autowired
    private ZhilianJobService zhilianJobService;

    public PlaywrightController(PlaywrightManager playwrightManager, BossJobService bossJobService) {
        this.playwrightManager = playwrightManager;
        this.bossJobService = bossJobService;
    }

    /**
     * 获取Playwright状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        try {
            status.put("success", true);
            status.put("initialized", playwrightManager.isInitialized());
            status.put("platformMode", playwrightManager.getBrowserPlatformMode());
            status.put("switchingMode", playwrightManager.isSwitchingMode());
            boolean canSwitch = playwrightManager.canSwitchPlatformMode(this::hasRunningTasks);
            status.put("canSwitchMode", canSwitch);
            status.put("modeMessage", playwrightManager.isSwitchingMode() ? "正在切换助手浏览器模式，请等待"
                    : canSwitch ? "可切换；只重新打开助手浏览器，不自动启动投递"
                    : "浏览器正在处理任务，请等待或停止投递后再切换");
            status.put("cdpPort", playwrightManager.getCdpPort());
            status.put("hasBossPage", playwrightManager.getBossPage() != null);
            status.put("hasLiepinPage", playwrightManager.getLiepinPage() != null);
            status.put("has51jobPage", playwrightManager.getJob51Page() != null);
            status.put("hasZhilianPage", playwrightManager.getZhilianPage() != null);
            // 持久化上下文代表浏览器本身，不存在独立的Browser对象。
            status.put("hasBrowser", playwrightManager.getContext() != null);
            status.put("bossLoggedIn", playwrightManager.isLoggedIn("boss"));
            status.put("bossLoginState", playwrightManager.getBossLoginState());
            status.put("bossLoginMessage", playwrightManager.getBossLoginMessage());
            status.put("liepinLoggedIn", playwrightManager.isLoggedIn("liepin"));
            status.put("liepinLoginState", playwrightManager.getLiepinLoginState());
            status.put("liepinLoginMessage", playwrightManager.getLiepinLoginMessage());
            return ResponseEntity.ok(status);
        } catch (RuntimeException failure) {
            return ResponseEntity.internalServerError().body(Map.of("success", false,
                    "canSwitchMode", false, "message", "无法读取浏览器模式，请检查本地配置存储"));
        }
    }

    public record PlatformModeRequest(String mode) { }

    /** Selection is an explicit action; GET status and ordinary navigation never switch modes. */
    @PostMapping("/platform-mode")
    public ResponseEntity<Map<String, Object>> switchPlatformMode(@RequestBody PlatformModeRequest request) {
        if (request == null || request.mode() == null || request.mode().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请选择浏览器模式"));
        }
        try {
            boolean changed = playwrightManager.switchPlatformMode(request.mode(), this::hasRunningTasks);
            ResponseEntity<Map<String, Object>> response = getStatus();
            if (response.getBody() != null && response.getStatusCode().is2xxSuccessful()) {
                response.getBody().put("changed", changed);
                response.getBody().put("message", changed
                        ? "模式已切换并保存；未启动投递，请确认目标平台登录状态"
                        : "已处于该模式，无需重新打开浏览器");
            }
            return response;
        } catch (BrowserModeSwitchBusyException busy) {
            return ResponseEntity.status(409).body(Map.of("success", false, "message", busy.getMessage()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "模式只能选择BOSS、猎聘、51job或智联招聘"));
        } catch (IllegalStateException failed) {
            return ResponseEntity.internalServerError().body(Map.of("success", false,
                    "message", "切换未能完整完成，请刷新状态核对当前模式；不要重复提交"));
        }
    }

    /** Reopen the application's browser without starting a delivery task. */
    @PostMapping("/initialize")
    public ResponseEntity<Map<String, Object>> initialize() {
        playwrightManager.ensureReady();
        return getStatus();
    }

    /** Read-only login recheck; no navigation, credentials, or delivery task. */
    @PostMapping("/check-login")
    public ResponseEntity<Map<String, Object>> checkLogin() {
        if (!isTaskBusy()) {
            if (playwrightManager.isPlatformEnabled("boss")) playwrightManager.refreshBossLoginStatus();
            if (playwrightManager.isPlatformEnabled("liepin")) playwrightManager.refreshLiepinLoginStatus();
        }
        return getStatus();
    }

    /** Reopen only this application's browser, retaining its profile. */
    @PostMapping("/reopen")
    public ResponseEntity<Map<String, Object>> reopen() {
        if (isTaskBusy()) {
            return ResponseEntity.status(409).body(Map.of("success", false,
                    "message", "任务运行中，不能重开浏览器"));
        }
        playwrightManager.reopenBrowser();
        return getStatus();
    }

    private boolean isTaskBusy() {
        return hasRunningTasks() || playwrightManager.isPlaywrightBusy() || playwrightManager.isSwitchingMode()
                || playwrightManager.hasPlatformTaskReservations();
    }

    private boolean hasRunningTasks() {
        return bossJobService.isRunning() || (liepinJobService != null && liepinJobService.isRunning())
                || (job51JobService != null && job51JobService.isRunning())
                || (zhilianJobService != null && zhilianJobService.isRunning());
    }

    /**
     * 指纹自检：确认当前浏览器有没有暴露自动化特征。
     * <p>
     * 期望值：
     * navigator.webdriver = false（真实 Chrome 就是 false，undefined 反而是老式 stealth 脚本的痕迹）；
     * userAgentData 必须存在且 platform/brands 与 UA 自洽；cdc_ 残留为空。
     */
    @GetMapping("/fingerprint")
    public ResponseEntity<Map<String, Object>> fingerprint() {
        if (!playwrightManager.isPlatformEnabled("boss") || playwrightManager.getBossPage() == null) {
            return ResponseEntity.status(409).body(Map.of("success", false,
                    "message", "当前模式未启用BOSS页面"));
        }
        try {
            // Playwright 调用必须走专用线程，不能直接在 http-nio 线程上调
            Object raw = playwrightManager.callOnPlaywright(() -> playwrightManager.getBossPage().evaluate("""
                    () => {
                        const uaData = navigator.userAgentData;
                        return {
                            'navigator.webdriver': String(navigator.webdriver),
                            'userAgent': navigator.userAgent,
                            'navigator.platform': navigator.platform,
                            'uaData.platform': uaData ? uaData.platform : '(缺失-异常)',
                            'uaData.brands': uaData && uaData.brands
                                ? uaData.brands.map(b => b.brand + ' ' + b.version).join(', ')
                                : '(缺失-异常)',
                            'cdc_残留': Object.keys(window).filter(k => k.startsWith('cdc_')).join(',') || '(无)',
                            'url': location.href
                        };
                    }"""));
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("fingerprint", raw);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 诊断：对比"常驻的 bossPage 标签页"和"新开的受控标签页"加载同一地址的表现。
     * <p>
     * 现象是 bossPage 一直转圈加载不完，但在同一个浏览器里手动新开标签页打开同样的地址却正常。
     * 同浏览器同 profile 同指纹，差别只可能在标签页本身，这里把这个变量隔离出来。
     */
    @GetMapping("/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose(
            @RequestParam(defaultValue = "https://www.zhipin.com/web/geek/jobs?query=Java&city=101020100") String url) {
        if (!playwrightManager.isPlatformEnabled("boss")) {
            return ResponseEntity.status(409).body(Map.of("success", false,
                    "message", "当前模式未启用BOSS诊断"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String probeJs = """
                () => ({
                  readyState: document.readyState,
                  title: document.title,
                  url: location.href,
                  bodyLength: document.body ? document.body.innerHTML.length : 0,
                  jobCards: document.querySelectorAll('li.job-card-box, li.job-card-wrapper').length,
                  listContainers: document.querySelectorAll('ul.rec-job-list, ul.job-list-box, .job-list-box').length,
                  pending: performance.getEntriesByType('resource')
                             .filter(r => r.responseEnd === 0).map(r => r.name).slice(0, 8)
                })""";
        // 刻意不碰常驻的 bossPage：它一旦卡在"永久加载"，evaluate 会无限期阻塞（没有默认超时），
        // 整个诊断请求也会跟着挂死。这里只用全新的受控标签页做对照。
        String[] targets = {"https://www.zhipin.com", url};
        try {
            playwrightManager.callOnPlaywright(() -> {
                for (String target : targets) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    Page probe = null;
                    long start = System.currentTimeMillis();
                    try {
                        probe = playwrightManager.getContext().newPage();
                        probe.setDefaultTimeout(20_000);
                        probe.navigate(target, new Page.NavigateOptions()
                                .setTimeout(20_000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                        result.put("navigateMs", System.currentTimeMillis() - start);
                        probe.waitForTimeout(5_000);
                        result.put("currentUrl", probe.url());
                        result.put("probe", probe.evaluate(probeJs));
                    } catch (Exception e) {
                        result.put("elapsedMs", System.currentTimeMillis() - start);
                        result.put("error", e.getMessage() == null ? e.toString()
                                : e.getMessage().split("\n")[0]);
                    } finally {
                        if (probe != null) {
                            try {
                                probe.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    out.put(target, result);
                }
                return null;
            });
            out.put("success", true);
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 测试Boss导航功能
     */
    @GetMapping("/test-navigate")
    public ResponseEntity<Map<String, String>> testNavigate() {
        if (!playwrightManager.isPlatformEnabled("boss") || playwrightManager.getBossPage() == null) {
            return ResponseEntity.status(409).body(Map.of("success", "false",
                    "message", "当前模式未启用BOSS页面"));
        }
        try {
            playwrightManager.getBossPage().navigate("https://www.zhipin.com");
            String title = playwrightManager.getBossPage().title();

            Map<String, String> result = new HashMap<>();
            result.put("success", "true");
            result.put("title", title);
            result.put("url", playwrightManager.getBossPage().url());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("success", "false");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
