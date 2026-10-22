package com.getjobs.worker.liepin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure mock tests: no browser, remote request, real sleep or database access. */
class LiepinCommunicationObserverTest {
    private static final Long JOB_ID = 42L;

    @Test
    void exactVisibleSameJobButtonConfirmsWithoutAnyAction() {
        Fixture fixture = new Fixture();
        Locator card = fixture.card(JOB_ID, " \n继续聊\u3000 ");
        fixture.show(card);

        fixture.observer(1000).awaitEstablished(fixture.page, JOB_ID, () -> { });

        fixture.assertReadOnly();
        verify(fixture.page, never()).waitForTimeout(anyDouble());
        ArgumentCaptor<Locator.GetAttributeOptions> attributes =
                ArgumentCaptor.forClass(Locator.GetAttributeOptions.class);
        verify(card, atLeastOnce()).getAttribute(eq("data-tlg-ext"), attributes.capture());
        assertTrue(attributes.getAllValues().stream().allMatch(options -> options.timeout > 0 && options.timeout < 250));
    }

    @Test
    void nestedEncodedChatIdIsSupported() {
        Fixture fixture = new Fixture();
        Locator card = fixture.card(null, "继续聊");
        Locator nested = mock(Locator.class);
        when(card.locator(LiepinCommunicationObserver.CHAT_ID_SELECTOR)).thenReturn(nested);
        when(nested.count()).thenReturn(1);
        when(nested.first()).thenReturn(nested);
        when(nested.getAttribute(eq("data-tlg-ext"), any(Locator.GetAttributeOptions.class)))
                .thenReturn("%7B%22jobId%22%3A%2242%22%7D");
        fixture.show(card);

        assertDoesNotThrow(() -> fixture.observer(1000).awaitEstablished(fixture.page, JOB_ID, () -> { }));
        fixture.assertReadOnly();
    }

    @Test
    void jobLinkIdIsSupportedWhenDataAttributesAreUnavailable() {
        Fixture fixture = new Fixture();
        Locator card = fixture.card(null, "继续聊");
        Locator links = mock(Locator.class);
        Locator link = mock(Locator.class);
        when(card.locator(LiepinCommunicationObserver.JOB_LINK_SELECTOR)).thenReturn(links);
        when(links.count()).thenReturn(1);
        when(links.nth(0)).thenReturn(link);
        when(link.getAttribute(eq("href"), any(Locator.GetAttributeOptions.class)))
                .thenReturn("https://www.liepin.com/job/42.shtml");
        fixture.show(card);

        assertDoesNotThrow(() -> fixture.observer(1000).awaitEstablished(fixture.page, JOB_ID, () -> { }));
        fixture.assertReadOnly();
    }

    @Test
    void anotherJobWithContinueChatNeverConfirmsTarget() {
        Fixture fixture = new Fixture();
        fixture.show(fixture.card(99L, "继续聊"));

        assertUnconfirmed(fixture.observer(500), fixture);
        fixture.assertReadOnly();
    }

    @ParameterizedTest
    @ValueSource(strings = {"聊一聊", "不支持继续聊", "继续聊（已申请）", "", "继续聊天"})
    void onlyTheCompleteNormalizedContinueChatTextConfirms(String text) {
        Fixture fixture = new Fixture();
        fixture.show(fixture.card(JOB_ID, text));

        assertUnconfirmed(fixture.observer(250), fixture);
        fixture.assertReadOnly();
    }

    @Test
    void hiddenCardOrHiddenButtonCannotConfirm() {
        Fixture fixture = new Fixture();
        Locator hiddenCard = fixture.card(JOB_ID, "继续聊");
        when(hiddenCard.isVisible()).thenReturn(false);
        Locator hiddenButtonCard = fixture.card(JOB_ID, "继续聊");
        when(fixture.buttonOf(hiddenButtonCard).isVisible()).thenReturn(false);
        fixture.show(hiddenCard, hiddenButtonCard);

        assertUnconfirmed(fixture.observer(250), fixture);
    }

    @Test
    void headerAloneDoesNotConfirmAndDefaultBudgetIsFifteenSeconds() {
        Fixture fixture = new Fixture();
        fixture.show();
        Locator header = mock(Locator.class);
        when(fixture.page.locator(Locators.CHAT_HEADER)).thenReturn(header);
        when(header.isVisible()).thenReturn(true);

        assertUnconfirmed(fixture.observer(15_000), fixture);

        assertEquals(TimeUnit.SECONDS.toNanos(15), fixture.now.get());
        verify(fixture.page, times(60)).waitForTimeout(250);
        verify(fixture.page, never()).locator(Locators.CHAT_HEADER);
    }

