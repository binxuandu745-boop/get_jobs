package com.getjobs.worker.boss;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.Timing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Search-scoped observation of naturally issued detail responses. This class never navigates,
 * refreshes, issues HTTP requests, or selects an alternative job to force a response.
 * Use on the owning Playwright thread and call beginNavigation before each list navigation.
 */
public final class BossJobDetailSession implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BossJobDetailSession.class);
    private static final String DETAIL_PATH = "/wapi/zpgeek/job/detail.json";
    static final int MAX_PENDING_RESPONSES = 64;
    static final int MAX_CACHED_DETAILS = 32;
    private static final int MAX_OBSERVED_REQUESTS = 128;

    private final Page page;
    private final boolean metadataOnly;
    private final Consumer<Request> requestListener = this::observeRequest;
    private final Consumer<Response> responseListener = this::observeResponse;
    private final Consumer<Frame> navigationListener = this::observeNavigation;
    private final Map<Request, Long> requests = new IdentityHashMap<>();
    private final ArrayDeque<Request> requestOrder = new ArrayDeque<>();
    private final ArrayDeque<ObservedResponse> pending = new ArrayDeque<>();
    private final LinkedHashMap<String, BossJobDetailReader.Detail> details = new LinkedHashMap<>();
    private long generation;
    private long navigationStartedAt;
    private boolean active;
    private boolean closed;
    private BossSearchContext searchContext;
    private boolean searchContextChanged;

    private record ObservedResponse(long generation, Response response) { }

    public BossJobDetailSession(Page page) {
        this(page, false);
    }

    public BossJobDetailSession(Page page, boolean metadataOnly) {
        this.page = Objects.requireNonNull(page, "page");
        this.metadataOnly = metadataOnly;
        try {
            page.onRequest(requestListener);
            page.onResponse(responseListener);
            page.onFrameNavigated(navigationListener);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    /** Invalidate old search/attempt responses before the next list navigation starts. */
    public void beginNavigation() {
        requireOpen();
        searchContext = null;
        searchContextChanged = false;
        generation++;
        navigationStartedAt = System.currentTimeMillis();
        requests.clear();
        requestOrder.clear();
        pending.clear();
        details.clear();
        active = true;
    }

    /** Arm only after the caller has reached the expected search and confirmed list readiness. */
    public void bindSearchContext(String expectedUrl) {
        requireOpen();
        if (searchContextChanged) throw changedSearch();
        BossSearchContext expected = BossSearchContext.fromUrl(expectedUrl);
        if (searchContext != null && !searchContext.matches(expectedUrl)) {
            invalidateSearch();
            throw changedSearch();
        }
        searchContext = expected;
        requireSearchContextUnchanged();
    }

    /** A detected change is terminal until an explicit beginNavigation, even after A -> B -> A. */
    public void requireSearchContextUnchanged() {
        requireOpen();
        if (searchContextChanged) throw changedSearch();
        if (searchContext == null) return;
        final String currentUrl;
        try { currentUrl = page.url(); }
        catch (BossTaskPausedException | CancellationException stopped) { throw stopped; }
        catch (RuntimeException unavailable) {
            throw BossTaskPausedException.detailsUnavailable("搜索页面地址暂不可读，无法确认本轮筛选条件");
        }
        if (!searchContext.matches(currentUrl)) {
            invalidateSearch();
            throw changedSearch();
        }
    }

    private void observeNavigation(Frame frame) {
        if (closed || searchContext == null || searchContextChanged) return;
        try {
            // Frame URL and mainFrame are cached metadata; do not operate the page in callbacks.
            if (frame == page.mainFrame() && !searchContext.matches(frame.url())) invalidateSearch();
        } catch (RuntimeException unreadableMetadata) {
            // A read failure is not evidence of a filter change; the main flow checks again.
        }
    }

    private void invalidateSearch() {
        if (searchContextChanged) return;
        searchContextChanged = true;
        generation++;
        active = false;
        requests.clear();
        requestOrder.clear();
        pending.clear();
        details.clear();
    }

    private BossTaskPausedException changedSearch() {
        return BossTaskPausedException.searchFiltersChanged();
    }

    /**
     * Select the desired card at most once, then use only a response bearing that card's ID.
     * For the already selected first card the caller supplies a no-op selection callback.
     * Selection always precedes a cache hit, so cached data cannot leave a different card selected.
     */
    public BossJobDetailReader.Detail await(String expectedJobId, Runnable selectJobOnce,
                                           Runnable checkStopped, int timeoutMs) {
        requireOpen();
        requireSearchContextUnchanged();
        Objects.requireNonNull(selectJobOnce, "selectJobOnce");
        Objects.requireNonNull(checkStopped, "checkStopped");
        if (!active || expectedJobId == null || expectedJobId.isBlank()) {
            throw BossTaskPausedException.detailsUnavailable("无法确认当前岗位的唯一标识或搜索上下文");
        }
        String expectedId = expectedJobId.trim();
        long expectedGeneration = generation;
        AtomicReference<BossJobDetailReader.Detail> result = new AtomicReference<>();
        try {
            checkAllowed(checkStopped);
            selectJobOnce.run();
            checkAllowed(checkStopped);
            result.set(findDetail(expectedId, expectedGeneration, checkStopped));
            if (result.get() == null) {
                page.waitForCondition(() -> {
                    checkAllowed(checkStopped);
                    result.set(findDetail(expectedId, expectedGeneration, checkStopped));
                    return result.get() != null;
                }, new Page.WaitForConditionOptions().setTimeout(timeoutMs));
            }
            checkAllowed(checkStopped);
            if (generation != expectedGeneration || result.get() == null) {
                throw BossTaskPausedException.detailsUnavailable("当前岗位详情未确认或搜索页面已变化");
            }
            return result.get();
        } catch (BossTaskPausedException | CancellationException stopped) {
            throw stopped;
        } catch (TimeoutError timeout) {
            checkAllowed(checkStopped);
            throw BossTaskPausedException.detailsUnavailable("岗位详情读取超时（" + timeoutMs + " ms）");
        } catch (RuntimeException failure) {
            checkAllowed(checkStopped);
            throw BossTaskPausedException.detailsUnavailable("岗位详情读取失败（"
                    + failure.getClass().getSimpleName() + "）");
        }
    }

    private BossJobDetailReader.Detail findDetail(String expectedId, long expectedGeneration,
                                                 Runnable checkStopped) {
        requireOpen();
        requireSearchContextUnchanged();
        if (generation != expectedGeneration) {
            throw BossTaskPausedException.detailsUnavailable("搜索页面已变化，不能复用上一页面的岗位详情");
        }
        BossJobDetailReader.Detail cached = details.get(expectedId);
        if (cached != null) return cached;
        while (!pending.isEmpty()) {
            checkAllowed(checkStopped);
            ObservedResponse observed = pending.removeFirst();
            if (observed.generation() != expectedGeneration) continue;
            // Body reads happen only in the main flow, never inside Playwright event callbacks.
            BossJobDetailReader.Detail detail = BossJobDetailReader.read(observed.response(), metadataOnly);
            // response.text() can dispatch queued navigation events; do not refill a cleared cache.
            requireSearchContextUnchanged();
            if (generation != expectedGeneration) {
                throw BossTaskPausedException.detailsUnavailable("搜索页面已变化，不能复用上一页面的岗位详情");
            }
            String id = detail.root().getJSONObject("zpData").getJSONObject("jobInfo")
                    .optString("encryptId", "").trim();
            if (id.isEmpty()) {
                throw BossTaskPausedException.detailsUnavailable("岗位详情缺少可核对的唯一标识");
            }
            details.put(id, detail);
            while (details.size() > MAX_CACHED_DETAILS) {
                details.remove(details.keySet().iterator().next());
            }
            if (id.equals(expectedId)) return detail;
        }
        return null;
    }

    private void observeRequest(Request request) {
        if (!active || closed) return;
        try {
            if (!isDetailRequest(request)) return;
            if (requests.put(request, generation) == null) requestOrder.addLast(request);
            while (requestOrder.size() > MAX_OBSERVED_REQUESTS) {
                requests.remove(requestOrder.removeFirst());
            }
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException ignored) {
            // An unreadable metadata event cannot trigger navigation, retry, or body reads.
        }
    }

    private void observeResponse(Response response) {
        if (!active || closed) return;
        try {
            Request request = response.request();
            Long requestGeneration = requests.remove(request);
            requestOrder.removeIf(candidate -> candidate == request);
            if (requestGeneration == null || requestGeneration != generation
                    || predatesNavigation(request)) return;
            while (pending.size() >= MAX_PENDING_RESPONSES) pending.removeFirst();
            pending.addLast(new ObservedResponse(requestGeneration, response));
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException ignored) {
            // Keep callbacks read-only and body-free; await reports a missing usable response.
        }
    }

    private boolean isDetailRequest(Request request) {
        if (!"GET".equalsIgnoreCase(request.method())) return false;
        URI uri = URI.create(request.url());
        String host = uri.getHost();
        return ("www.zhipin.com".equalsIgnoreCase(host) || "zhipin.com".equalsIgnoreCase(host))
                && DETAIL_PATH.equals(uri.getPath());
    }

    private boolean predatesNavigation(Request request) {
        Timing timing = request.timing();
        return timing != null && Double.isFinite(timing.startTime) && timing.startTime > 0
                && timing.startTime < navigationStartedAt;
    }

    private void checkAllowed(Runnable checkStopped) {
        requireOpen();
        checkStopped.run();
        BossPageSafety.requireUsablePage(page);
        requireSearchContextUnchanged();
    }

    private void requireOpen() {
        if (closed) throw BossTaskPausedException.detailsUnavailable("岗位详情观察会话已结束");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        active = false;
        pending.clear();
        details.clear();
        requests.clear();
        requestOrder.clear();
        removeListener(() -> page.offResponse(responseListener), "response");
        removeListener(() -> page.offRequest(requestListener), "request");
        removeListener(() -> page.offFrameNavigated(navigationListener), "navigation");
    }

    private void removeListener(Runnable cleanup, String eventType) {
        try {
            cleanup.run();
        } catch (RuntimeException failure) {
            log.warn("Boss 搜索详情监听器移除失败（{}，异常类型：{}）",
                    eventType, failure.getClass().getSimpleName());
        }
    }
}
