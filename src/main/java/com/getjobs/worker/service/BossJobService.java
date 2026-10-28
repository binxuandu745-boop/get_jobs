package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossManualSearchFilters;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
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
    @Value("${getjobs.boss.auto-rounds-enabled:true}")
    private boolean autoRoundsEnabled;
    @Value("${getjobs.boss.round-size:50}")
    private int roundSize = 50;
    @Value("${getjobs.boss.total-target:150}")
    private int totalTarget = 150;
    @Value("${getjobs.boss.max-rounds:30}")
    private int maxRounds = 30;
    @Value("${getjobs.boss.max-empty-rounds:3}")
    private int maxEmptyRounds = 3;
    @Value("${getjobs.boss.manual-filter-setup-enabled:true}")
    private boolean manualSetupEnabled;
    @Value("${getjobs.boss.manual-filter-setup-wait-ms:10000}")
    private int manualSetupWaitMs = 10000;
    private final Object taskLock = new Object();
    private String lastRandomCityCode = "";
    private volatile String taskCityCode = "";
    private volatile String taskCityName = "";
    private volatile int roundNumber;
    private volatile int roundDelivered;
    private volatile int deliveredCount;
    private volatile int roundLimit;

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
            roundNumber = 0;
            roundDelivered = 0;
            deliveredCount = 0;
            roundLimit = 0;
        }

        try {
            if (autoRoundsEnabled && (roundSize < 1 || roundSize > 50 || totalTarget < 1
                    || maxRounds < 1 || maxEmptyRounds < 1)) {
                throw new IllegalArgumentException("自动轮次配置无效：每轮须为1至50个，总目标、最大轮数与无产出轮数须大于0");
            }
            Set<String> deliveredJobIds = new HashSet<>();
            BossManualSearchFilters manualFilters = null;
            int emptyRounds = 0;
            while (true) {
                requireTaskNotStopped();
                roundNumber++;
                roundDelivered = 0;
                final int deliveredBeforeRound = deliveredCount;
                roundLimit = autoRoundsEnabled ? Math.min(roundSize, totalTarget - deliveredCount) : 0;
                // 浏览器可能已经被关掉（手动关窗口 / Chrome 崩溃），这时各字段仍非空，
                // 但每个操作都会抛 TargetClosedError。先确保浏览器可用，必要时重新拉起。
                progressCallback.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
                playwrightManager.ensureReady();
                requireTaskNotStopped();

                // 获取Boss页面实例
                Page page = playwrightManager.getBossPage();
                if (page == null) {
                    lastMessage = "Boss页面未初始化";
                    progressCallback.accept(JobProgressMessage.error(PLATFORM, lastMessage));
                    return;
                }

                // 检查是否已登录
                if (!playwrightManager.refreshBossLoginStatus()) {
                    lastMessage = playwrightManager.getBossLoginMessage();
                    progressCallback.accept(JobProgressMessage.error(PLATFORM, lastMessage));
                    return;
                }
                requireTaskNotStopped();
                // The login probe can bind a different authenticated tab in the same browser.
                // Use that tab for both the manual choices and the subsequent delivery task.
                page = playwrightManager.getBossPage();
                if (page == null) {
                    lastMessage = "登录后的Boss页面未初始化";
                    progressCallback.accept(JobProgressMessage.error(PLATFORM, lastMessage));
                    return;
                }

                // 暂停后台登录监控，避免与投递流程并发访问同一Page
                playwrightManager.pauseBossMonitoring();

                // 加载配置（统一从 boss_config 专表读取）
                BossConfig config = configService.getBossConfig();
                if (previewOnly) config.setDebugger(true);
                final boolean roundAutomation = autoRoundsEnabled && !Boolean.TRUE.equals(config.getDebugger());
                if (!roundAutomation) roundLimit = 0;
                if (manualFilters == null || !manualFilters.cityChanged()) {
                    applyRandomCity(config);
                }
                if (manualFilters != null) {
                    manualFilters.applyTo(config);
                    if (manualFilters.cityChanged()) applyManualCityStatus(manualFilters);
                }
                String jobTypeName = jobTypeName(config.getJobType());
                progressCallback.accept(JobProgressMessage.info(PLATFORM,
                        (manualSetupEnabled && manualFilters == null ? "初始工作类型：" : "本轮工作类型：")
                                + jobTypeName + (manualSetupEnabled && manualFilters == null
                                ? "；稍后可在BOSS页面手动调整" : "；本轮所有关键词沿用此设置")));
                if (randomCityEnabled && (manualFilters == null || !manualFilters.cityChanged())) {
                    lastMessage = "本次随机城市：" + taskCityName + "（" + taskCityCode + "），本任务所有关键词使用同一城市";
                    log.info(lastMessage);
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, lastMessage));
                }
                progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

                progressCallback.accept(JobProgressMessage.info(PLATFORM,
                        manualSetupEnabled && manualFilters == null ? "正在准备手动筛选，设置完成后开始处理岗位..."
                                : Boolean.TRUE.equals(config.getDebugger())
                                ? "开始岗位预览（不发送、不投递）..." : "开始投递任务..."));

                // 创建Boss实例并执行投递
                Boss.ProgressCallback bossCallback = (message, current, total) -> {
                    lastMessage = roundAutomation ? roundProgress(message) : message;
                    if (current != null && total != null) {
                        progressCallback.accept(JobProgressMessage.progress(PLATFORM, lastMessage,
                                roundAutomation ? deliveredCount : current, roundAutomation ? totalTarget : total));
                    } else {
                        progressCallback.accept(JobProgressMessage.info(PLATFORM, lastMessage));
                    }
                };

                Boss boss = bossProvider.getObject();
                boss.setPage(page);
                boss.setConfig(config);
                boss.setProgressCallback(bossCallback);
                boss.setShouldStopCallback(this::shouldStop);
                boss.setDeliveredJobIds(deliveredJobIds);
                if (roundAutomation) {
                    boss.setSuccessfulDeliveryLimit(roundLimit);
                    boss.setDeliveryCountCallback(count -> {
                        roundDelivered = count;
                        deliveredCount = deliveredBeforeRound + count;
                        lastMessage = roundProgress("本次招呼发送操作已完成");
                        progressCallback.accept(JobProgressMessage.progress(PLATFORM, lastMessage,
                                deliveredCount, totalTarget));
                    });
                    lastMessage = roundProgress(manualSetupEnabled && manualFilters == null
                            ? "等待手动设置筛选后开始，过滤岗位不计入投递数量"
                            : "本轮开始，过滤岗位不计入投递数量");
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, lastMessage,
                            deliveredCount, totalTarget));
                }
                requireTaskNotStopped();
                boss.prepare();

                if (manualSetupEnabled && manualFilters == null) {
                    lastMessage = "正在打开BOSS岗位列表，页面加载后可手动设置筛选";
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, lastMessage));
                    final int waitMs = manualSetupWaitMs >= 1000 && manualSetupWaitMs <= 60000
                            ? manualSetupWaitMs : 10000;
                    manualFilters = playwrightManager.callOnPlaywright(() -> boss.awaitManualSearchSetup(waitMs));
                    if (manualFilters == null) {
                        throw BossTaskPausedException.detailsUnavailable("未能确认手动设置的搜索条件");
                    }
                    requireTaskNotStopped();
                    manualFilters.applyTo(config);
                    if (manualFilters.cityChanged()) applyManualCityStatus(manualFilters);
                    boss.setManualSearchGuard(manualFilters);
                    String position = config.getPosition() == null || config.getPosition().isBlank()
                            ? "不限" : config.getPosition();
                    lastMessage = "已采用网页筛选：职位类型=" + position + "，求职类型="
                            + jobTypeName(config.getJobType()) + "；开始处理岗位";
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, lastMessage));
                }

                // 整个投递流程必须跑在 Playwright 专用线程上。
                // 原来跑在 ForkJoinPool 上，会和 scheduling-1 的登录监控同时操作同一条
                // Playwright 连接，导航被打断报 net::ERR_ABORTED，整个任务直接挂掉。
                int completed = playwrightManager.callOnPlaywright(boss::execute);
                if (completed < 0 || (roundAutomation && completed > roundLimit)) {
                    throw new IllegalStateException("本轮投递计数超出配置范围，任务已结束");
                }
                roundDelivered = Math.max(roundDelivered, completed);
                deliveredCount = deliveredBeforeRound + roundDelivered;
                requireTaskNotStopped();

                if (!roundAutomation) {
                    lastMessage = Boolean.TRUE.equals(config.getDebugger())
                                ? String.format("岗位预览完成：查看%d条，符合筛选%d条；未发送任何消息",
                                        boss.getPreviewInspectedCount(), boss.getPreviewMatchedCount())
                                : String.format("投递任务完成，共发起%d个聊天", completed);
                    progressCallback.accept(JobProgressMessage.success(PLATFORM, lastMessage));
                    return;
                }
                if (boss.hasProcessingFailures()) {
                    lastMessage = roundProgress("本轮存在岗位处理或沟通失败，已结束自动轮次；请检查页面和日志后手动开始");
                    progressCallback.accept(JobProgressMessage.stopped(PLATFORM, lastMessage));
                    return;
                }
                if (deliveredCount >= totalTarget) {
                    lastMessage = String.format("投递任务完成：共%d轮，累计投递%d/%d个", roundNumber, deliveredCount, totalTarget);
                    progressCallback.accept(JobProgressMessage.success(PLATFORM, lastMessage));
                    return;
                }
                emptyRounds = roundDelivered == 0 ? emptyRounds + 1 : 0;
                if (emptyRounds >= maxEmptyRounds || roundNumber >= maxRounds) {
                    String reason = emptyRounds >= maxEmptyRounds
                            ? "连续" + emptyRounds + "轮没有新增投递" : "已达到最大轮数" + maxRounds;
                    lastMessage = roundProgress(reason + "，任务已结束；尚未达到总目标，请检查岗位条件后手动开始");
                    progressCallback.accept(JobProgressMessage.stopped(PLATFORM, lastMessage));
                    return;
                }
                lastMessage = roundProgress(roundDelivered >= roundLimit
                        ? "本轮已达到投递上限，正在重新初始化下一轮"
                        : "本轮岗位已扫描完，正在重新初始化下一轮");
                progressCallback.accept(JobProgressMessage.info(PLATFORM, lastMessage));
                // Keep the session lock and cancellation flag across rounds; never recursively start a new task.
            }
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
        } catch (CancellationException e) {
            lastMessage = "任务已停止：累计投递" + deliveredCount + "/" + totalTarget + "个";
            progressCallback.accept(JobProgressMessage.stopped(PLATFORM, lastMessage));
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
        status.put("autoRoundsEnabled", autoRoundsEnabled);
        status.put("roundNumber", roundNumber);
        status.put("roundDelivered", roundDelivered);
        status.put("deliveredCount", deliveredCount);
        status.put("totalTarget", totalTarget);
        status.put("roundLimit", roundLimit);
        return status;
    }

    private void requireTaskNotStopped() {
        if (shouldStop || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("用户取消任务");
        }
    }

    private String roundProgress(String message) {
        return "第" + roundNumber + "轮｜本轮" + roundDelivered + "/" + roundLimit
                + "｜累计" + deliveredCount + "/" + totalTarget + "：" + message;
    }

    private static String jobTypeName(String value) {
        return switch (value == null ? "0" : value) {
            case "1901" -> "全职";
            case "1902" -> "实习";
            case "1903" -> "兼职";
            case "0", "" -> "不限";
            default -> value;
        };
    }

    private void applyManualCityStatus(BossManualSearchFilters filters) {
        String code = filters.selectedCity();
        taskCityCode = code.isBlank() ? "0" : code;
        taskCityName = code.isBlank() ? "全国" : HOT_CITY_NAMES.getOrDefault(code, "手动选择");
        if (HOT_CITY_NAMES.containsKey(code)) lastRandomCityCode = code;
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
