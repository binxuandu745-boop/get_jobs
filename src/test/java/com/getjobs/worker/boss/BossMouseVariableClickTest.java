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

/** No browser, model or messaging: verifies that the prepared endpoint reaches the single click. */
class BossMouseVariableClickTest {
    private Boss boss;
    private Page page;
    private Locator target;
    private Locator verification;
    private BossMouseMovement movement;
    private BossMouseMovement.PreparedClick prepared;
    private Locator.ClickOptions options;
    private AtomicBoolean stopped;

    @BeforeEach
    void setUp() {
        boss = new Boss(mock(BossService.class), mock(AiService.class));
        page = mock(Page.class);
        target = mock(Locator.class);
        verification = mock(Locator.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        boss.setPage(page);
        stopped = new AtomicBoolean();
        boss.setShouldStopCallback(stopped::get);
        movement = mock(BossMouseMovement.class);
        prepared = mock(BossMouseMovement.PreparedClick.class);
        options = new Locator.ClickOptions().setPosition(24, 12);
        ReflectionTestUtils.setField(boss, "mouseMovement", movement);
        ReflectionTestUtils.setField(boss, "mouseMovementEnabled", true);
        ReflectionTestUtils.setField(boss, "mouseTargetRandomizationEnabled", true);
        when(movement.prepareClick(eq(page), eq(target), any(Runnable.class), eq(450), eq(1100),
                eq(0.25), eq(true))).thenReturn(prepared);
        when(movement.clickOptions(eq(page), eq(target), eq(prepared), any(Runnable.class)))
                .thenReturn(options);
    }

    @Test
    void configuredEndpointIsPassedToTheSingleLocatorClick() throws Throwable {
        click();

        var order = inOrder(movement, target);
        order.verify(movement).prepareClick(eq(page), eq(target), any(Runnable.class), eq(450), eq(1100),
                eq(0.25), eq(true));
        order.verify(movement).clickOptions(eq(page), eq(target), eq(prepared), any(Runnable.class));
        order.verify(target).click(same(options));
        verify(target, never()).click();
        verify(movement, never()).moveTo(any(), any(), any(), anyInt(), anyInt());
        verify(verification, times(2)).isVisible();
    }

    @Test
    void configurationValuesReachThePlannerWithoutChangingTheTimeBudget() throws Throwable {
        ReflectionTestUtils.setField(boss, "mouseTargetInsetRatio", 0.35);
        ReflectionTestUtils.setField(boss, "mousePathVariationEnabled", false);
        when(movement.prepareClick(eq(page), eq(target), any(Runnable.class), eq(450), eq(1100),
                eq(0.35), eq(false))).thenReturn(prepared);

        click();

        verify(movement).prepareClick(eq(page), eq(target), any(Runnable.class), eq(450), eq(1100),
                eq(0.35), eq(false));
        verify(target).click(options);
    }

    @Test
    void missingPreparationCannotSilentlyRevertToTheCenter() {
        when(movement.prepareClick(any(), any(), any(), anyInt(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(null);

        assertThrows(BossTaskPausedException.class, this::click);

        verify(target, never()).click();
        verify(target, never()).click(any(Locator.ClickOptions.class));
        verify(movement, never()).clickOptions(any(), any(), any(), any());
    }

    @Test
    void missingPositionCannotSilentlyRevertToTheCenter() {
        when(movement.clickOptions(any(), any(), any(), any())).thenReturn(null);

        assertThrows(BossTaskPausedException.class, this::click);

        verify(target, never()).click();
        verify(target, never()).click(any(Locator.ClickOptions.class));
    }

    @Test
    void fullVerificationAfterEndpointValidationStillPreventsClicking() {
        when(movement.clickOptions(any(), any(), any(), any())).thenAnswer(ignored -> {
            when(verification.isVisible()).thenReturn(true);
            return options;
        });

        BossTaskPausedException failure = assertThrows(BossTaskPausedException.class, this::click);

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(target, never()).click(any(Locator.ClickOptions.class));
    }

    @Test
    void localEndpointGuardStillRespondsToCancellation() {
        when(movement.clickOptions(any(), any(), any(), any())).thenAnswer(call -> {
            stopped.set(true);
            ((Runnable) call.getArgument(3)).run();
            return options;
        });

        assertThrows(CancellationException.class, this::click);

        verify(target, never()).click(any(Locator.ClickOptions.class));
    }

    @Test
    void deferredCardClickRetainsItsOwnPreparedEndpoint() throws Throwable {
        Object result = invoke("prepareMouseClick", new Class<?>[]{Page.class, Locator.class, Runnable.class},
                page, target, (Runnable) () -> { });
        assertSame(prepared, result);
        verify(target, never()).click(any(Locator.ClickOptions.class));

        invoke("finishMouseClick", new Class<?>[]{Page.class, Locator.class, Runnable.class,
                        String.class, BossMouseMovement.PreparedClick.class},
                page, target, (Runnable) () -> { }, "打开岗位卡片", result);

        verify(movement).clickOptions(eq(page), eq(target), same(prepared), any(Runnable.class));
        verify(target, times(1)).click(same(options));
    }

    @Test
    void enabledVariableTargetCannotBeClickedWithoutPreparation() {
        assertThrows(BossTaskPausedException.class, () -> invoke("finishMouseClick",
                new Class<?>[]{Page.class, Locator.class, Runnable.class}, page, target, (Runnable) () -> { }));

        verify(target, never()).click();
        verify(target, never()).click(any(Locator.ClickOptions.class));
    }

    @Test
    void disablingMovementRetainsAnOrdinaryGuardedLocatorClick() throws Throwable {
        ReflectionTestUtils.setField(boss, "mouseMovementEnabled", false);

        click();

        verifyNoInteractions(movement);
        verify(target).click();
        verify(target, never()).click(any(Locator.ClickOptions.class));
    }

    private void click() throws Throwable {
        invoke("clickWithMouse", new Class<?>[]{Page.class, Locator.class}, page, target);
    }

    private Object invoke(String name, Class<?>[] parameters, Object... arguments) throws Throwable {
        Method method = Boss.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        try { return method.invoke(boss, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
