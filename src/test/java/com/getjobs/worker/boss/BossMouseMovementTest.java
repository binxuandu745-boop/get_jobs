package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pointer calls and geometry only: no live browser, website or actual wait. */
class BossMouseMovementTest {
    private BossMouseMovement movement;
    private Page page;
    private Locator target;
    private Mouse mouse;
    private final List<BossMouseMovement.Point> points = new ArrayList<>();
    private final List<Double> waits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        movement = new BossMouseMovement(new Random(37));
        page = mock(Page.class);
        target = mock(Locator.class);
        mouse = mock(Mouse.class);
        when(page.mouse()).thenReturn(mouse);
        when(page.viewportSize()).thenReturn(new ViewportSize(1280, 800));
        when(target.isVisible()).thenReturn(true);
        when(target.isEnabled()).thenReturn(true);
        when(target.boundingBox()).thenReturn(box(400, 200, 100, 40));
        doAnswer(invocation -> {
            points.add(new BossMouseMovement.Point(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(mouse).move(anyDouble(), anyDouble());
        doAnswer(invocation -> { waits.add(invocation.getArgument(0)); return null; })
                .when(page).waitForTimeout(anyDouble());
    }

    @Test
    void realMovesAndPositiveWaitsEndAtTheLocatorCenterWithoutClicking() {
        AtomicInteger checks = new AtomicInteger();

        movement.moveTo(page, target, checks::incrementAndGet, 450, 1100);

        assertTrue(points.size() >= 12 && points.size() <= 48);
        assertEquals(new BossMouseMovement.Point(450, 220), points.getLast());
        assertTrue(waits.size() >= points.size());
        assertTrue(waits.stream().allMatch(delay -> delay > 0 && delay <= 50));
        double total = waits.stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(total >= 450 && total <= 1100);
        assertTrue(checks.get() >= points.size() * 4);
        verify(target).scrollIntoViewIfNeeded(argThat(options -> options.timeout == 5000));
        verify(target, never()).click();
        verify(mouse, never()).click(anyDouble(), anyDouble());
    }

    @Test
    void plannerIsSeedableAndKeepsExactDurationWithVaryingPositiveStepWaits() {
        var start = new BossMouseMovement.Point(0, 0);
        var end = new BossMouseMovement.Point(450, 220);
        var viewport = new BossMouseMovement.Viewport(1280, 800);
        var first = new BossMouseMovement(new Random(9)).plan(start, end, viewport, 450, 1100);
        var same = new BossMouseMovement(new Random(9)).plan(start, end, viewport, 450, 1100);
        var other = new BossMouseMovement(new Random(10)).plan(start, end, viewport, 450, 1100);

        assertEquals(first, same);
        assertNotEquals(first, other);
        assertEquals(first.durationMs(), first.steps().stream().mapToInt(BossMouseMovement.MoveStep::delayMs).sum());
        assertTrue(first.steps().stream().allMatch(step -> step.delayMs() > 0));
        assertTrue(first.steps().stream().map(BossMouseMovement.MoveStep::delayMs).distinct().count() > 1);
        assertEquals(end.x(), first.steps().getLast().x());
        assertEquals(end.y(), first.steps().getLast().y());
    }

    @Test
    void everyPlannedPointStaysInTheViewportIncludingAtItsEdges() {
        for (int seed = 0; seed < 100; seed++) {
            var plan = new BossMouseMovement(new Random(seed)).plan(new BossMouseMovement.Point(0, 0),
                    new BossMouseMovement.Point(1279, 799), new BossMouseMovement.Viewport(1280, 800), 200, 5000);
            assertTrue(plan.steps().stream().allMatch(step -> step.x() >= 0 && step.x() < 1280
                    && step.y() >= 0 && step.y() < 800 && step.delayMs() > 0));
        }
    }

    @ParameterizedTest
    @CsvSource({"0,100", "199,1100", "600,599", "5000,5001", "-1,-2"})
    void invalidDurationConfigurationFallsBackTo450Through1100(int minimum, int maximum) {
        movement.moveTo(page, target, () -> {}, minimum, maximum);

        double total = waits.stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(total >= 450 && total <= 1100);
    }

    @ParameterizedTest
    @CsvSource({"200,200", "5000,5000"})
    void validDurationBoundariesAreInclusive(int minimum, int maximum) {
        movement.moveTo(page, target, () -> {}, minimum, maximum);

        assertEquals(minimum, waits.stream().mapToDouble(Double::doubleValue).sum());
    }

    @Test
    void nullViewportUsesReadOnlyWindowDimensions() {
        when(page.viewportSize()).thenReturn(null);
        when(page.evaluate("() => ({ width: window.innerWidth, height: window.innerHeight })"))
                .thenReturn(Map.of("width", 1280, "height", 800));

        movement.moveTo(page, target, () -> {}, 450, 1100);

        assertEquals(new BossMouseMovement.Point(450, 220), points.getLast());
        verify(page, times(2)).evaluate("() => ({ width: window.innerWidth, height: window.innerHeight })");
    }

    @Test
    void invalidWindowDimensionsFailBeforeAnyMovement() {
        when(page.viewportSize()).thenReturn(null);
        when(page.evaluate(anyString())).thenReturn(Map.of("width", 0, "height", 800));

        assertThrows(BossTaskPausedException.class, () -> movement.moveTo(page, target, () -> {}, 450, 1100));

        verifyNoInteractions(mouse);
    }

    @Test
    void offscreenCenterFailsBeforeMovement() {
        when(target.boundingBox()).thenReturn(box(1300, 100, 100, 40));

        assertThrows(BossTaskPausedException.class, () -> movement.moveTo(page, target, () -> {}, 450, 1100));

        verifyNoInteractions(mouse);
    }

    @Test
    void missingOrNonfiniteBoundingBoxFailsBeforeMovement() {
        when(target.boundingBox()).thenReturn(null);
        assertThrows(BossTaskPausedException.class, () -> movement.moveTo(page, target, () -> {}, 450, 1100));
        when(target.boundingBox()).thenReturn(box(Double.NaN, 100, 100, 40));
        assertThrows(BossTaskPausedException.class, () -> movement.moveTo(page, target, () -> {}, 450, 1100));
        verifyNoInteractions(mouse);
    }

    @Test
    void disabledTargetDoesNotMove() {
        when(target.isEnabled()).thenReturn(false);

        assertThrows(BossTaskPausedException.class, () -> movement.moveTo(page, target, () -> {}, 450, 1100));

        verifyNoInteractions(mouse);
    }

    @Test
    void finalLayoutChangeFailsWithoutClickingOrReplanning() {
        when(target.boundingBox()).thenReturn(box(400, 200, 100, 40), box(600, 200, 100, 40));

        var failure = assertThrows(BossTaskPausedException.class,
                () -> movement.moveTo(page, target, () -> {}, 450, 1100));

        assertTrue(failure.getMessage().contains("目标位置变化"));
        verify(target, times(2)).boundingBox();
        verify(target, never()).click();
    }

    @Test
    void hoverScalingAroundTheSameClickCenterDoesNotCauseAFalsePause() {
        when(target.boundingBox()).thenReturn(box(400, 200, 100, 40), box(396, 198, 108, 44));

        assertDoesNotThrow(() -> movement.moveTo(page, target, () -> {}, 450, 1100));

        assertEquals(new BossMouseMovement.Point(450, 220), points.getLast());
    }

    @Test
    void zeroDistanceStillFollowsASmallArcAndReturnsExactlyToCenter() {
        movement.moveTo(page, target, () -> {}, 450, 1100);
        points.clear();
        waits.clear();

        movement.moveTo(page, target, () -> {}, 450, 1100);

        assertTrue(points.size() >= 12);
        assertTrue(points.stream().anyMatch(point -> Math.hypot(point.x() - 450, point.y() - 220) > 1));
        assertTrue(points.stream().allMatch(point -> Math.hypot(point.x() - 450, point.y() - 220) <= 12.01));
        assertEquals(new BossMouseMovement.Point(450, 220), points.getLast());
        assertTrue(waits.stream().mapToDouble(Double::doubleValue).sum() >= 450);
    }

    @Test
    void positionsAreTrackedPerPageInsteadOfSharingAnotherTabsEndpoint() {
        movement.moveTo(page, target, () -> {}, 450, 1100);
        Page other = mock(Page.class);
        Mouse otherMouse = mock(Mouse.class);
        when(other.mouse()).thenReturn(otherMouse);
        when(other.viewportSize()).thenReturn(new ViewportSize(1280, 800));
        List<BossMouseMovement.Point> otherPoints = new ArrayList<>();
        doAnswer(invocation -> {
            otherPoints.add(new BossMouseMovement.Point(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(otherMouse).move(anyDouble(), anyDouble());

        movement.moveTo(other, target, () -> {}, 450, 1100);

        assertTrue(otherPoints.getFirst().x() < 50 && otherPoints.getFirst().y() < 50);
        assertEquals(new BossMouseMovement.Point(450, 220), otherPoints.getLast());
    }

    @Test
    void cancellationDuringMovementPreservesTheExactExceptionAndStopsImmediately() {
        CancellationException cancellation = new CancellationException("cancelled");
        Runnable guard = () -> { if (points.size() >= 3) throw cancellation; };

        assertSame(cancellation, assertThrows(CancellationException.class,
                () -> movement.moveTo(page, target, guard, 450, 1100)));

        assertEquals(3, points.size());
        verify(target, never()).click();
    }

    @Test
    void verificationDuringASlicedWaitPreservesTheExactException() {
        BossTaskPausedException verification = BossTaskPausedException.verificationRequired();
        AtomicBoolean challenged = new AtomicBoolean();
        doAnswer(invocation -> { challenged.set(true); return null; }).when(page).waitForTimeout(anyDouble());

        assertSame(verification, assertThrows(BossTaskPausedException.class,
                () -> movement.moveTo(page, target, () -> {
                    if (challenged.get()) throw verification;
                }, 5000, 5000)));

        assertEquals(1, points.size());
        verify(page, times(1)).waitForTimeout(anyDouble());
    }

    @Test
    void guardFailureBeforeStartingDoesNotScrollOrMove() {
        IllegalStateException changedJob = new IllegalStateException("identity changed");

        assertSame(changedJob, assertThrows(IllegalStateException.class,
                () -> movement.moveTo(page, target, () -> { throw changedJob; }, 450, 1100)));

        verifyNoInteractions(target, page, mouse);
    }

    @Test
    void threadInterruptionStopsBeforeMovementAndRetainsTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> movement.moveTo(page, target, () -> {}, 450, 1100));
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(target, page, mouse);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void operationalFailureIsSanitizedAndDoesNotFallBackToInstantClick() {
        doThrow(new IllegalStateException("private URL token"))
                .when(mouse).move(anyDouble(), anyDouble());

        var failure = assertThrows(BossTaskPausedException.class,
                () -> movement.moveTo(page, target, () -> {}, 450, 1100));

        assertFalse(failure.getMessage().contains("private"));
        verify(target, never()).click();
    }

    private static BoundingBox box(double x, double y, double width, double height) {
        BoundingBox bounds = new BoundingBox();
        bounds.x = x;
        bounds.y = y;
        bounds.width = width;
        bounds.height = height;
        return bounds;
    }
}
