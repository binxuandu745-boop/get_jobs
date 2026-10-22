package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression for the BOSS click boundary; browser motion and clicks remain pure mocks. */
class BossMouseClickTest {
    private Boss boss;
    private Page page;
    private Locator target;
    private BossMouseMovement movement;
    private AtomicBoolean stopped;

    @BeforeEach
    void setUp() {
        boss = new Boss(mock(BossService.class), mock(AiService.class));
        page = mock(Page.class);
        target = mock(Locator.class);
        movement = mock(BossMouseMovement.class);
        stopped = new AtomicBoolean();
        boss.setPage(page);
        boss.setShouldStopCallback(stopped::get);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        Locator verification = mock(Locator.class);
        when(verification.first()).thenReturn(verification);
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        ReflectionTestUtils.setField(boss, "mouseMovement", movement);
        ReflectionTestUtils.setField(boss, "mouseMovementEnabled", true);
        ReflectionTestUtils.setField(boss, "mouseMovementMinMs", 450);
        ReflectionTestUtils.setField(boss, "mouseMovementMaxMs", 1100);
    }

    @Test
    void enabledClickMovesFirstAndClicksOnlyAfterMovementCompletes() throws Throwable {
        Runnable identity = mock(Runnable.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(2)).run();
            return null;
        }).when(movement).moveTo(eq(page), eq(target), any(Runnable.class), eq(450), eq(1100));

        click(identity);

        var order = inOrder(movement, target);
        order.verify(movement).moveTo(eq(page), eq(target), any(Runnable.class), eq(450), eq(1100));
        order.verify(target).click();
        verify(identity, atLeast(2)).run();
        verify(target, times(1)).click();
    }

    @Test
    void twoArgumentOverloadAlsoMovesBeforeClicking() throws Throwable {
        clickWithoutIdentityGuard();

        var order = inOrder(movement, target);
        order.verify(movement).moveTo(eq(page), eq(target), any(Runnable.class), eq(450), eq(1100));
        order.verify(target).click();
    }

    @Test
    void cancellationDuringMovementPreventsClick() {
        doAnswer(call -> {
            stopped.set(true);
            ((Runnable) call.getArgument(2)).run();
            return null;
        }).when(movement).moveTo(eq(page), eq(target), any(Runnable.class), anyInt(), anyInt());

        assertThrows(CancellationException.class, () -> click(() -> { }));

        verify(target, never()).click();
    }

    @Test
    void identityGuardRunsInsideMovementAndBlocksClickWhenJobChanges() {
        AtomicBoolean changed = new AtomicBoolean();
        Runnable identity = () -> {
            if (changed.get()) throw BossTaskPausedException.detailsUnavailable("test job changed");
        };
        doAnswer(call -> {
            changed.set(true);
            ((Runnable) call.getArgument(2)).run();
            return null;
        }).when(movement).moveTo(eq(page), eq(target), any(Runnable.class), anyInt(), anyInt());

        assertThrows(BossTaskPausedException.class, () -> click(identity));

        verify(target, never()).click();
    }

    @Test
    void identityIsRecheckedAfterMovementReturnsBeforeClicking() {
        AtomicBoolean changed = new AtomicBoolean();
        Runnable identity = () -> {
            if (changed.get()) throw BossTaskPausedException.detailsUnavailable("test job changed");
        };
        doAnswer(ignored -> {
            // A helper must not be able to bypass the caller's final identity check.
            changed.set(true);
            return null;
        }).when(movement).moveTo(eq(page), eq(target), any(Runnable.class), anyInt(), anyInt());

        assertThrows(BossTaskPausedException.class, () -> click(identity));

        verify(target, never()).click();
    }

    @Test
    void cancellationIsRecheckedAfterMovementReturnsBeforeClicking() {
        doAnswer(ignored -> {
            stopped.set(true);
            return null;
        }).when(movement).moveTo(eq(page), eq(target), any(Runnable.class), anyInt(), anyInt());

        assertThrows(CancellationException.class, () -> click(() -> { }));

        verify(target, never()).click();
    }

    @Test
    void missingMovementHelperCannotSilentlyFallBackToInstantClick() {
        ReflectionTestUtils.setField(boss, "mouseMovement", null);

        assertThrows(BossTaskPausedException.class, () -> click(() -> { }));

        verify(target, never()).click();
    }

    @Test
    void disabledMovementRetainsOriginalSingleClick() throws Throwable {
        ReflectionTestUtils.setField(boss, "mouseMovementEnabled", false);
        Runnable identity = mock(Runnable.class);

        click(identity);

        verifyNoInteractions(movement);
        verify(target, times(1)).click();
        verify(identity, atLeastOnce()).run();
    }

    @Test
    void existingStopRequestPreventsMovementAndClick() {
        stopped.set(true);

        assertThrows(CancellationException.class, () -> click(() -> { }));

        verifyNoInteractions(movement);
        verify(target, never()).click();
    }

    @Test
    void safetyVerificationPreventsMovementAndClick() {
        when(page.url()).thenReturn("https://www.zhipin.com/web/passport/zp/verify.html");

        BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, () -> click(() -> { }));

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verifyNoInteractions(movement);
        verify(target, never()).click();
    }

    private void click(Runnable identityGuard) throws Throwable {
        invoke("clickWithMouse", new Class<?>[]{Page.class, Locator.class, Runnable.class},
                page, target, identityGuard);
    }

    private void clickWithoutIdentityGuard() throws Throwable {
        invoke("clickWithMouse", new Class<?>[]{Page.class, Locator.class}, page, target);
    }

    private void invoke(String methodName, Class<?>[] parameters, Object... arguments) throws Throwable {
        Method method = Boss.class.getDeclaredMethod(methodName, parameters);
        method.setAccessible(true);
        try {
            method.invoke(boss, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
