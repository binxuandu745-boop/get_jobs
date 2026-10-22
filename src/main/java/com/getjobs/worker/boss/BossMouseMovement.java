package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ViewportSize;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Moves the Playwright pointer only; the caller keeps the original Locator.click() action. */
@Component
public class BossMouseMovement {
    static final int DEFAULT_MINIMUM_MS = 450;
    static final int DEFAULT_MAXIMUM_MS = 1100;
    private static final int MINIMUM_STEPS = 12;
    private static final int MAXIMUM_STEPS = 48;
    private static final double LAYOUT_TOLERANCE = 3;
    private final RandomGenerator random;
    private final Map<Page, Point> positions = new WeakHashMap<>();

    public BossMouseMovement() { this(ThreadLocalRandom.current()); }

    BossMouseMovement(RandomGenerator random) { this.random = Objects.requireNonNull(random); }

    public void moveTo(Page page, Locator target, Runnable checkAllowed, int minimumMs, int maximumMs) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(checkAllowed, "checkAllowed");
        checked(checkAllowed, () -> {
            target.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(5000));
            return null;
        });
        requireActionable(target, checkAllowed);
        Bounds before = bounds(target, checkAllowed);
        Viewport viewport = viewport(page, checkAllowed);
        Point destination = before.center();
        viewport.requireInside(destination);
        Point start;
        synchronized (positions) { start = positions.getOrDefault(page, new Point(0, 0)); }
        start = viewport.clamp(start);
        MovementPlan plan = plan(start, destination, viewport, minimumMs, maximumMs);
        Mouse mouse = checked(checkAllowed, page::mouse);
        if (mouse == null) throw unavailable("浏览器鼠标不可用");
        for (MoveStep step : plan.steps()) {
            checked(checkAllowed, () -> {
                mouse.move(step.x(), step.y());
                // Record the real last move even if the following guard cancels this call.
                synchronized (positions) { positions.put(page, new Point(step.x(), step.y())); }
                return null;
            });
            // Short slices keep a slow configured move responsive to cancellation and verification.
            for (int remaining = step.delayMs(); remaining > 0;) {
                int slice = Math.min(50, remaining);
                checked(checkAllowed, () -> { page.waitForTimeout(slice); return null; });
                remaining -= slice;
            }
        }
        requireActionable(target, checkAllowed);
        Bounds after = bounds(target, checkAllowed);
        if (!before.stableWith(after)) {
            throw unavailable("鼠标移动期间目标位置变化，未执行点击");
        }
        // A resize must not leave the final click outside the visible viewport.
        viewport(page, checkAllowed).requireInside(after.center());
        check(checkAllowed);
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
        if (!checked(checkAllowed, target::isVisible) || !checked(checkAllowed, target::isEnabled)) {
            throw unavailable("鼠标移动目标不可见或不可用，未执行点击");
        }
    }

    private Bounds bounds(Locator target, Runnable checkAllowed) {
        BoundingBox box = checked(checkAllowed, target::boundingBox);
        if (box == null || !Double.isFinite(box.x) || !Double.isFinite(box.y)
                || !Double.isFinite(box.width) || !Double.isFinite(box.height)
                || box.width <= 0 || box.height <= 0) {
            throw unavailable("无法确认鼠标移动目标的有效位置，未执行点击");
        }
        return new Bounds(box.x, box.y, box.width, box.height);
    }

    private Viewport viewport(Page page, Runnable checkAllowed) {
        ViewportSize configured = checked(checkAllowed, page::viewportSize);
        if (configured != null) return new Viewport(configured.width, configured.height).validated();
        Object dimensions = checked(checkAllowed,
                () -> page.evaluate("() => ({ width: window.innerWidth, height: window.innerHeight })"));
        if (!(dimensions instanceof Map<?, ?> values) || !(values.get("width") instanceof Number width)
                || !(values.get("height") instanceof Number height)) {
            throw unavailable("无法确认浏览器可视区域，未执行点击");
        }
        return new Viewport(width.doubleValue(), height.doubleValue()).validated();
    }

    private <T> T checked(Runnable checkAllowed, Supplier<T> action) {
        check(checkAllowed);
        T result;
        try { result = action.get(); }
        catch (BossTaskPausedException | CancellationException stopped) { throw stopped; }
        catch (RuntimeException failure) {
            throw unavailable("鼠标移动操作失败（" + failure.getClass().getSimpleName() + "），未执行点击");
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

        boolean stableWith(Bounds other) {
            // Hover borders/scaling can change all four edges while keeping the click center stable.
            Point before = center(), after = other.center();
            return Math.hypot(before.x() - after.x(), before.y() - after.y()) <= LAYOUT_TOLERANCE;
        }
    }
}
