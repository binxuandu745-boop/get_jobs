package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ViewportSize;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Moves the Playwright pointer only; the caller keeps the original Locator.click() action. */
@Component
@Slf4j
public class BossMouseMovement {
    static final int DEFAULT_MINIMUM_MS = 450;
    static final int DEFAULT_MAXIMUM_MS = 1100;
    private static final int MINIMUM_STEPS = 12;
    private static final int MAXIMUM_STEPS = 48;
    private static final double LAYOUT_TOLERANCE = 3;
    static final double DEFAULT_TARGET_INSET_RATIO = 0.25;
    static final String BORDER_INSETS_SCRIPT = """
            el => {
                const style = getComputedStyle(el);
                const rect = el.getBoundingClientRect();
                const number = value => {
                    const n = parseFloat(value);
                    return Number.isFinite(n) && n >= 0 ? n : 0;
                };
                return {
                    left: number(style.borderLeftWidth), top: number(style.borderTopWidth),
                    right: number(style.borderRightWidth), bottom: number(style.borderBottomWidth),
                    scaleX: el.offsetWidth > 0 ? rect.width / el.offsetWidth : 1,
                    scaleY: el.offsetHeight > 0 ? rect.height / el.offsetHeight : 1
                };
            }
            """;
    static final String LANDING_POINTS_SCRIPT = """
            (el, request) => {
                const rect = el.getBoundingClientRect();
                const independentControl = 'a, button, input, select, textarea, [role="button"], '
                    + '[role="link"], [contenteditable="true"]';
                for (let i = 0; i < request.points.length; i++) {
                    const point = request.points[i];
                    const x = rect.left + point.x - request.origin.x;
                    const y = rect.top + point.y - request.origin.y;
                    const hit = el.ownerDocument.elementFromPoint(x, y);
                    if (!hit || (hit !== el && !el.contains(hit))) continue;
                    let blocked = false;
                    for (let node = hit; node && node !== el; node = node.parentElement) {
                        if (node.matches(independentControl)) {
                            blocked = true;
                            break;
                        }
                    }
                    if (!blocked) return i;
                }
                return -1;
            }
            """;
    private final RandomGenerator random;
    private final Map<Page, Point> positions = new WeakHashMap<>();

    public BossMouseMovement() { this(ThreadLocalRandom.current()); }

    BossMouseMovement(RandomGenerator random) { this.random = Objects.requireNonNull(random); }

