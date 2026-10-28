package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Geometry, seeded paths and action boundaries only; no actual browser, waits or external messages. */
class BossMouseVariableTargetTest {
    private static final Runnable ALLOWED = () -> { };

    @Test
    void differentSeedsVaryLandingPointsAndPathsInsideTheInsetTargetWithoutChangingTheWaitBudget() {
        Set<BossMouseMovement.Point> destinations = new HashSet<>();
        Set<List<BossMouseMovement.Point>> paths = new HashSet<>();
        for (int seed = 0; seed < 12; seed++) {
            Fixture f = new Fixture(seed);

            BossMouseMovement.PreparedClick prepared = f.prepare();

            destinations.add(prepared.destination());
            paths.add(List.copyOf(f.points));
            assertInside(prepared.destination(), 425, 475, 210, 230);
            assertEquals(prepared.destination(), f.points.getLast());
            assertEquals(prepared.stepCount(), f.points.size());
            assertTrue(f.points.size() >= 12 && f.points.size() <= 48);
            assertTrue(prepared.plannedDurationMs() >= 450 && prepared.plannedDurationMs() <= 1100);
            assertEquals(prepared.plannedDurationMs(), f.waits.stream().mapToDouble(Double::doubleValue).sum());
            assertTrue(f.waits.stream().allMatch(delay -> delay > 0 && delay <= 50));
            assertTrue(f.points.stream().allMatch(p -> p.x() >= 0 && p.x() < 1280
                    && p.y() >= 0 && p.y() < 800));
            f.verifyNoClicks();
        }
        assertTrue(destinations.size() > 8, "seed changes must vary the landing point");
        assertTrue(paths.size() > 8, "seed changes must vary the path");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameSeedReproducesLandingPathAndWaitsForEitherPathMode(boolean variation) {
        Fixture first = new Fixture(37);
        Fixture same = new Fixture(37);

        BossMouseMovement.PreparedClick firstPrepared = first.prepare(0.25, variation);
        BossMouseMovement.PreparedClick samePrepared = same.prepare(0.25, variation);

        assertEquals(firstPrepared.destination(), samePrepared.destination());
        assertEquals(firstPrepared.start(), samePrepared.start());
        assertEquals(firstPrepared.plannedDurationMs(), samePrepared.plannedDurationMs());
        assertEquals(first.points, same.points);
        assertEquals(first.waits, same.waits);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 0.049, 0.451, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidInsetRatiosUseTheDocumentedDefault(double invalidRatio) {
        Fixture invalid = new Fixture(37);
        Fixture defaults = new Fixture(37);

        BossMouseMovement.PreparedClick actual = invalid.prepare(invalidRatio, true);
        BossMouseMovement.PreparedClick expected = defaults.prepare(0.25, true);

        assertEquals(expected.destination(), actual.destination());
        assertEquals(defaults.points, invalid.points);
        assertEquals(defaults.waits, invalid.waits);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.05, 0.45})
    void validInsetBoundaryRatiosStayInsideTheirOwnSafeRegion(double ratio) {
        Fixture f = new Fixture(19);

        BossMouseMovement.PreparedClick prepared = f.prepare(ratio, true);

        assertInside(prepared.destination(), 400 + 100 * ratio, 500 - 100 * ratio,
                200 + 40 * ratio, 240 - 40 * ratio);
    }

    @Test
    void nextMoveStartsAtTheActualPreviousLandingWithoutAnExtraTeleport() {
        Fixture f = new Fixture(37);
        BossMouseMovement.PreparedClick first = f.prepare();
        f.movement.clickOptions(f.page, f.target, first, ALLOWED);
        int initialMoves = f.points.size();
        f.bounds.set(box(800, 500, 180, 50));

        BossMouseMovement.PreparedClick next = f.prepare();

        assertEquals(first.destination(), next.start());
        assertEquals(initialMoves + next.stepCount(), f.points.size());
        assertEquals(next.destination(), f.points.getLast());
        f.verifyNoClicks();
    }

    @Test
    void anotherPageKeepsAnIndependentPointerOrigin() {
        BossMouseMovement movement = new BossMouseMovement(new Random(37));
        Fixture firstPage = new Fixture(movement);
        Fixture otherPage = new Fixture(movement);
        BossMouseMovement.PreparedClick first = firstPage.prepare();

        BossMouseMovement.PreparedClick other = otherPage.prepare();

        assertEquals(new BossMouseMovement.Point(0, 0), first.start());
        assertEquals(new BossMouseMovement.Point(0, 0), other.start());
        BossMouseMovement.PreparedClick firstAgain = firstPage.prepare();
        assertEquals(first.destination(), firstAgain.start());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 1, 2})
    void clickOptionsUseTheMovedPointRelativeToThePaddingOriginIncludingFractionalBorders(double scale) {
        Fixture f = new Fixture(37);
        f.borderInsets.set(insets(2.8, 3.7, 1.2, 1.9, scale, scale));
        BossMouseMovement.PreparedClick prepared = f.prepare();

        Locator.ClickOptions options = f.movement.clickOptions(f.page, f.target, prepared, ALLOWED);

        assertNotNull(options.position);
        assertEquals(prepared.destination().x(), 400 + Math.floor(2.8) + options.position.x, 0.000001);
        assertEquals(prepared.destination().y(), 200 + Math.floor(3.7) + options.position.y, 0.000001);
        assertEquals(prepared.destination(), f.points.getLast());
        f.verifyNoClicks();
    }

