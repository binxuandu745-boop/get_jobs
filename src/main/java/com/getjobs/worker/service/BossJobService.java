package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossTaskPausedException;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Boss直聘任务服务
 * 管理Boss平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";
    private static final Map<String, String> HOT_CITY_NAMES = Map.of(
            "101010100", "北京", "101020100", "上海",
            "101280600", "深圳", "101280100", "广州",
            "101210100", "杭州", "101270100", "成都",
            "101190100", "南京", "101200100", "武汉",
            "101190400", "苏州", "101110100", "西安");

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;
    private final BossPauseNotifier bossPauseNotifier;
    @Value("${getjobs.boss.preview-only:false}")
    private boolean previewOnly;
    @Value("${getjobs.boss.random-city-enabled:false}")
    private boolean randomCityEnabled;
    @Value("${getjobs.boss.random-city-pool:}")
    private List<String> randomCityPool = List.of();
    private final Object taskLock = new Object();
    private String lastRandomCityCode = "";
    private volatile String taskCityCode = "";
    private volatile String taskCityName = "";

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;
    // 本次任务的开始时间（毫秒），用于判断任务是不是卡死了
    private volatile long runningSince = 0L;
    private volatile String lastMessage = "尚未运行";
    private volatile boolean paused = false;
    private volatile String pauseReason = "";

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        synchronized (taskLock) {
            if (isRunning) {
                progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
                return;
            }
            isRunning = true;
            shouldStop = false;
            paused = false;
            pauseReason = "";
            taskCityCode = "";
            taskCityName = "";
            lastMessage = "任务初始化中";
            runningSince = System.currentTimeMillis();
        }

        try {
            // 浏览器可能已经被关掉（手动关窗口 / Chrome 崩溃），这时各字段仍非空，
            // 但每个操作都会抛 TargetClosedError。先确保浏览器可用，必要时重新拉起。
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            playwrightManager.ensureReady();

            // 获取Boss页面实例
            Page page = playwrightManager.getBossPage();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "Boss页面未初始化"));
                return;
            }

            // 检查是否已登录
            if (!playwrightManager.refreshBossLoginStatus()) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, playwrightManager.getBossLoginMessage()));
                return;
            }

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pauseBossMonitoring();

            // 加载配置（统一从 boss_config 专表读取）
            BossConfig config = configService.getBossConfig();
            if (previewOnly) config.setDebugger(true);
            applyRandomCity(config);
            String jobTypeName = switch (config.getJobType() == null ? "0" : config.getJobType()) {
                case "1901" -> "全职";
                case "1902" -> "实习";
                case "1903" -> "兼职";
                case "0", "" -> "不限";
                default -> config.getJobType();
            };
            progressCallback.accept(JobProgressMessage.info(PLATFORM,
                    "本轮工作类型：" + jobTypeName + "；本任务所有城市和关键词沿用此设置"));
            if (randomCityEnabled) {
                lastMessage = "本次随机城市：" + taskCityName + "（" + taskCityCode + "），本任务所有关键词使用同一城市";
                log.info(lastMessage);
                progressCallback.accept(JobProgressMessage.info(PLATFORM, lastMessage));
            }
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            progressCallback.accept(JobProgressMessage.info(PLATFORM,
                    Boolean.TRUE.equals(config.getDebugger()) ? "开始岗位预览（不发送、不投递）..." : "开始投递任务..."));

            // 创建Boss实例并执行投递
            Boss.ProgressCallback bossCallback = (message, current, total) -> {
                lastMessage = message;
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            Boss boss = bossProvider.getObject();
            boss.setPage(page);
            boss.setConfig(config);
            boss.setProgressCallback(bossCallback);
            boss.setShouldStopCallback(this::shouldStop);
            boss.prepare();

            // 整个投递流程必须跑在 Playwright 专用线程上。
            // 原来跑在 ForkJoinPool 上，会和 scheduling-1 的登录监控同时操作同一条
            // Playwright 连接，导航被打断报 net::ERR_ABORTED，整个任务直接挂掉。
            int deliveredCount = playwrightManager.callOnPlaywright(boss::execute);

            lastMessage = shouldStop ? "任务已停止" : Boolean.TRUE.equals(config.getDebugger())
                            ? String.format("岗位预览完成：查看%d条，符合筛选%d条；未发送任何消息",
                                    boss.getPreviewInspectedCount(), boss.getPreviewMatchedCount())
                            : String.format("投递任务完成，共发起%d个聊天", deliveredCount);
            progressCallback.accept(JobProgressMessage.success(PLATFORM, lastMessage));
        } catch (BossTaskPausedException e) {
            // This is terminal for the current run, not a successful completion or a retry.
            paused = true;
            pauseReason = e.getReason().name();
            lastMessage = e.getMessage();
            log.warn("Boss任务保护性暂停 [{}]：{}", pauseReason, lastMessage);
            // Notify independently of SSE: a hidden/disconnected management page must not hide a pause.
            try {
                bossPauseNotifier.notifyPaused(e.getReason(), lastMessage);
            } catch (RuntimeException notificationFailure) {
                log.warn("暂停提醒显示失败，任务仍保持暂停", notificationFailure);
            }
            progressCallback.accept(JobProgressMessage.error(PLATFORM, lastMessage));
        } catch (Exception e) {
            log.error("Boss投递任务执行失败", e);
            lastMessage = "任务未完成: " + e.getMessage();
            progressCallback.accept(JobProgressMessage.error(PLATFORM, lastMessage));
        } finally {
            // 恢复后台登录监控
            try {
                playwrightManager.resumeBossMonitoring();
            } catch (Exception ignored) {}
            synchronized (taskLock) {
                isRunning = false;
                shouldStop = false;
                runningSince = 0L;
            }
        }
    }

    @Override
    public void stopDelivery() {
        if (!isRunning) {
            return;
        }
        log.info("收到停止Boss投递任务的请求");
        shouldStop = true;

        // 保持运行状态直到旧任务实际退出，防止新任务清除旧任务的停止标志。
    }

    /** 任务已经跑了多久（毫秒）；没在跑返回 0 */
    public long runningForMillis() {
        long since = runningSince;
        return since == 0L ? 0L : System.currentTimeMillis() - since;
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("loginState", playwrightManager.getBossLoginState());
        status.put("loginMessage", playwrightManager.getBossLoginMessage());
        status.put("isStopping", shouldStop);
        status.put("previewOnly", previewOnly);
        status.put("lastMessage", lastMessage);
        status.put("paused", paused);
        status.put("pauseReason", pauseReason);
        status.put("randomCityEnabled", randomCityEnabled);
        status.put("cityCode", taskCityCode);
        status.put("cityName", taskCityName);
        return status;
    }

    /** 每次任务只选择一次城市，不回写用户的固定城市配置。 */
    void applyRandomCity(BossConfig config) {
        if (!randomCityEnabled) return;
        if (config == null) throw new IllegalArgumentException("Boss任务配置为空");
        List<String> pool = randomCityPool == null ? List.of() : randomCityPool.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim).distinct().toList();
        if (pool.isEmpty()) throw new IllegalArgumentException("随机城市池为空，请配置热门城市代码");
        for (String code : pool) {
            if (!HOT_CITY_NAMES.containsKey(code)) {
                throw new IllegalArgumentException("随机城市池含不支持的城市代码：" + code);
            }
        }
        List<String> candidates = pool.stream()
                .filter(code -> !code.equals(lastRandomCityCode)).toList();
        if (candidates.isEmpty()) candidates = pool;
        String selected = candidates.get(randomCityIndex(candidates.size()));
        config.setCityCode(List.of(selected));
        taskCityCode = selected;
        taskCityName = HOT_CITY_NAMES.get(selected);
        lastRandomCityCode = selected;
    }

    int randomCityIndex(int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 检查是否应该停止
     * 供Boss.java调用
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    
}
