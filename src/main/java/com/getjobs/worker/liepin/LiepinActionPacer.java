package com.getjobs.worker.liepin;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;

/** Configurable action pacing that keeps Playwright events and stop checks running. */
@Component
public class LiepinActionPacer {
    private static final Logger log = LoggerFactory.getLogger(LiepinActionPacer.class);
    private static final int DEFAULT_MIN_MILLIS = 3000;
    private static final int DEFAULT_MAX_MILLIS = 5000;
    private static final int MAX_SLICE_MILLIS = 250;

    @Value("${getjobs.liepin.action-delay-min-ms:3000}")
    private int actionDelayMinMs = DEFAULT_MIN_MILLIS;
    @Value("${getjobs.liepin.action-delay-max-ms:5000}")
    private int actionDelayMaxMs = DEFAULT_MAX_MILLIS;

    public LiepinActionPacer() { }

    LiepinActionPacer(int minMillis, int maxMillis) {
        this.actionDelayMinMs = minMillis;
        this.actionDelayMaxMs = maxMillis;
    }

    public void pauseBeforeAction(Page page, String step, Runnable checkStopped) {
        requireActionAllowed(page, checkStopped);
        int min = actionDelayMinMs;
        int max = actionDelayMaxMs;
        if (min <= 0 || max < min || max > 60_000) {
            log.warn("猎聘步骤等待配置无效，使用默认 3000-5000 毫秒");
            min = DEFAULT_MIN_MILLIS;
            max = DEFAULT_MAX_MILLIS;
        }

        int milliseconds = ThreadLocalRandom.current().nextInt(min, max + 1);
        log.info("猎聘步骤等待 {} 毫秒 → {}", milliseconds, step);
        for (int remaining = milliseconds; remaining > 0;) {
            requireActionAllowed(page, checkStopped);
            int slice = Math.min(MAX_SLICE_MILLIS, remaining);
            try {
                page.waitForTimeout(slice);
            } catch (LiepinTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (RuntimeException failure) {
                requireActionAllowed(page, checkStopped);
                if (!isStaleObjectError(failure)) throw LiepinTaskPausedException.readFailed(failure);
                log.debug("猎聘步骤等待遇到页面切换，页面复查正常，继续等待（异常类型：{}）",
                        LiepinTaskPausedException.exceptionType(failure));
            }
            requireActionAllowed(page, checkStopped);
            remaining -= slice;
        }
    }

    private static void requireActionAllowed(Page page, Runnable checkStopped) {
        LiepinPageSafety.requireUsablePage(page, checkStopped);
    }

    private static boolean isStaleObjectError(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && (message.contains("Object doesn't exist")
                || message.contains("Execution context was destroyed")
                || message.contains("Execution context destroyed"));
    }
}