    @Test
    void nextRoundReacquiresCardsAfterTheListReorders() {
        Fixture fixture = new Fixture();
        Locator oldList = mock(Locator.class);
        Locator newList = mock(Locator.class);
        Locator other = fixture.card(99L, "继续聊");
        Locator target = fixture.card(JOB_ID, "继续聊");
        when(oldList.count()).thenReturn(1);
        when(oldList.nth(0)).thenReturn(other);
        when(newList.count()).thenReturn(1);
        when(newList.nth(0)).thenReturn(target);
        when(fixture.page.locator(Locators.JOB_CARDS)).thenReturn(oldList, newList);

        assertDoesNotThrow(() -> fixture.observer(1000).awaitEstablished(fixture.page, JOB_ID, () -> { }));

        verify(fixture.page, times(2)).locator(Locators.JOB_CARDS);
        verify(fixture.page, times(1)).waitForTimeout(250);
    }

    @Test
    void idChangingWhileReadingButtonDoesNotConfirm() {
        Fixture fixture = new Fixture();
        Locator card = fixture.card(JOB_ID, "继续聊");
        when(card.getAttribute(eq("data-tlg-ext"), any(Locator.GetAttributeOptions.class)))
                .thenReturn("{\"jobId\":42}", "{\"jobId\":99}");
        fixture.show(card);

        assertUnconfirmed(fixture.observer(250), fixture);
    }

    @Test
    void readTimeCountsAgainstTheOverallDeadlineAndReducesFollowingTimeouts() {
        Fixture fixture = new Fixture();
        Locator card = fixture.card(JOB_ID, "聊一聊");
        when(card.getAttribute(eq("data-tlg-ext"), any(Locator.GetAttributeOptions.class)))
                .thenAnswer(invocation -> {
                    fixture.now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
                    return "{\"jobId\":42}";
                });
        fixture.show(card);

        assertUnconfirmed(fixture.observer(250), fixture);

        assertEquals(TimeUnit.MILLISECONDS.toNanos(250), fixture.now.get());
        verify(fixture.page).waitForTimeout(150);
        ArgumentCaptor<Locator.TextContentOptions> texts = ArgumentCaptor.forClass(Locator.TextContentOptions.class);
        verify(fixture.buttonOf(card)).textContent(texts.capture());
        assertEquals(150.0, texts.getValue().timeout.doubleValue());
    }

    @Test
    void aReadThatConsumesTheDeadlineCannotReturnLateConfirmation() {
        Fixture fixture = new Fixture();
        Locator card = fixture.card(JOB_ID, "继续聊");
        when(card.getAttribute(eq("data-tlg-ext"), any(Locator.GetAttributeOptions.class)))
                .thenAnswer(invocation -> {
                    fixture.now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
                    return "{\"jobId\":42}";
                });
        fixture.show(card);

        assertUnconfirmed(fixture.observer(250), fixture);

        verify(fixture.page, never()).waitForTimeout(anyDouble());
        verify(fixture.buttonOf(card), never()).textContent(any(Locator.TextContentOptions.class));
    }

    @Test
    void aMockClockThatNeverAdvancesStillHasABoundedRoundCount() {
        Fixture fixture = new Fixture();
        fixture.show();
        doNothing().when(fixture.page).waitForTimeout(anyDouble());

        assertUnconfirmed(fixture.observer(500), fixture);

        verify(fixture.page, times(2)).locator(Locators.JOB_CARDS);
        verify(fixture.page, times(2)).waitForTimeout(250);
    }

    @Test
    void aSlowEarlierCardDoesNotStarveLaterCardsAcrossRounds() {
        Fixture fixture = new Fixture();
        Locator earlier = fixture.card(99L, "继续聊");
        Locator target = fixture.card(JOB_ID, "继续聊");
        when(earlier.getAttribute(eq("data-tlg-ext"), any(Locator.GetAttributeOptions.class)))
                .thenAnswer(invocation -> {
                    fixture.now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
                    return "{\"jobId\":99}";
                });
        fixture.show(earlier, target);

        assertDoesNotThrow(() -> fixture.observer(1000).awaitEstablished(fixture.page, JOB_ID, () -> { }));

        verify(fixture.page, times(2)).locator(Locators.JOB_CARDS);
        verify(fixture.cards).nth(0);
        verify(fixture.cards).nth(1);
        fixture.assertReadOnly();
    }

    @Test
    void initialCancellationDoesNotReadThePage() {
        Fixture fixture = new Fixture();
        clearInvocations(fixture.page);
        CancellationException cancellation = new CancellationException("stop");

        assertSame(cancellation, assertThrows(CancellationException.class,
                () -> fixture.observer(1000).awaitEstablished(fixture.page, JOB_ID, () -> { throw cancellation; })));

        verifyNoInteractions(fixture.page);
    }

    @Test
    void cancellationAfterAReadFailureIsNotReplacedByUnconfirmed() {
        Fixture fixture = new Fixture();
        AtomicBoolean stopped = new AtomicBoolean();
        when(fixture.page.locator(Locators.JOB_CARDS)).thenAnswer(invocation -> {
            stopped.set(true);
            throw new TimeoutError("page details must not leak");
        });

        assertThrows(CancellationException.class,
                () -> fixture.observer(1000).awaitEstablished(fixture.page, JOB_ID, () -> {
                    if (stopped.get()) throw new CancellationException("stop");
                }));
        verify(fixture.page, never()).waitForTimeout(anyDouble());
    }