    @Test
    void preparedClickCanProduceOptionsOnlyOnce() {
        Fixture f = new Fixture(37);
        BossMouseMovement.PreparedClick prepared = f.prepare();

        assertNotNull(f.movement.clickOptions(f.page, f.target, prepared, ALLOWED));
        assertThrows(BossTaskPausedException.class,
                () -> f.movement.clickOptions(f.page, f.target, prepared, ALLOWED));

        f.verifyNoClicks();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparedClickCannotBeUsedForAnotherPageOrLocator(boolean anotherPage) {
        Fixture f = new Fixture(37);
        BossMouseMovement.PreparedClick prepared = f.prepare();
        Page page = anotherPage ? mock(Page.class) : f.page;
        Locator target = anotherPage ? f.target : mock(Locator.class);

        assertThrows(BossTaskPausedException.class,
                () -> f.movement.clickOptions(page, target, prepared, ALLOWED));

        if (anotherPage) verifyNoInteractions(page);
        else verifyNoInteractions(target);
        f.verifyNoClicks();
    }

    @Test
    void movingAgainMakesTheOldLandingTokenUnusableInsteadOfClickingAtAnotherPoint() {
        Fixture f = new Fixture(37);
        BossMouseMovement.PreparedClick stale = f.prepare();
        BossMouseMovement.PreparedClick current = f.prepare();
        assertNotEquals(stale.destination(), current.destination());
        int completedMoves = f.points.size();

        assertThrows(BossTaskPausedException.class,
                () -> f.movement.clickOptions(f.page, f.target, stale, ALLOWED));

        assertEquals(completedMoves, f.points.size());
        f.verifyNoClicks();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void layoutMoveOrShrinkAfterPreparationRejectsThePointWithoutReplanning(boolean shrink) {
        Fixture f = new Fixture(37);
        BossMouseMovement.PreparedClick prepared = f.prepare();
        int completedMoves = f.points.size();
        if (shrink) {
            // Same center, but the chosen point is no longer safely inside the shrunken target.
            double dx = Math.abs(prepared.destination().x() - 450);
            double dy = Math.abs(prepared.destination().y() - 220);
            assertTrue(dx > 0.00001 || dy > 0.00001);
            double width = Math.max(0.00001, dx);
            double height = Math.max(0.00001, dy);
            f.bounds.set(box(450 - width / 2, 220 - height / 2, width, height));
        } else {
            f.bounds.set(box(700, 200, 100, 40));
        }

        assertThrows(BossTaskPausedException.class,
                () -> f.movement.clickOptions(f.page, f.target, prepared, ALLOWED));

        assertEquals(completedMoves, f.points.size());
        f.bounds.set(box(400, 200, 100, 40));
        assertThrows(BossTaskPausedException.class,
                () -> f.movement.clickOptions(f.page, f.target, prepared, ALLOWED),
                "failed layout validation must not leave a reusable click token");
        f.verifyNoClicks();
    }

    @Test
    void viewportShrinkingPastThePreparedPointRejectsItWithoutMovingAgain() {
        Fixture f = new Fixture(37);
        BossMouseMovement.PreparedClick prepared = f.prepare();
        int completedMoves = f.points.size();
        f.viewport.set(new ViewportSize(300, 150));

        assertThrows(BossTaskPausedException.class,
                () -> f.movement.clickOptions(f.page, f.target, prepared, ALLOWED));

        assertEquals(completedMoves, f.points.size());
        f.verifyNoClicks();
    }

    @Test
    void partiallyVisibleTargetUsesOnlyTheVisibleIntersectionOfItsInsetArea() {
        Fixture f = new Fixture(37);
        f.bounds.set(box(1200, 200, 200, 40));

        BossMouseMovement.PreparedClick prepared = f.prepare();

        assertInside(prepared.destination(), 1250, 1280, 210, 230);
        assertTrue(prepared.destination().x() < 1280);
        assertDoesNotThrow(() -> f.movement.clickOptions(f.page, f.target, prepared, ALLOWED));
    }

    @Test
    void targetWithNoVisibleInsetIntersectionFailsBeforeAnyMovement() {
        Fixture f = new Fixture(37);
        f.bounds.set(box(1270, 200, 200, 40));

        assertThrows(BossTaskPausedException.class, f::prepare);

        assertTrue(f.points.isEmpty());
        f.verifyNoClicks();
    }

    @Test
    void tinyPositiveTargetKeepsTheLandingInsideItsFractionalGeometry() {
        Fixture f = new Fixture(37);
        f.bounds.set(box(400, 200, 0.5, 0.5));

        BossMouseMovement.PreparedClick prepared = f.prepare();

        assertInside(prepared.destination(), 400.125, 400.375, 200.125, 200.375);
        assertDoesNotThrow(() -> f.movement.clickOptions(f.page, f.target, prepared, ALLOWED));
        f.verifyNoClicks();
    }

    @Test
    void invalidBorderMetadataStopsBeforeMovingInsteadOfGuessingAClickPoint() {
        Fixture f = new Fixture(37);
        f.borderInsets.set(insets(0, 0, 0, 0, Double.NaN, 1));

        assertThrows(BossTaskPausedException.class, f::prepare);

        assertTrue(f.points.isEmpty());
        f.verifyNoClicks();
    }

    @Test
    void noSafeCandidateStopsBeforeMovementWithoutTryingAnotherBatch() {
        Fixture f = new Fixture(37);
        when(f.target.evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), any())).thenReturn(-1);

        assertThrows(BossTaskPausedException.class, f::prepare);

        assertTrue(f.points.isEmpty());
        verify(f.target, times(1)).evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), any());
        f.verifyNoClicks();
    }

    @Test
    void landingBecomingCoveredOrInteractiveAfterMovementStopsWithoutChoosingAnotherPoint() {
        Fixture f = new Fixture(37);
        when(f.target.evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), any())).thenReturn(0, -1);

        assertThrows(BossTaskPausedException.class, f::prepare);

        assertTrue(f.points.size() >= 12 && f.points.size() <= 48);
        verify(f.target, times(2)).evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), any());
        verify(f.target, times(1)).scrollIntoViewIfNeeded(any());
        f.verifyNoClicks();
    }

    @Test
    void landingBecomingUnsafeAtTheFinalClickBoundaryRejectsTheSamePointWithoutMovingAgain() {
        Fixture f = new Fixture(37);
        when(f.target.evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), any())).thenReturn(0, 0, -1);
        BossMouseMovement.PreparedClick prepared = f.prepare();
        int completedMoves = f.points.size();

        assertThrows(BossTaskPausedException.class,
                () -> f.movement.clickOptions(f.page, f.target, prepared, ALLOWED));

        assertEquals(completedMoves, f.points.size());
        verify(f.target, times(3)).evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), any());
        verify(f.target, times(1)).evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), argThat(request -> {
            if (!(request instanceof Map<?, ?> values) || !(values.get("points") instanceof List<?> points)) {
                return false;
            }
            return points.size() == 8;
        }));
        verify(f.target, times(2)).evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), argThat(request -> {
            if (!(request instanceof Map<?, ?> values) || !(values.get("points") instanceof List<?> points)) {
                return false;
            }
            return points.equals(List.of(Map.of("x", prepared.destination().x(), "y", prepared.destination().y())));
        }));
        assertThrows(BossTaskPausedException.class,
                () -> f.movement.clickOptions(f.page, f.target, prepared, ALLOWED));
        verify(f.target, times(3)).evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), any());
        f.verifyNoClicks();
    }

    @Test
    void cancellationDuringMovementPreservesTheLastActualPositionForTheNextAttempt() {
        Fixture f = new Fixture(37);
        CancellationException stopped = new CancellationException("test cancelled");
        Runnable guard = () -> { if (f.points.size() >= 3) throw stopped; };

        assertSame(stopped, assertThrows(CancellationException.class,
                () -> f.movement.prepareClick(f.page, f.target, guard, 450, 1100, 0.25, true)));
        assertEquals(3, f.points.size());
        BossMouseMovement.Point lastActual = f.points.getLast();
        f.points.clear();
        BossMouseMovement.PreparedClick next = f.prepare();

        assertEquals(lastActual, next.start());
        f.verifyNoClicks();
    }

    @Test
    void clickOptionsRechecksCancellationBeforeAnyGeometryOrClick() {
        Fixture f = new Fixture(37);
        BossMouseMovement.PreparedClick prepared = f.prepare();
        clearInvocations(f.page, f.target, f.mouse);
        CancellationException stopped = new CancellationException("test cancelled");

        assertSame(stopped, assertThrows(CancellationException.class,
                () -> f.movement.clickOptions(f.page, f.target, prepared, () -> { throw stopped; })));

        verifyNoInteractions(f.page, f.target, f.mouse);
    }

    @Test
    void interruptedPreparationStopsBeforeAnyBrowserOperationAndPreservesTheFlag() {
        Fixture f = new Fixture(37);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, f::prepare);
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(f.page, f.target, f.mouse);
        } finally {
            Thread.interrupted();
        }
    }

    private static void assertInside(BossMouseMovement.Point point, double left, double right,
                                     double top, double bottom) {
        assertTrue(point.x() >= left && point.x() <= right, () -> "x outside safe region: " + point);
        assertTrue(point.y() >= top && point.y() <= bottom, () -> "y outside safe region: " + point);
    }

    private static Map<String, Number> insets(double left, double top, double right, double bottom,
                                              double scaleX, double scaleY) {
        return Map.of("left", left, "top", top, "right", right, "bottom", bottom,
                "scaleX", scaleX, "scaleY", scaleY);
    }

    private static BoundingBox box(double x, double y, double width, double height) {
        BoundingBox box = new BoundingBox();
        box.x = x;
        box.y = y;
        box.width = width;
        box.height = height;
        return box;
    }

    private static final class Fixture {
        final BossMouseMovement movement;
        final Page page = mock(Page.class);
        final Locator target = mock(Locator.class);
        final Mouse mouse = mock(Mouse.class);
        final List<BossMouseMovement.Point> points = new ArrayList<>();
        final List<Double> waits = new ArrayList<>();
        final AtomicReference<BoundingBox> bounds = new AtomicReference<>(box(400, 200, 100, 40));
        final AtomicReference<ViewportSize> viewport = new AtomicReference<>(new ViewportSize(1280, 800));
        final AtomicReference<Map<String, Number>> borderInsets = new AtomicReference<>(insets(0, 0, 0, 0, 1, 1));

        Fixture(int seed) { this(new BossMouseMovement(new Random(seed))); }

        Fixture(BossMouseMovement movement) {
            this.movement = movement;
            when(page.mouse()).thenReturn(mouse);
            when(page.viewportSize()).thenAnswer(ignored -> viewport.get());
            when(target.isVisible()).thenReturn(true);
            when(target.isEnabled()).thenReturn(true);
            when(target.boundingBox()).thenAnswer(ignored -> bounds.get());
            when(target.evaluate(BossMouseMovement.BORDER_INSETS_SCRIPT)).thenAnswer(ignored -> borderInsets.get());
            when(target.evaluate(eq(BossMouseMovement.LANDING_POINTS_SCRIPT), any())).thenReturn(0);
            doAnswer(call -> {
                points.add(new BossMouseMovement.Point(call.getArgument(0), call.getArgument(1)));
                return null;
            }).when(mouse).move(anyDouble(), anyDouble());
            doAnswer(call -> { waits.add(call.getArgument(0)); return null; })
                    .when(page).waitForTimeout(anyDouble());
        }

        BossMouseMovement.PreparedClick prepare() { return prepare(0.25, true); }

        BossMouseMovement.PreparedClick prepare(double ratio, boolean variation) {
            return movement.prepareClick(page, target, ALLOWED, 450, 1100, ratio, variation);
        }

        void verifyNoClicks() {
            verify(target, never()).click();
            verify(target, never()).click(any(Locator.ClickOptions.class));
            verify(mouse, never()).click(anyDouble(), anyDouble());
        }
    }
}
