package com.getjobs.worker.boss;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.Timing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Wait for the selected job once, while keeping cancellation and page checks active. */
public final class BossJobDetailAwaiter {
    private static final Logger log = LoggerFactory.getLogger(BossJobDetailAwaiter.class);
    private static final String DETAIL_PATH = "/wapi/zpgeek/job/detail.json";

    private BossJobDetailAwaiter() { }

    public static BossJobDetailReader.Detail await(Page page, Runnable selectJobOnce,
                                                  Runnable checkStopped, int timeoutMs) {
        return await(page, selectJobOnce, checkStopped, timeoutMs, false);
    }

    public static BossJobDetailReader.Detail await(Page page, Runnable selectJobOnce,
                                                  Runnable checkStopped, int timeoutMs, boolean metadataOnly) {
        checkStopped.run();
        BossPageSafety.requireUsablePage(page);

        AtomicBoolean selectionStarted = new AtomicBoolean();
        AtomicLong selectionStartedAt = new AtomicLong();
        Set<Request> selectedRequests = Collections.newSetFromMap(new IdentityHashMap<>());
        AtomicReference<Response> received = new AtomicReference<>();
        Consumer<Request> requestListener = request -> {
            if (!selectionStarted.get() || received.get() != null) return;
            try {
                String url = request.url();
                if (url != null && url.contains(DETAIL_PATH)
                        && "GET".equalsIgnoreCase(request.method())) {
                    selectedRequests.add(request);
                }
            } catch (BossTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (RuntimeException ignored) {
                // Metadata only: an unreadable request must not turn into a retry.
            }
        };
        Consumer<Response> responseListener = response -> {
            if (received.get() != null) return;
            try {
                Request request = response.request();
                if (!selectedRequests.contains(request)) return;
                // SDK timing is cached metadata in epoch milliseconds, available once a response exists.
                Timing timing = request.timing();
                if (timing != null && Double.isFinite(timing.startTime) && timing.startTime > 0
                        && timing.startTime < selectionStartedAt.get()) return;
                received.compareAndSet(null, response);
            } catch (BossTaskPausedException | CancellationException stopped) {
                throw stopped;
            } catch (RuntimeException ignored) {
                // Matching only uses response metadata; do not read a body or operate the page here.
            }
        };

        try {
            page.onRequest(requestListener);
            page.onResponse(responseListener);
            selectionStartedAt.set(System.currentTimeMillis());
            selectionStarted.set(true);
            selectJobOnce.run();
            page.waitForCondition(() -> {
                checkStopped.run();
                BossPageSafety.requireUsablePage(page);
                return received.get() != null;
            }, new Page.WaitForConditionOptions().setTimeout(timeoutMs));

            checkStopped.run();
            BossPageSafety.requireUsablePage(page);
            return BossJobDetailReader.read(received.get(), metadataOnly);
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (TimeoutError timeout) {
            checkStopped.run();
            BossPageSafety.requireUsablePage(page);
            throw BossTaskPausedException.detailsUnavailable(
                    "岗位详情读取超时（" + timeoutMs + " ms）");
        } catch (RuntimeException failure) {
            checkStopped.run();
            BossPageSafety.requireUsablePage(page);
            throw BossTaskPausedException.detailsUnavailable("岗位详情读取失败");
        } finally {
            removeListener(() -> page.offResponse(responseListener), "response");
            removeListener(() -> page.offRequest(requestListener), "request");
        }
    }

    private static void removeListener(Runnable cleanup, String eventType) {
        try {
            cleanup.run();
        } catch (RuntimeException failure) {
            // Never replace a protective stop/cancellation with a cleanup failure or log raw data.
            log.warn("Boss 详情临时监听器移除失败（{}，异常类型：{}）",
                    eventType, failure.getClass().getSimpleName());
        }
    }
}
