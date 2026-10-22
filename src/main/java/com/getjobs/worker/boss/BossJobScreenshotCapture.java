package com.getjobs.worker.boss;

import com.getjobs.application.screenshot.OfflineScreenshotService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotScale;
import com.microsoft.playwright.options.ScreenshotType;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Captures one already-open job detail before communication; never opens or fetches a page. */
public final class BossJobScreenshotCapture {
    static final double EPSILON = 2;
    static final double OVERLAP = 96;
    static final long MAX_IMAGE_PIXELS = 16_000_000L;
    static final int READINESS_TIMEOUT_MS = 30_000;
    static final int POLL_INTERVAL_MS = 500;
    static final int STABLE_INTERVAL_MS = 1000;
    static final int MAX_GROUP_RETRIES = 1;

    private BossJobScreenshotCapture() { }

    /** The callback must check cancellation, page safety and the caller's current job identity. */
    public static List<OfflineScreenshotService.Upload> capture(Page detailPage, String expectedJobId,
                                                                Runnable checkAllowed) {
        if (detailPage == null || expectedJobId == null || expectedJobId.isBlank() || checkAllowed == null) {
            throw new IllegalArgumentException("截图采集必须提供详情页、岗位标识和安全检查");
        }
        return capture(new PageSurface(detailPage, expectedJobId), checkAllowed);
    }