    public void moveTo(Page page, Locator target, Runnable checkAllowed, int minimumMs, int maximumMs) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(checkAllowed, "checkAllowed");
        checked(checkAllowed, "滚动目标进入视口", () -> {
            target.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(5000));
            return null;
        });
        requireActionable(target, checkAllowed);
        Bounds before = bounds(target, checkAllowed, "读取移动前目标坐标");
        Viewport viewport = viewport(page, checkAllowed);
        Point destination = before.center();
        viewport.requireInside(destination);
        Point start;
        synchronized (positions) { start = positions.getOrDefault(page, new Point(0, 0)); }
        start = viewport.clamp(start);
        MovementPlan plan = plan(start, destination, viewport, minimumMs, maximumMs);
        Mouse mouse = checked(checkAllowed, "取得页面鼠标", page::mouse);
        if (mouse == null) throw unavailable("浏览器鼠标不可用");
        for (MoveStep step : plan.steps()) {
            checked(checkAllowed, "移动指针", () -> {
                mouse.move(step.x(), step.y());
                // Record the real last move even if the following guard cancels this call.
                synchronized (positions) { positions.put(page, new Point(step.x(), step.y())); }
                return null;
            });
            // Short slices keep a slow configured move responsive to cancellation and verification.
            for (int remaining = step.delayMs(); remaining > 0;) {
                int slice = Math.min(50, remaining);
                checked(checkAllowed, "移动分段等待", () -> { page.waitForTimeout(slice); return null; });
                remaining -= slice;
            }
        }
        requireActionable(target, checkAllowed);
        Bounds after = bounds(target, checkAllowed, "读取移动后目标坐标");
        if (!before.stableWith(after)) {
            throw unavailable("鼠标移动期间目标位置变化，未执行点击");
        }
        // A resize must not leave the final click outside the visible viewport.
        viewport(page, checkAllowed).requireInside(after.center());
        check(checkAllowed);
    }

    /** Prepares one target-relative click without selecting a new or fictitious pointer start. */
    PreparedClick prepareClick(Page page, Locator target, Runnable checkAllowed, int minimumMs,
                               int maximumMs, double insetRatio, boolean pathVariation) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(checkAllowed, "checkAllowed");
        checked(checkAllowed, "滚动目标进入视口", () -> {
            target.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(5000));
            return null;
        });
        requireActionable(target, checkAllowed);
        Bounds before = bounds(target, checkAllowed, "读取移动前目标坐标");
        BorderInsets border = borderInsets(target, checkAllowed);
        Viewport viewport = viewport(page, checkAllowed);
        Point destination = safeDestination(target, before,
                candidateDestinations(before, border, viewport, insetRatio), checkAllowed);
        Point start;
        synchronized (positions) { start = positions.getOrDefault(page, new Point(0, 0)); }
        // A resized viewport must not turn an out-of-bounds cached position into a made-up start.
        viewport.requireInside(start);
        MovementPlan movementPlan = pathVariation
                ? variedPlan(start, destination, viewport, minimumMs, maximumMs)
                : plan(start, destination, viewport, minimumMs, maximumMs);
        executePlan(page, movementPlan, checkAllowed);
        requireActionable(target, checkAllowed);
        Bounds after = bounds(target, checkAllowed, "读取移动后目标坐标");
        BorderInsets afterBorder = borderInsets(target, checkAllowed);
        requireStableDestination(before, after, afterBorder, destination, viewport(page, checkAllowed));
        requireSafeLanding(target, after, destination, checkAllowed);
        check(checkAllowed);
        log.info("Boss鼠标路径准备完成：起点=({}, {})，落点=({}, {})，步数={}，计划等待={}毫秒",
                Math.round(start.x()), Math.round(start.y()), Math.round(destination.x()), Math.round(destination.y()),
                movementPlan.steps().size(), movementPlan.durationMs());
        return new PreparedClick(page, target, start, destination, before,
                movementPlan.durationMs(), movementPlan.steps().size());
    }

    /** Consumes a prepared click once; Locator.click keeps its normal actionability checks. */
    Locator.ClickOptions clickOptions(Page page, Locator target, PreparedClick prepared, Runnable checkAllowed) {
        Objects.requireNonNull(checkAllowed, "checkAllowed");
        check(checkAllowed);
        if (prepared == null || prepared.page != page || prepared.target != target) {
            throw unavailable("鼠标点击准备结果与当前页面或控件不一致，未执行点击");
        }
        if (!prepared.consumed.compareAndSet(false, true)) {
            throw unavailable("鼠标点击准备结果已使用，未重复执行点击");
        }
        requireActionable(target, checkAllowed);
        Bounds current = bounds(target, checkAllowed, "读取点击前目标坐标");
        BorderInsets border = borderInsets(target, checkAllowed);
        requireStableDestination(prepared.before, current, border, prepared.destination,
                viewport(page, checkAllowed));
        requireSafeLanding(target, current, prepared.destination, checkAllowed);
        Point pointer;
        synchronized (positions) { pointer = positions.get(page); }
        if (pointer == null || Math.hypot(pointer.x() - prepared.destination.x(),
                pointer.y() - prepared.destination.y()) > 0.001) {
            throw unavailable("鼠标准备后已执行其他移动，未使用过期落点点击");
        }
        // Playwright's offset point is boundingBox origin + parseInt(CSS border) + local position.
        // Do not use the scaled border here: the driver uses CSS border values even under scaling.
        double x = prepared.destination.x() - current.x() - Math.floor(border.left());
        double y = prepared.destination.y() - current.y() - Math.floor(border.top());
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0) {
            throw unavailable("无法确认目标内的有效点击坐标，未执行点击");
        }
        check(checkAllowed);
        return new Locator.ClickOptions().setPosition(x, y);
    }

    private void executePlan(Page page, MovementPlan plan, Runnable checkAllowed) {
        Mouse mouse = checked(checkAllowed, "取得页面鼠标", page::mouse);
        if (mouse == null) throw unavailable("浏览器鼠标不可用");
        for (MoveStep step : plan.steps()) {
            checked(checkAllowed, "移动指针", () -> {
                mouse.move(step.x(), step.y());
                synchronized (positions) { positions.put(page, new Point(step.x(), step.y())); }
                return null;
            });
            for (int remaining = step.delayMs(); remaining > 0;) {
                int slice = Math.min(50, remaining);
                checked(checkAllowed, "移动分段等待", () -> { page.waitForTimeout(slice); return null; });
                remaining -= slice;
            }
        }
    }

    private List<Point> candidateDestinations(Bounds bounds, BorderInsets border, Viewport viewport,
                                             double insetRatio) {
        if (!Double.isFinite(insetRatio) || insetRatio < 0.05 || insetRatio > 0.45) {
            insetRatio = DEFAULT_TARGET_INSET_RATIO;
        }
        Bounds padding = border.paddingBounds(bounds);
        double minX = Math.max(0, padding.x() + padding.width() * insetRatio);
        double minY = Math.max(0, padding.y() + padding.height() * insetRatio);
        double maxX = Math.min(Math.nextDown(viewport.width()),
                padding.x() + padding.width() * (1 - insetRatio));
        double maxY = Math.min(Math.nextDown(viewport.height()),
                padding.y() + padding.height() * (1 - insetRatio));
        if (minX > maxX || minY > maxY) {
            throw unavailable("目标内部没有可见的安全落点，未执行移动或点击");
        }
        List<Point> candidates = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            Point point = new Point(minX == maxX ? minX : random.nextDouble(minX, maxX),
                    minY == maxY ? minY : random.nextDouble(minY, maxY));
            viewport.requireInside(point);
            if (!padding.contains(point)) throw unavailable("目标内部落点无效，未执行移动或点击");
            candidates.add(point);
        }
        return List.copyOf(candidates);
    }

    /** A parent card must not accidentally click a separate link/button nested inside it. */
    private Point safeDestination(Locator target, Bounds bounds, List<Point> candidates, Runnable checkAllowed) {
        Map<String, Object> request = Map.of(
                "origin", Map.of("x", bounds.x(), "y", bounds.y()),
                "points", candidates.stream().map(point -> Map.of("x", point.x(), "y", point.y())).toList());
        Object raw = checked(checkAllowed, "核对目标内部落点", () -> target.evaluate(LANDING_POINTS_SCRIPT, request));
        if (!(raw instanceof Number index) || !Double.isFinite(index.doubleValue())
                || index.doubleValue() != index.intValue() || index.intValue() < 0
                || index.intValue() >= candidates.size()) {
            throw unavailable("目标内部没有可确认的独立落点，未执行点击");
        }
        return candidates.get(index.intValue());
    }

    private void requireSafeLanding(Locator target, Bounds bounds, Point destination, Runnable checkAllowed) {
        // A hover-revealed child control invalidates the existing point; do not choose again or click it.
        safeDestination(target, bounds, List.of(destination), checkAllowed);
    }

    private BorderInsets borderInsets(Locator target, Runnable checkAllowed) {
        Object raw = checked(checkAllowed, "读取目标边框与缩放", () -> target.evaluate(BORDER_INSETS_SCRIPT));
        if (!(raw instanceof Map<?, ?> values)) {
            throw unavailable("无法确认目标边框与缩放，未执行点击");
        }
        return new BorderInsets(geometryNumber(values, "left", false), geometryNumber(values, "top", false),
                geometryNumber(values, "right", false), geometryNumber(values, "bottom", false),
                geometryNumber(values, "scaleX", true), geometryNumber(values, "scaleY", true));
    }

    private static double geometryNumber(Map<?, ?> values, String name, boolean positive) {
        if (!(values.get(name) instanceof Number value) || !Double.isFinite(value.doubleValue())
                || (positive ? value.doubleValue() <= 0 : value.doubleValue() < 0)) {
            throw unavailable("目标边框或缩放数据无效，未执行点击");
        }
        return value.doubleValue();
    }

    private void requireStableDestination(Bounds before, Bounds after, BorderInsets border,
                                          Point destination, Viewport viewport) {
        if (!before.stableWith(after)) throw unavailable("鼠标移动期间目标位置变化，未执行点击");
        viewport.requireInside(destination);
        if (!border.paddingBounds(after).contains(destination)) {
            throw unavailable("鼠标落点已不在目标内部，未执行点击");
        }
    }

    MovementPlan variedPlan(Point start, Point end, Viewport viewport, int minimumMs, int maximumMs) {
        if (minimumMs < 200 || maximumMs < minimumMs || maximumMs > 5000) {
            minimumMs = DEFAULT_MINIMUM_MS;
            maximumMs = DEFAULT_MAXIMUM_MS;
        }
        viewport.requireInside(start);
        viewport.requireInside(end);
        int duration = random.nextInt(minimumMs, maximumMs + 1);
        int count = random.nextInt(MINIMUM_STEPS, MAXIMUM_STEPS + 1);
        double dx = end.x() - start.x(), dy = end.y() - start.y();
        double distance = Math.hypot(dx, dy);
        double normalX = distance < 0.01 ? 1 : -dy / distance;
        double normalY = distance < 0.01 ? 0 : dx / distance;
        double spread = Math.min(48, Math.max(6, distance * 0.12));
        double firstT = random.nextDouble(0.15, 0.45), secondT = random.nextDouble(0.55, 0.85);
        double firstOffset = random.nextDouble(-spread, spread);
        double secondOffset = random.nextDouble(-spread, spread);
        Point first = viewport.clamp(new Point(start.x() + dx * firstT + normalX * firstOffset,
                start.y() + dy * firstT + normalY * firstOffset));
        Point second = viewport.clamp(new Point(start.x() + dx * secondT + normalX * secondOffset,
                start.y() + dy * secondT + normalY * secondOffset));
        double[] weights = new double[count];
        double totalWeight = 0;
        for (int i = 0; i < count; i++) totalWeight += weights[i] = 0.5 + random.nextDouble();
        List<MoveStep> steps = new ArrayList<>(count);
        int assigned = 0, previousExtra = 0;
        double cumulativeWeight = 0;
        for (int i = 1; i <= count; i++) {
            double t = (double) i / count;
            double eased = t * t * (3 - 2 * t), reverse = 1 - eased;
            Point point = i == count ? end : viewport.clamp(new Point(
                    reverse * reverse * reverse * start.x() + 3 * reverse * reverse * eased * first.x()
                            + 3 * reverse * eased * eased * second.x() + eased * eased * eased * end.x(),
                    reverse * reverse * reverse * start.y() + 3 * reverse * reverse * eased * first.y()
                            + 3 * reverse * eased * eased * second.y() + eased * eased * eased * end.y()));
            cumulativeWeight += weights[i - 1];
            int extra = (int) Math.floor((duration - count) * cumulativeWeight / totalWeight);
            int delay = i == count ? duration - assigned : 1 + extra - previousExtra;
            steps.add(new MoveStep(point.x(), point.y(), delay));
            assigned += delay;
            previousExtra = extra;
        }
        return new MovementPlan(List.copyOf(steps), duration);
    }

    MovementPlan plan(Point start, Point end, Viewport viewport, int minimumMs, int maximumMs) {
        if (minimumMs < 200 || maximumMs < minimumMs || maximumMs > 5000) {
            minimumMs = DEFAULT_MINIMUM_MS;
            maximumMs = DEFAULT_MAXIMUM_MS;
        }
        viewport.requireInside(start);
        viewport.requireInside(end);
        int duration = random.nextInt(minimumMs, maximumMs + 1);
        int count = random.nextInt(MINIMUM_STEPS, MAXIMUM_STEPS + 1);
        double dx = end.x() - start.x(), dy = end.y() - start.y();
        double distance = Math.hypot(dx, dy);
        double angle = random.nextDouble() * Math.PI * 2;
        double normalX = distance < 0.01 ? Math.cos(angle) : -dy / distance;
        double normalY = distance < 0.01 ? Math.sin(angle) : dx / distance;
        double amplitude = (distance < 0.01 ? 4 + random.nextDouble() * 8
                : Math.min(24, Math.max(6, distance * 0.08)) * (0.4 + random.nextDouble() * 0.6));
        if (random.nextBoolean()) amplitude = -amplitude;
        double[] weights = new double[count];
        double totalWeight = 0;
        for (int i = 0; i < count; i++) totalWeight += weights[i] = 0.5 + random.nextDouble();
        List<MoveStep> steps = new ArrayList<>(count);
        int assigned = 0, previousExtra = 0;
        double cumulativeWeight = 0;
        for (int i = 1; i <= count; i++) {
            double t = (double) i / count;
            double eased = t * t * (3 - 2 * t);
            double arc = amplitude * Math.sin(Math.PI * eased);
            Point point = i == count ? end : viewport.clamp(new Point(
                    start.x() + dx * eased + normalX * arc,
                    start.y() + dy * eased + normalY * arc));
            cumulativeWeight += weights[i - 1];
            int extra = (int) Math.floor((duration - count) * cumulativeWeight / totalWeight);
            int delay = i == count ? duration - assigned : 1 + extra - previousExtra;
            steps.add(new MoveStep(point.x(), point.y(), delay));
            assigned += delay;
            previousExtra = extra;
        }
        return new MovementPlan(List.copyOf(steps), duration);
    }

    private void requireActionable(Locator target, Runnable checkAllowed) {
        if (!checked(checkAllowed, "检查目标可见性", target::isVisible)
                || !checked(checkAllowed, "检查目标可用状态", target::isEnabled)) {
            throw unavailable("鼠标移动目标不可见或不可用，未执行点击");
        }
    }

    private Bounds bounds(Locator target, Runnable checkAllowed, String operation) {
        BoundingBox box = checked(checkAllowed, operation, target::boundingBox);
        if (box == null || !Double.isFinite(box.x) || !Double.isFinite(box.y)
                || !Double.isFinite(box.width) || !Double.isFinite(box.height)
                || box.width <= 0 || box.height <= 0) {
            throw unavailable("无法确认鼠标移动目标的有效位置，未执行点击");
        }
        return new Bounds(box.x, box.y, box.width, box.height);
    }

    private Viewport viewport(Page page, Runnable checkAllowed) {
        ViewportSize configured = checked(checkAllowed, "读取视口配置", page::viewportSize);
        if (configured != null) return new Viewport(configured.width, configured.height).validated();
        Object dimensions = checked(checkAllowed, "读取窗口尺寸",
                () -> page.evaluate("() => ({ width: window.innerWidth, height: window.innerHeight })"));
        if (!(dimensions instanceof Map<?, ?> values) || !(values.get("width") instanceof Number width)
                || !(values.get("height") instanceof Number height)) {
            throw unavailable("无法确认浏览器可视区域，未执行点击");
        }
        return new Viewport(width.doubleValue(), height.doubleValue()).validated();
    }

    private <T> T checked(Runnable checkAllowed, String operation, Supplier<T> action) {
        check(checkAllowed);
        long started = System.nanoTime();
        T result;
        try { result = action.get(); }
        catch (BossTaskPausedException | CancellationException stopped) { throw stopped; }
        catch (RuntimeException failure) {
            // Keep the original cause for local diagnosis without copying provider messages/URLs to logs or SSE.
            StackTraceElement[] frames = failure.getStackTrace();
            log.warn("Boss鼠标准备子步骤失败：步骤={}，耗时={}毫秒，异常类型={}，调用栈={}",
                    operation, (System.nanoTime() - started) / 1_000_000L, failure.getClass().getName(),
                    Arrays.toString(Arrays.copyOf(frames, Math.min(8, frames.length))));
            throw BossTaskPausedException.detailsUnavailable("鼠标准备子步骤[" + operation + "]失败（"
                    + failure.getClass().getSimpleName() + "），该控件尚未执行点击", failure);
        }
        check(checkAllowed);
        return result;
    }

    private void check(Runnable checkAllowed) {
        checkAllowed.run();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("用户取消任务");
    }

    private static BossTaskPausedException unavailable(String message) {
        return BossTaskPausedException.detailsUnavailable(message);
    }

    record Point(double x, double y) { }
    record MoveStep(double x, double y, int delayMs) { }
    record MovementPlan(List<MoveStep> steps, int durationMs) { }

    static final class PreparedClick {
        private final Page page;
        private final Locator target;
        private final Point start;
        private final Point destination;
        private final Bounds before;
        private final int plannedDurationMs;
        private final int stepCount;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private PreparedClick(Page page, Locator target, Point start, Point destination, Bounds before,
                              int plannedDurationMs, int stepCount) {
            this.page = page;
            this.target = target;
            this.start = start;
            this.destination = destination;
            this.before = before;
            this.plannedDurationMs = plannedDurationMs;
            this.stepCount = stepCount;
        }

        Point start() { return start; }
        Point destination() { return destination; }
        int plannedDurationMs() { return plannedDurationMs; }
        int stepCount() { return stepCount; }
    }

    private record BorderInsets(double left, double top, double right, double bottom, double scaleX, double scaleY) {
        Bounds paddingBounds(Bounds bounds) {
            double x = bounds.x() + left * scaleX, y = bounds.y() + top * scaleY;
            double width = bounds.width() - (left + right) * scaleX;
            double height = bounds.height() - (top + bottom) * scaleY;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
                    || !Double.isFinite(height) || width <= 0 || height <= 0) {
                throw unavailable("目标内部没有有效的点击区域，未执行点击");
            }
            return new Bounds(x, y, width, height);
        }
    }

    record Viewport(double width, double height) {
        Viewport validated() {
            if (!Double.isFinite(width) || !Double.isFinite(height) || width < 1 || height < 1) {
                throw unavailable("浏览器可视区域尺寸无效，未执行点击");
            }
            return this;
        }

        void requireInside(Point point) {
            validated();
            if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())
                    || point.x() < 0 || point.y() < 0 || point.x() >= width || point.y() >= height) {
                throw unavailable("鼠标移动目标中心不在可视区域，未执行点击");
            }
        }

        Point clamp(Point point) {
            return new Point(Math.max(0, Math.min(width - 1, point.x())),
                    Math.max(0, Math.min(height - 1, point.y())));
        }
    }

    private record Bounds(double x, double y, double width, double height) {
        Point center() { return new Point(x + width / 2, y + height / 2); }

        boolean contains(Point point) {
            return point != null && point.x() > x && point.x() < x + width
                    && point.y() > y && point.y() < y + height;
        }

        boolean stableWith(Bounds other) {
            // Hover borders/scaling can change all four edges while keeping the click center stable.
            Point before = center(), after = other.center();
            return Math.hypot(before.x() - after.x(), before.y() - after.y()) <= LAYOUT_TOLERANCE;
        }
    }
}
