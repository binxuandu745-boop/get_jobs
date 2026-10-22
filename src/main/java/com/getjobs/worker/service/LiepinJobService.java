package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.liepin.LiepinTaskPausedException;
import com.getjobs.worker.manager.BrowserModeSwitchBusyException;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * 猎聘任务服务
 * 负责猎聘平台的自动投递任务管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiepinJobService implements JobPlatformService {

    private static final String PLATFORM = "liepin";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Liepin> liepinProvider;

    // 运行状态标志
    private volatile boolean isRunning = false;
    private volatile boolean starting;
    private final Object taskStateLock = new Object();
    private StartSnapshot pendingStart;

    // 停止请求标志
    private volatile boolean shouldStop = false;
    private volatile boolean paused;
    private volatile String pauseReason = "";
    private volatile String lastMessage = "尚未运行";

    /** A request-scoped admission token also retains the state to restore if scheduling fails. */
    public static final class StartSnapshot {
        private final boolean paused;
        private final String pauseReason;
        private final String lastMessage;

        private StartSnapshot(boolean paused, String pauseReason, String lastMessage) {
            this.paused = paused;
            this.pauseReason = pauseReason;
            this.lastMessage = lastMessage;
        }
    }

    /** Publish starting synchronously, before the controller queues its asynchronous task. */
    public StartSnapshot prepareStart() {
        synchronized (taskStateLock) {
            if (starting || isRunning) throw new BrowserModeSwitchBusyException();
            StartSnapshot snapshot = new StartSnapshot(paused, pauseReason, lastMessage);
            pendingStart = snapshot;
            starting = true;
            shouldStop = false;
            paused = false;
            pauseReason = "";
            lastMessage = "任务启动中";
            return snapshot;
        }
    }

    /** Only the still-pending request may roll back its own failed scheduling attempt. */
    public void cancelPreparedStart(StartSnapshot snapshot) {
        synchronized (taskStateLock) {
            if (snapshot == null || pendingStart != snapshot || !starting) return;
            pendingStart = null;
            starting = false;
            shouldStop = false;
            paused = snapshot.paused;
            pauseReason = snapshot.pauseReason;
            lastMessage = snapshot.lastMessage;
        }
    }

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        StartSnapshot snapshot;
        try {
            snapshot = prepareStart();
        } catch (BrowserModeSwitchBusyException busy) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }
        executePreparedDelivery(snapshot, progressCallback);
    }

    public void executePreparedDelivery(StartSnapshot snapshot, Consumer<JobProgressMessage> progressCallback) {
        synchronized (taskStateLock) {
            if (snapshot == null || pendingStart != snapshot || !starting || isRunning) return;
            pendingStart = null;
            starting = false;
            isRunning = true;
            lastMessage = shouldStop ? "正在停止任务" : "任务初始化中";
        }

        boolean monitoringPaused = false;
        try {
            if (shouldStop) throw new CancellationException("用户取消猎聘任务启动");
            // 浏览器可能已被手动关闭 / 崩溃，先确保可用，必要时重新拉起
            updateLastMessage("检查浏览器状态...");
            progressCallback.accept(JobProgressMessage.info(PLATFORM, lastMessage));
            playwrightManager.ensureReady();
            if (shouldStop) throw new CancellationException("用户停止猎聘任务");

            Page page = playwrightManager.getLiepinPage();
            if (page == null) {
                updateLastMessage("猎聘页面未初始化");
                progressCallback.accept(JobProgressMessage.error(PLATFORM, lastMessage));
                return;
            }

            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                updateLastMessage("请先登录猎聘");
                progressCallback.accept(JobProgressMessage.error(PLATFORM, lastMessage));
                return;
            }

            // 暂停后台登录监控，避免并发访问冲突
            playwrightManager.pauseLiepinMonitoring();
            monitoringPaused = true;

            // 加载配置（统一通过 ConfigService 从专表读取）
            LiepinConfig config = configService.getLiepinConfig();
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建并执行 Bean
            Liepin.ProgressCallback cb = (message, current, total) -> {
                updateLastMessage(message);
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            Liepin liepin = liepinProvider.getObject();
            liepin.setPage(page);
            liepin.setConfig(config);
            liepin.setProgressCallback(cb);
            liepin.setShouldStopCallback(this::shouldStop);
            if (shouldStop) throw new CancellationException("用户停止猎聘任务");

            // 必须跑在 Playwright 专用线程上，否则会和后台登录监控并发操作同一条连接
            int deliveredCount = playwrightManager.callOnPlaywright(liepin::execute);

            updateLastMessage(shouldStop ? "任务已停止"
                    : String.format("任务处理完成，共确认建立%d次沟通（不代表招呼或简历送达）", deliveredCount));
            progressCallback.accept(shouldStop ? JobProgressMessage.warning(PLATFORM, lastMessage)
                    : JobProgressMessage.success(PLATFORM, lastMessage));
        } catch (LiepinTaskPausedException pause) {
            synchronized (taskStateLock) {
                paused = true;
                pauseReason = pause.getReason().name();
                lastMessage = pause.getMessage();
            }
            log.warn("猎聘任务保护性暂停 [{}]：{}", pauseReason, lastMessage);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, lastMessage));
        } catch (CancellationException cancelled) {
            updateLastMessage("任务已停止");
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, lastMessage));
        } catch (Exception e) {
            log.error("猎聘投递任务执行失败", e);
            updateLastMessage("投递失败: " + e.getMessage());
            progressCallback.accept(JobProgressMessage.error(PLATFORM, lastMessage));
        } finally {
            try {
                if (monitoringPaused) playwrightManager.resumeLiepinMonitoring();
            } catch (Exception ignored) {}
            synchronized (taskStateLock) {
                isRunning = false;
                starting = false;
                pendingStart = null;
                shouldStop = false;
            }
        }
    }

    @Override
    public void stopDelivery() {
        synchronized (taskStateLock) {
            if (!isRunning && !starting) {
                log.warn("猎聘任务未在运行，无需停止");
                return;
            }
            log.info("收到停止猎聘任务请求");
            shouldStop = true;
            lastMessage = "正在停止任务";
        }
    }

    /**
     * 获取任务状态
     */
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        synchronized (taskStateLock) {
            status.put("isRunning", isRunning);
            status.put("starting", starting);
            status.put("paused", paused);
            status.put("pauseReason", pauseReason);
            status.put("lastMessage", lastMessage);
        }
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        synchronized (taskStateLock) {
            return isRunning || starting;
        }
    }

    public boolean shouldStop() {
        return shouldStop;
    }

    private void updateLastMessage(String message) {
        synchronized (taskStateLock) {
            lastMessage = message;
        }
    }
}