    static List<OfflineScreenshotService.Upload> capture(CaptureSurface surface, Runnable checkAllowed) {
        Throwable primaryFailure = null;
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    return captureAttempt(surface, checkAllowed);
                } catch (CaptureException retryable) {
                    if (!surface.retryTransientFailures() || !retryable.recoverable
                            || retryable.getSuppressed().length > 0) throw retryable;
                    if (attempt >= MAX_GROUP_RETRIES) {
                        throw new CaptureException(retryable.getMessage()
                                + "；已重新采集1次，仍未稳定", false, retryable);
                    }
                    // Discard the complete attempt before retrying. No model or partial result is used.
                    checkpoint(surface, checkAllowed);
                    surface.restore();
                    checkpoint(surface, checkAllowed);
                }
            }
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            // Cancellation also restores geometry. A failed check must not bypass this cleanup.
            RuntimeException cleanupFailure = null;
            try { checkAllowed.run(); } catch (RuntimeException stopped) { cleanupFailure = stopped; }
            try { surface.restore(); }
            catch (RuntimeException failedRestore) {
                if (cleanupFailure == null) cleanupFailure = new CaptureException("恢复滚动位置：未能恢复原页面状态", false, failedRestore);
            }
            try { checkAllowed.run(); }
            catch (RuntimeException stopped) { if (cleanupFailure == null) cleanupFailure = stopped; }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    if (primaryFailure != cleanupFailure) primaryFailure.addSuppressed(cleanupFailure);
                }
                else throw cleanupFailure;
            }
        }
    }

    private static List<OfflineScreenshotService.Upload> captureAttempt(CaptureSurface surface,
                                                                        Runnable checkAllowed) {
        List<OfflineScreenshotService.Upload> images = new ArrayList<>();
        long totalBytes = 0;
        checkpoint(surface, checkAllowed);
        surface.awaitReady(checkAllowed);
        checkpoint(surface, checkAllowed);
        int phases = surface.initialize();
        checkpoint(surface, checkAllowed);
        if (phases < 1 || phases > 2) throw failure("无法确认岗位截图区域");
        for (int phase = 0; phase < phases; phase++) {
            checkpoint(surface, checkAllowed);
            surface.prepare(phase);
            checkpoint(surface, checkAllowed);
            surface.awaitStable(phase, checkAllowed);
            Geometry geometry = measure(surface, phase, checkAllowed);
            double coveredUntil = geometry.contentTop();
            double originalTop = geometry.contentTop();
            double originalLeft = geometry.clip().x();
            double originalWidth = geometry.clip().width();
            boolean first = true;
            while (true) {
                geometry.requireValid();
                if (Math.abs(geometry.contentTop() - originalTop) > EPSILON
                        || Math.abs(geometry.clip().x() - originalLeft) > EPSILON
                        || Math.abs(geometry.clip().width() - originalWidth) > EPSILON) {
                    throw transientFailure("采集截图：岗位布局仍在变化，不能确认完整覆盖");
                }
                if (geometry.visibleStart() > coveredUntil + EPSILON) {
                    throw transientFailure("采集截图：岗位截图之间出现未覆盖内容");
                }
                if (!first && geometry.visibleEnd() <= coveredUntil + EPSILON) {
                    throw transientFailure("滚动截图：岗位滚动未前进，尚未完整覆盖职位描述");
                }
                if (images.size() >= OfflineScreenshotService.MAX_IMAGES) {
                    throw failure("岗位内容超过6张截图，尚未完整覆盖，已取消本岗位识别");
                }
                checkpoint(surface, checkAllowed);
                byte[] bytes = surface.screenshot(geometry.clip());
                checkpoint(surface, checkAllowed);
                validateImage(bytes);
                totalBytes += bytes.length;
                if (totalBytes > OfflineScreenshotService.MAX_TOTAL_BYTES) {
                    throw failure("岗位截图总大小超过24MiB");
                }
                images.add(new OfflineScreenshotService.Upload(bytes,
                        "boss-job-" + (images.size() + 1) + ".png"));
                coveredUntil = Math.max(coveredUntil, geometry.visibleEnd());
                first = false;
                // Re-measure after the image: lazy layout growth must not look like completion.
                Geometry after = measure(surface, phase, checkAllowed);
                if (Math.abs(after.contentTop() - originalTop) > EPSILON
                        || Math.abs(after.visibleStart() - geometry.visibleStart()) > EPSILON
                        || Math.abs(after.visibleEnd() - geometry.visibleEnd()) > EPSILON
                        || Math.abs(after.clip().x() - originalLeft) > EPSILON
                        || Math.abs(after.clip().width() - originalWidth) > EPSILON
                        || after.contentBottom() < geometry.contentBottom() - EPSILON) {
                    throw transientFailure("复核截图：页面移动或布局变化，不能确认完整覆盖");
                }
                if (coveredUntil >= after.contentBottom() - EPSILON) break;
                if (images.size() >= OfflineScreenshotService.MAX_IMAGES) {
                    throw failure("岗位内容超过6张截图，尚未完整覆盖，已取消本岗位识别");
                }
                double overlap = Math.min(OVERLAP, after.clip().height() / 3);
                double requested = after.scrollTop() + after.clip().height() - overlap;
                if (requested > after.maxScrollTop()) requested = after.maxScrollTop();
                if (requested <= after.scrollTop() + EPSILON) {
                    throw failure("岗位滚动容器已停止，仍有职位描述未进入可视区域");
                }
                checkpoint(surface, checkAllowed);
                surface.scrollTo(phase, requested);
                checkpoint(surface, checkAllowed);
                surface.awaitStable(phase, checkAllowed);
                geometry = measure(surface, phase, checkAllowed);
            }
        }
        checkpoint(surface, checkAllowed);
        return List.copyOf(images);
    }

    private static Geometry measure(CaptureSurface surface, int phase, Runnable checkAllowed) {
        checkpoint(surface, checkAllowed);
        Geometry result = surface.measure(phase);
        checkpoint(surface, checkAllowed);
        result.requireValid();
        return result;
    }

    private static void checkpoint(CaptureSurface surface, Runnable checkAllowed) {
        checkAllowed.run();
        surface.requireSameJob();
    }

    static void validateImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > OfflineScreenshotService.MAX_IMAGE_BYTES) {
            throw failure("岗位截图为空或超过8MiB");
        }
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw failure("岗位截图不是有效PNG");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!"png".equalsIgnoreCase(reader.getFormatName()) || width <= 0 || height <= 0
                        || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw failure("岗位截图尺寸无效或超过1600万像素");
                }
                if (reader.read(0) == null) throw failure("岗位截图损坏");
            } finally { reader.dispose(); }
        } catch (IOException unreadable) {
            throw failure("岗位截图损坏或无法解码");
        }
    }

    static boolean matchesJobUrl(String url, String expectedJobId) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && "www.zhipin.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null
                    && ("/job_detail/" + expectedJobId + ".html").equals(uri.getPath());
        } catch (IllegalArgumentException | NullPointerException invalid) { return false; }
    }

    private static CaptureException failure(String message) { return new CaptureException(message); }
    private static CaptureException transientFailure(String message) { return new CaptureException(message, true, null); }

    public static final class CaptureException extends IllegalStateException {
        private final boolean recoverable;
        private CaptureException(String message) { this(message, false, null); }
        private CaptureException(String message, boolean recoverable, Throwable cause) {
            super(message, cause);
            this.recoverable = recoverable;
        }
    }

    interface CaptureSurface {
        void requireSameJob();
        int initialize();
        void prepare(int phase);
        Geometry measure(int phase);
        void scrollTo(int phase, double position);
        byte[] screenshot(Clip clip);
        void restore();
        default void awaitReady(Runnable checkAllowed) { }
        default void awaitStable(int phase, Runnable checkAllowed) { }
        default boolean retryTransientFailures() { return false; }
    }

    record Clip(double x, double y, double width, double height) { }

    record Geometry(double contentTop, double contentBottom, double visibleStart, double visibleEnd,
                    double scrollTop, double maxScrollTop, Clip clip) {
        void requireValid() {
            if (clip == null || !Double.isFinite(contentTop) || !Double.isFinite(contentBottom)
                    || !Double.isFinite(visibleStart) || !Double.isFinite(visibleEnd)
                    || !Double.isFinite(scrollTop) || !Double.isFinite(maxScrollTop)
                    || !Double.isFinite(clip.x()) || !Double.isFinite(clip.y())
                    || !Double.isFinite(clip.width()) || !Double.isFinite(clip.height())
                    || contentBottom <= contentTop || visibleStart < contentTop - EPSILON
                    || visibleEnd > contentBottom + EPSILON || visibleEnd <= visibleStart
                    || clip.x() < 0 || clip.y() < 0 || clip.width() < 40 || clip.height() < 40
                    || clip.width() * clip.height() > MAX_IMAGE_PIXELS || scrollTop < 0 || maxScrollTop < 0) {
                throw transientFailure("读取岗位布局：可见区域尺寸尚未有效，无法确认完整截图");
            }
        }
    }

    private static final class PageSurface implements CaptureSurface {
        private static final String MASKS = "#header, #footer, body > header, body > footer, .job-sider, "
                + ".job-list-container, li.job-card-box, li.job-card-wrapper, .recommend-box, "
                + ".job-recommend, .sider-list, .dialog-container, .chat-container";
        private final Page page;
        private final String expectedJobId;
        private final String token = "__getjobsCapture_" + UUID.randomUUID().toString().replace("-", "");
        private final long deadlineNanos = System.nanoTime() + READINESS_TIMEOUT_MS * 1_000_000L;
        private int remainingWaitMs = READINESS_TIMEOUT_MS;
        private boolean initialized;

        private PageSurface(Page page, String expectedJobId) {
            this.page = page;
            this.expectedJobId = expectedJobId;
        }

        @Override public void requireSameJob() {
            if (page.isClosed() || !matchesJobUrl(page.url(), expectedJobId)) {
                throw failure("核对截图岗位：页面已关闭或岗位身份已改变，不再继续采集");
            }
        }

        @Override public boolean retryTransientFailures() { return true; }

        @Override public void awaitReady(Runnable checkAllowed) { awaitState(-1, checkAllowed); }

        @Override public void awaitStable(int phase, Runnable checkAllowed) { awaitState(phase, checkAllowed); }

        private void awaitState(int phase, Runnable checkAllowed) {
            String stage = phase < 0 ? "等待岗位内容" : "等待滚动后布局";
            String reason = "岗位内容尚未显示";
            StableSample previous = null;
            int stableMs = 0;
            while (true) {
                checkpoint(this, checkAllowed);
                if (remainingBudgetMs() <= 0) throw readinessTimeout(stage, reason);
                try {
                    Object raw = evaluate(READINESS_SCRIPT, Map.of());
                    checkpoint(this, checkAllowed);
                    if (!(raw instanceof Map<?, ?> state)) throw failure(stage + "：未取得有效就绪状态");
                    if (Boolean.TRUE.equals(state.get("ready"))) {
                        StableSample current = sample(state);
                        if (phase >= 0) {
                            Geometry geometry = measure(phase);
                            checkpoint(this, checkAllowed);
                            geometry.requireValid();
                            List<Double> dimensions = new ArrayList<>(current.dimensions());
                            dimensions.addAll(List.of(geometry.contentTop(), geometry.contentBottom(),
                                    geometry.visibleStart(), geometry.visibleEnd(), geometry.scrollTop(),
                                    geometry.clip().x(), geometry.clip().y(), geometry.clip().width(), geometry.clip().height()));
                            current = new StableSample(List.copyOf(dimensions), current.contentHash());
                        }
                        if (previous != null && previous.stableWith(current)) stableMs += POLL_INTERVAL_MS;
                        else { previous = current; stableMs = 0; }
                        reason = "岗位布局尚未连续稳定1秒";
                        if (stableMs >= STABLE_INTERVAL_MS) return;
                    } else {
                        previous = null;
                        stableMs = 0;
                        reason = readinessReason(state.get("reason"));
                    }
                } catch (CaptureException notReady) {
                    checkpoint(this, checkAllowed);
                    if (!notReady.recoverable) throw notReady;
                    previous = null;
                    stableMs = 0;
                    reason = notReady.getMessage();
                }
                int remaining = Math.min(POLL_INTERVAL_MS, remainingBudgetMs());
                if (remaining <= 0) throw readinessTimeout(stage, reason);
                while (remaining > 0) {
                    int slice = Math.min(250, remaining);
                    checkpoint(this, checkAllowed);
                    try { page.waitForTimeout(slice); }
                    catch (BossTaskPausedException | CancellationException stopped) { throw stopped; }
                    catch (RuntimeException failure) {
                        checkpoint(this, checkAllowed);
                        throw new CaptureException(stage + "：浏览器等待操作未完成", false, failure);
                    }
                    remainingWaitMs -= slice;
                    remaining -= slice;
                    checkpoint(this, checkAllowed);
                }
            }
        }

        private int remainingBudgetMs() {
            long wallMs = Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            return (int) Math.min(remainingWaitMs, wallMs);
        }

        private CaptureException readinessTimeout(String stage, String reason) {
            return failure(stage + "：30秒总采集预算内未完成就绪与稳定检查；最后状态：" + reason);
        }

        private StableSample sample(Map<?, ?> values) {
            if (!(values.get("geometry") instanceof List<?> raw) || !(values.get("contentHash") instanceof Number hash)) {
                throw failure("等待岗位内容：就绪状态缺少有效几何数据");
            }
            List<Double> dimensions = new ArrayList<>();
            for (Object item : raw) {
                if (!(item instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    throw transientFailure("等待岗位内容：布局尺寸尚未有效");
                }
                dimensions.add(number.doubleValue());
            }
            if (dimensions.isEmpty()) throw transientFailure("等待岗位内容：布局尺寸尚未有效");
            return new StableSample(List.copyOf(dimensions), hash.longValue());
        }

        private String readinessReason(Object code) {
            return switch (String.valueOf(code)) {
                case "HEADER" -> "岗位标题区域尚未显示";
                case "TITLE" -> "岗位标题尚未显示有效内容";
                case "SALARY" -> "岗位薪资尚未显示有效内容";
                case "DESCRIPTION" -> "职位描述尚未显示有效内容";
                case "FONTS" -> "页面字体仍在加载";
                case "BUSY" -> "岗位内容仍在更新";
                default -> "岗位内容或有效可见区域尚未就绪";
            };
        }

        @Override public int initialize() {
            // Set before evaluation so partially initialized browser state is also cleaned up.
            initialized = true;
            return ((Number) evaluate(INIT_SCRIPT, Map.of("token", token))).intValue();
        }

        @Override public void prepare(int phase) {
            evaluate(SCROLL_SCRIPT, Map.of("token", token, "phase", phase, "prepare", true, "position", 0));
        }

        @Override public Geometry measure(int phase) {
            Object raw = evaluate(MEASURE_SCRIPT, Map.of("token", token, "phase", phase));
            if (!(raw instanceof Map<?, ?> values)) throw failure("未能读取岗位截图几何信息");
            return new Geometry(number(values, "contentTop"), number(values, "contentBottom"),
                    number(values, "visibleStart"), number(values, "visibleEnd"),
                    number(values, "scrollTop"), number(values, "maxScrollTop"),
                    new Clip(number(values, "x"), number(values, "y"),
                            number(values, "width"), number(values, "height")));
        }

        @Override public void scrollTo(int phase, double position) {
            evaluate(SCROLL_SCRIPT, Map.of("token", token, "phase", phase, "prepare", false, "position", position));
        }

        @Override public byte[] screenshot(Clip clip) {
            int remaining = remainingBudgetMs();
            if (remaining <= 0) throw readinessTimeout("采集岗位截图", "总采集预算已用完");
            try {
                Locator masks = page.locator(MASKS);
                return page.screenshot(new Page.ScreenshotOptions().setFullPage(false)
                        .setType(ScreenshotType.PNG).setScale(ScreenshotScale.CSS)
                        .setClip(clip.x(), clip.y(), clip.width(), clip.height())
                        .setMask(List.of(masks)).setMaskColor("#ffffff").setTimeout(Math.min(15_000, remaining)));
            } catch (BossTaskPausedException | CancellationException stopped) { throw stopped; }
            catch (RuntimeException unavailable) {
                boolean timeout = unavailable instanceof com.microsoft.playwright.TimeoutError;
                throw new CaptureException(timeout ? "采集岗位截图：截图等待超时" : "采集岗位截图：浏览器未能生成有效图片",
                        timeout, unavailable);
            }
        }

        @Override public void restore() {
            if (initialized && !page.isClosed()) evaluate(RESTORE_SCRIPT, Map.of("token", token));
        }

        private Object evaluate(String script, Map<String, Object> args) {
            try { return page.evaluate(script, args); }
            catch (BossTaskPausedException | CancellationException stopped) { throw stopped; }
            catch (RuntimeException unavailable) {
                String stage = script == RESTORE_SCRIPT ? "恢复滚动位置" : script == INIT_SCRIPT ? "定位岗位内容"
                        : script == SCROLL_SCRIPT ? "滚动岗位内容" : script == READINESS_SCRIPT ? "等待岗位内容" : "读取岗位布局";
                String raw = unavailable.getMessage() == null ? "" : unavailable.getMessage();
                String reason;
                boolean recoverable = false;
                if (raw.contains("Job detail header or description unavailable")) {
                    reason = "岗位标题、薪资或职位描述尚未显示"; recoverable = true;
                } else if (raw.contains("Nested job description fragments")) reason = "职位描述包含多个独立滚动区域，无法确认完整覆盖";
                else if (raw.contains("Job description contains inaccessible clipped text")) reason = "职位描述存在不可见的截断内容，无法确认完整覆盖";
                else if (raw.contains("Job description region unavailable")) { reason = "职位描述区域尚未有效"; recoverable = true; }
                else if (raw.contains("Independent metadata scroll containers")) reason = "岗位基本信息分布于独立滚动区域，无法完整采集";
                else if (raw.contains("Job region changed")) { reason = "岗位区域正在重新渲染"; recoverable = true; }
                else if (raw.contains("Job content clipped horizontally")) reason = "岗位内容超出窗口宽度，无法确认横向完整覆盖";
                else if (raw.contains("Job identity changed")) {
                    requireSameJob();
                    reason = "当前岗位页面状态已更新，需要重新确认布局"; recoverable = true;
                } else if (raw.contains("Execution context was destroyed") || raw.contains("Cannot find context")
                        || raw.contains("Object doesn't exist")) {
                    reason = "页面执行上下文正在切换"; recoverable = true;
                } else reason = "浏览器暂时无法读取有效页面状态";
                if (script == RESTORE_SCRIPT) recoverable = false;
                throw new CaptureException(stage + "：" + reason, recoverable, unavailable);
            }
        }

        private double number(Map<?, ?> values, String key) {
            if (!(values.get(key) instanceof Number number)) throw failure("岗位截图缺少几何数据");
            return number.doubleValue();
        }
    }

    private record StableSample(List<Double> dimensions, long contentHash) {
        boolean stableWith(StableSample other) {
            if (contentHash != other.contentHash || dimensions.size() != other.dimensions.size()) return false;
            for (int i = 0; i < dimensions.size(); i++) {
                if (Math.abs(dimensions.get(i) - other.dimensions.get(i)) > EPSILON) return false;
            }
            return true;
        }
    }

    private static final String READINESS_SCRIPT = """
            () => {
              const shown = el => {
                if (!el || !el.isConnected) return false;
                const css = getComputedStyle(el), r = el.getBoundingClientRect();
                return css.display !== 'none' && css.visibility !== 'hidden' && r.width > 0 && r.height > 0;
              };
              const forbidden = '.job-list-container, li.job-card-box, li.job-card-wrapper, .dialog-container, .chat-container';
              const first = selector => [...document.querySelectorAll(selector)].find(el => shown(el) && !el.closest(forbidden));
              const header = first('.job-banner .info-primary, .job-primary .info-primary, .info-primary');
              const description = first('.job-detail .job-sec-text, .job-sec-text');
              if (!header) return {ready:false, reason:'HEADER'};
              const title = header.querySelector('h1, .name, .job-name'), salary = header.querySelector('.salary');
              if (!shown(title) || !title.textContent.trim()) return {ready:false, reason:'TITLE'};
              if (!shown(salary) || !salary.textContent.trim()) return {ready:false, reason:'SALARY'};
              if (!shown(description) || !description.textContent.trim()) return {ready:false, reason:'DESCRIPTION'};
              if (header.closest('[aria-busy="true"]') || description.closest('[aria-busy="true"]'))
                return {ready:false, reason:'BUSY'};
              if (document.fonts && document.fonts.status !== 'loaded') return {ready:false, reason:'FONTS'};
              const geometry = [];
              for (const el of [header, title, salary, description]) {
                const r = el.getBoundingClientRect();
                geometry.push(r.left,r.top,r.width,r.height,el.clientWidth,el.clientHeight,el.scrollWidth,el.scrollHeight);
              }
              // No job text leaves this readiness probe: only a local stability hash is returned.
              let contentHash = 2166136261;
              for (const el of [title, salary, description])
                for (const character of el.textContent) contentHash = Math.imul(contentHash ^ character.charCodeAt(0), 16777619);
              return {ready:true, geometry, contentHash:contentHash >>> 0};
            }
            """;

    /* DOM reads establish regions and readiness only; recognized job fields come exclusively from screenshots. */
    private static final String INIT_SCRIPT = """
            ({token}) => {
              const shown = el => {
                if (!el || !el.isConnected) return false;
                const style = getComputedStyle(el), r = el.getBoundingClientRect();
                return style.display !== 'none' && style.visibility !== 'hidden' && r.width > 0 && r.height > 0;
              };
              const forbidden = '.job-list-container, li.job-card-box, li.job-card-wrapper, .dialog-container, .chat-container';
              const first = selector => [...document.querySelectorAll(selector)].find(el => shown(el) && !el.closest(forbidden));
              const description = first('.job-detail .job-sec-text, .job-sec-text');
              const header = first('.job-banner .info-primary, .job-primary .info-primary, .info-primary');
              if (!description || !header || !header.querySelector('.salary') || !header.querySelector('h1, .name, .job-name'))
                throw new Error('Job detail header or description unavailable');
              const documentScroller = document.scrollingElement || document.documentElement;
              const scrollable = el => el && el !== document.body && el !== document.documentElement
                && /(auto|scroll|overlay)/.test(getComputedStyle(el).overflowY) && el.scrollHeight > el.clientHeight + 2;
              const nearest = el => {
                for (let current = el; current && current !== document.body; current = current.parentElement)
                  if (scrollable(current)) return current;
                return documentScroller;
              };
              const inner = nearest(description);
              // Multiple independently scrolling JD fragments cannot be represented by one traversal.
              if ([...description.querySelectorAll('*')].some(scrollable)) throw new Error('Nested job description fragments');
              const clipped = el => {
                const css = getComputedStyle(el);
                return (el.scrollHeight > el.clientHeight + 2 && /(hidden|clip)/.test(css.overflowY))
                  || (el.scrollWidth > el.clientWidth + 2 && /(hidden|clip)/.test(css.overflowX));
              };
              if ([description, ...description.querySelectorAll('*')].some(clipped)
                  || (description.scrollHeight > description.clientHeight + 2 && inner !== description))
                throw new Error('Job description contains inaccessible clipped text');
              const section = description.closest('.job-detail-section, .job-sec') || description.parentElement;
              if (!section || section.closest(forbidden)) throw new Error('Job description region unavailable');
              const company = first('.job-banner .info-company, .job-primary .info-company');
              const root = description.closest('.job-detail') || section;
              const recruiter = [...root.querySelectorAll('.boss-info')].find(shown);
              const metadata = [header, company, recruiter].filter(Boolean);
              let phases;
              if (inner === documentScroller) phases = [{targets: [...metadata, section], scroller: documentScroller}];
              else {
                const outside = metadata.filter(el => !inner.contains(el));
                const inside = metadata.filter(el => inner.contains(el));
                if (outside.length && outside.some(el => nearest(el) !== documentScroller))
                  throw new Error('Independent metadata scroll containers');
                phases = outside.length ? [{targets: outside, scroller: documentScroller}] : [];
                const jdTarget = inner === description || !inner.contains(section) ? description : section;
                phases.push({targets: [jdTarget, ...inside], scroller: inner});
              }
              const positions = new Map();
              for (const el of [...metadata, section, description])
                for (let current = el; current; current = current.parentElement)
                  if (!positions.has(current)) positions.set(current, {el: current, x: current.scrollLeft, y: current.scrollTop});
              if (!positions.has(documentScroller)) positions.set(documentScroller,
                {el: documentScroller, x: documentScroller.scrollLeft, y: documentScroller.scrollTop});
              const state = {url: location.origin + location.pathname, phases, positions: [...positions.values()], documentScroller};
              state.geometry = phaseIndex => {
                const phase = phases[phaseIndex];
                if (!phase || phase.targets.some(el => !shown(el)) || !phase.scroller.isConnected)
                  throw new Error('Job region changed');
                const scroller = phase.scroller, isDocument = scroller === documentScroller;
                const sr = scroller.getBoundingClientRect();
                const originTop = isDocument ? 0 : sr.top + scroller.clientTop;
                let viewTop = isDocument ? 0 : originTop;
                let viewBottom = isDocument ? innerHeight : originTop + scroller.clientHeight;
                let viewLeft = isDocument ? 0 : sr.left + scroller.clientLeft;
                let viewRight = isDocument ? innerWidth : viewLeft + scroller.clientWidth;
                // Respect all real clipping ancestors instead of assuming the window is the scroller.
                for (let parent = isDocument ? null : scroller.parentElement; parent; parent = parent.parentElement) {
                  const css = getComputedStyle(parent), r = parent.getBoundingClientRect();
                  if (/(auto|scroll|hidden|clip|overlay)/.test(css.overflowY)) {
                    viewTop = Math.max(viewTop, r.top + parent.clientTop);
                    viewBottom = Math.min(viewBottom, r.top + parent.clientTop + parent.clientHeight);
                  }
                  if (/(auto|scroll|hidden|clip|overlay)/.test(css.overflowX)) {
                    viewLeft = Math.max(viewLeft, r.left + parent.clientLeft);
                    viewRight = Math.min(viewRight, r.left + parent.clientLeft + parent.clientWidth);
                  }
                }
                viewTop = Math.max(0, viewTop); viewBottom = Math.min(innerHeight, viewBottom);
                viewLeft = Math.max(0, viewLeft); viewRight = Math.min(innerWidth, viewRight);
                // Fixed navigation is not visible JD and must not count towards coverage.
                for (const nav of document.querySelectorAll('#header, body > header, .boss-header')) {
                  const css = getComputedStyle(nav), r = nav.getBoundingClientRect();
                  if (shown(nav) && /fixed|sticky/.test(css.position) && r.top <= 2 && r.bottom < innerHeight / 2)
                    viewTop = Math.max(viewTop, r.bottom);
                }
                for (const footer of document.querySelectorAll('.fixed-footer, .job-detail-footer, .job-bottom, body > footer')) {
                  const css = getComputedStyle(footer), r = footer.getBoundingClientRect();
                  if (shown(footer) && /fixed|sticky/.test(css.position) && r.bottom >= innerHeight - 2
                      && r.top > innerHeight / 2) viewBottom = Math.min(viewBottom, r.top);
                }
                const scrollTop = scroller.scrollTop;
                const bounds = phase.targets.map(el => {
                  const r = el.getBoundingClientRect();
                  return el === scroller && !isDocument
                    ? {top: 0, bottom: scroller.scrollHeight, left: sr.left + scroller.clientLeft,
                       right: sr.left + scroller.clientLeft + scroller.clientWidth}
                    : {top: r.top - originTop + scrollTop, bottom: r.bottom - originTop + scrollTop,
                       left: r.left, right: r.right};
                });
                const contentTop = Math.min(...bounds.map(r => r.top));
                const contentBottom = Math.max(...bounds.map(r => r.bottom));
                const left = Math.min(...bounds.map(r => r.left)), right = Math.max(...bounds.map(r => r.right));
                if (left < viewLeft - 2 || right > viewRight + 2) throw new Error('Job content clipped horizontally');
                const start = Math.max(contentTop, scrollTop + viewTop - originTop);
                const end = Math.min(contentBottom, scrollTop + viewBottom - originTop);
                const x = Math.max(viewLeft, Math.floor(left)), y = Math.max(viewTop, Math.floor(start - scrollTop + originTop));
                return {contentTop, contentBottom, visibleStart: start, visibleEnd: end, scrollTop, originTop, viewTop,
                  maxScrollTop: Math.max(0, scroller.scrollHeight - scroller.clientHeight),
                  x, y, width: Math.min(viewRight, Math.ceil(right)) - x,
                  height: Math.min(viewBottom, Math.ceil(end - scrollTop + originTop)) - y};
              };
              window[token] = state;
              return phases.length;
            }
            """;

    private static final String MEASURE_SCRIPT = """
            ({token, phase}) => {
              const state = window[token];
              if (!state || state.url !== location.origin + location.pathname) throw new Error('Job identity changed');
              return state.geometry(phase);
            }
            """;

    private static final String SCROLL_SCRIPT = """
            ({token, phase, prepare, position}) => {
              const state = window[token];
              if (!state || state.url !== location.origin + location.pathname) throw new Error('Job identity changed');
              const current = state.phases[phase], scroller = current.scroller;
              if (prepare && scroller !== state.documentScroller)
                scroller.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'instant'});
              if (prepare) {
                const g = state.geometry(phase);
                // Content coordinates start at the real scroller origin; account for fixed navigation.
                position = Math.max(0, g.contentTop - (g.viewTop - g.originTop));
              }
              scroller.scrollTo({top: position, left: scroller.scrollLeft, behavior: 'instant'});
            }
            """;

    private static final String RESTORE_SCRIPT = """
            ({token}) => {
              const state = window[token];
              if (!state) return;
              try {
                if (state.url === location.origin + location.pathname) {
                  // Restore inner containers first, then the document, without scrolling a new job.
                  const positions = [...state.positions.filter(p => p.el !== state.documentScroller),
                    ...state.positions.filter(p => p.el === state.documentScroller)];
                  for (const position of positions)
                    if (position.el.isConnected) position.el.scrollTo({left: position.x, top: position.y, behavior: 'instant'});
                }
              } finally { delete window[token]; }
            }
            """;
}