    @Test
    void verificationAppearingDuringWaitPropagatesUnchanged() {
        Fixture fixture = new Fixture();
        fixture.show();
        AtomicBoolean verification = new AtomicBoolean();
        LiepinTaskPausedException pause = LiepinTaskPausedException.verificationRequired();
        doAnswer(invocation -> { verification.set(true); return null; })
                .when(fixture.page).waitForTimeout(anyDouble());

        assertSame(pause, assertThrows(LiepinTaskPausedException.class,
                () -> fixture.observer(1000).awaitEstablished(fixture.page, JOB_ID, () -> {
                    if (verification.get()) throw pause;
                })));
        verify(fixture.page, times(1)).waitForTimeout(250);
    }

    @Test
    void interruptDuringWaitIsPreservedAndCancelsFurtherReads() {
        Fixture fixture = new Fixture();
        fixture.show();
        doAnswer(invocation -> { Thread.currentThread().interrupt(); return null; })
                .when(fixture.page).waitForTimeout(anyDouble());
        try {
            assertThrows(CancellationException.class,
                    () -> fixture.observer(1000).awaitEstablished(fixture.page, JOB_ID, () -> { }));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(fixture.page, times(1)).locator(Locators.JOB_CARDS);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unconfirmedFailureDoesNotExposeOriginalReadErrorOrCause() {
        Fixture fixture = new Fixture();
        when(fixture.page.locator(Locators.JOB_CARDS))
                .thenThrow(new TimeoutError("https://example.invalid/?token=secret response body"));

        LiepinTaskPausedException failure = assertUnconfirmed(fixture.observer(500), fixture);

        assertTrue(failure.getMessage().contains("TimeoutError"));
        assertFalse(failure.getMessage().contains("token"));
        assertFalse(failure.getMessage().contains("example.invalid"));
        assertFalse(failure.getMessage().contains("response body"));
        assertNull(failure.getCause());
        fixture.assertReadOnly();
    }

    private static LiepinTaskPausedException assertUnconfirmed(LiepinCommunicationObserver observer,
                                                              Fixture fixture) {
        LiepinTaskPausedException failure = assertThrows(LiepinTaskPausedException.class,
                () -> observer.awaitEstablished(fixture.page, JOB_ID, () -> { }));
        assertEquals("COMMUNICATION_UNCONFIRMED", failure.getReason().name());
        return failure;
    }

    private static final class Fixture {
        private final Page page = mock(Page.class);
        private final Locator cards = mock(Locator.class);
        private final AtomicLong now = new AtomicLong();
        private final java.util.Map<Locator, Locator> buttons = new java.util.IdentityHashMap<>();

        private Fixture() {
            when(page.locator(Locators.JOB_CARDS)).thenReturn(cards);
            doAnswer(invocation -> {
                double millis = invocation.getArgument(0);
                now.addAndGet(Math.round(millis * TimeUnit.MILLISECONDS.toNanos(1)));
                return null;
            }).when(page).waitForTimeout(anyDouble());
        }

        private LiepinCommunicationObserver observer(int timeoutMillis) {
            return new LiepinCommunicationObserver(timeoutMillis, now::get);
        }

        private Locator card(Long jobId, String text) {
            Locator card = mock(Locator.class);
            when(card.isVisible()).thenReturn(true);
            when(card.getAttribute(eq("data-tlg-ext"), any(Locator.GetAttributeOptions.class)))
                    .thenReturn(jobId == null ? null : "{\"jobId\":" + jobId + "}");
            when(card.locator(LiepinCommunicationObserver.CHAT_ID_SELECTOR)).thenReturn(mock(Locator.class));
            when(card.locator(LiepinCommunicationObserver.JOB_LINK_SELECTOR)).thenReturn(mock(Locator.class));
            Locator buttonList = mock(Locator.class);
            Locator button = mock(Locator.class);
            when(card.locator(LiepinCommunicationObserver.BUTTON_SELECTOR)).thenReturn(buttonList);
            when(buttonList.count()).thenReturn(1);
            when(buttonList.nth(0)).thenReturn(button);
            when(button.isVisible()).thenReturn(true);
            when(button.textContent(any(Locator.TextContentOptions.class))).thenReturn(text);
            buttons.put(card, button);
            return card;
        }

        private Locator buttonOf(Locator card) {
            return buttons.get(card);
        }

        private void show(Locator... values) {
            when(cards.count()).thenReturn(values.length);
            for (int i = 0; i < values.length; i++) when(cards.nth(i)).thenReturn(values[i]);
        }

        private void assertReadOnly() {
            verify(page, never()).navigate(anyString());
            verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
            verify(page, never()).reload();
            verify(page, never()).waitForSelector(anyString());
            verify(page, never()).waitForSelector(anyString(), any(Page.WaitForSelectorOptions.class));
            for (Locator button : buttons.values()) {
                verify(button, never()).click();
                verify(button, never()).click(any(Locator.ClickOptions.class));
            }
        }
    }
}
