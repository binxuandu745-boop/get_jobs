package com.getjobs.worker.liepin;

import com.getjobs.application.entity.LiepinEntity;
import com.getjobs.application.service.LiepinService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.getjobs.worker.liepin.Locators.CHAT_CLOSE;
import static com.getjobs.worker.liepin.Locators.CHAT_HEADER;
import static com.getjobs.worker.liepin.Locators.JOB_CARDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The real card loop with mocked page, persistence and pacing; no browser, AI or messages. */
class LiepinExecutionPolicyTest {
    private static final String PROCESS_JOB = "处理符合条件岗位";
    private static final String START_CHAT = "发起沟通";
    private static final String CHAT_ID_SELECTOR = "[data-tlg-elem-id='c_pc_search_job_listcard_chat_btn']";
    private static final String CHAT_BUTTON_SELECTOR = "button.ant-btn.ant-btn-primary.ant-btn-round";

    private Liepin liepin;
    private Page page;
    private LiepinService service;
    private LiepinActionPacer pacer;
    private LiepinCommunicationObserver communicationObserver;
    private Locator cards;
    private Locator empty;
    private Locator chatClose;
    private List<LiepinEntity> snapshots;
    private final List<Card> visibleCards = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        page = mock(Page.class);
        service = mock(LiepinService.class);
        pacer = mock(LiepinActionPacer.class);
        communicationObserver = mock(LiepinCommunicationObserver.class);
        liepin = new Liepin();
        liepin.setPage(page);
        liepin.setConfig(new LiepinConfig());
        liepin.setShouldStopCallback(() -> false);
        liepin.setProgressCallback((message, current, total) -> { });
        ReflectionTestUtils.setField(liepin, "liepinService", service);
        ReflectionTestUtils.setField(liepin, "actionPacer", pacer);
        ReflectionTestUtils.setField(liepin, "communicationObserver", communicationObserver);
        snapshots = (List<LiepinEntity>) ReflectionTestUtils.getField(liepin, "lastApiEntities");
        when(service.reserveCommunication(anyLong())).thenAnswer(invocation -> {
            events.add("reserve-communication");
            setPersistedDelivery(invocation.getArgument(0), 2);
            return true;
        });
        when(service.confirmCommunication(anyLong())).thenAnswer(invocation -> {
            events.add("confirm-communication");
            setPersistedDelivery(invocation.getArgument(0), 1);
            return true;
        });
        doAnswer(invocation -> {
            events.add("observe-established");
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(communicationObserver).awaitEstablished(eq(page), anyLong(), any(Runnable.class));

        empty = mock(Locator.class);
        when(empty.first()).thenReturn(empty);
        when(empty.nth(anyInt())).thenReturn(empty);
        when(page.locator(anyString())).thenReturn(empty);
        when(page.isClosed()).thenReturn(false);
        when(page.url()).thenReturn("https://www.liepin.com/zhaopin/?currentPage=0&key=Java");
        Locator verification = mock(Locator.class);
        when(verification.first()).thenReturn(verification);
        when(verification.isVisible()).thenReturn(false);
        when(page.locator(LiepinPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);

        cards = mock(Locator.class);
        when(page.locator(JOB_CARDS)).thenReturn(cards);
        when(cards.count()).thenAnswer(ignored -> visibleCards.size());
        when(cards.nth(anyInt())).thenAnswer(invocation -> visibleCards.get(invocation.getArgument(0)).card());
        chatClose = mock(Locator.class);
        when(page.locator(CHAT_CLOSE)).thenReturn(chatClose);
        doAnswer(ignored -> {
            events.add("wait-chat-header");
            return null;
        }).when(page).waitForSelector(eq(CHAT_HEADER), any(Page.WaitForSelectorOptions.class));
        doAnswer(invocation -> {
            events.add(invocation.getArgument(1));
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(pacer).pauseBeforeAction(eq(page), anyString(), any(Runnable.class));
        doAnswer(ignored -> {
            events.add("scroll");
            return null;
        }).when(page).evaluate(anyString(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedOrIncompleteJobs")
    void rejectedOrIncompleteJobsSkipEveryAction(String caseName, String title, String salary,
                                                boolean hasMatchingSnapshot) {
        Card card = addCard(101L);
        if (hasMatchingSnapshot) {
            snapshots.add(snapshot(101L, title, salary));
        } else {
            // A valid snapshot for a different ID must not be used as an index-based fallback.
            snapshots.add(snapshot(999L, "Java开发工程师", "6-8K"));
        }

        assertDoesNotThrow(this::submitJobs);

        assertTrue(events.isEmpty(), caseName);
        verifyNoInteractions(pacer, service);
        verifyNoCardActions(card);
        verifyNoPageActions();
        assertTrue(results().isEmpty());
    }

    static Stream<Arguments> rejectedOrIncompleteJobs() {
        return Stream.of(
                Arguments.of("different role", "前端开发工程师", "6-8K", true),
                Arguments.of("salary lower bound below 4500", "Java开发工程师", "4-8K", true),
                Arguments.of("missing title", null, "6-8K", true),
                Arguments.of("blank title", " ", "6-8K", true),
                Arguments.of("missing salary", "Java开发工程师", null, true),
                Arguments.of("blank salary", "Java开发工程师", " ", true),
                Arguments.of("salary unconfirmed", "Java开发工程师", "面议", true),
                Arguments.of("no snapshot for card ID", "Java开发工程师", "6-8K", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Java开发工程师", "后端开发工程师", "服务端研发工程师"})
    void onlyMatchingJobsWaitBeforeProcessingAndBeforeChat(String title) {
        Card card = addCard(101L);
        // There is deliberately no internship marker; 4500 is the default inclusive minimum.
        snapshots.add(snapshot(101L, title, "4.5-6K"));

        assertDoesNotThrow(this::submitJobs);

        assertEquals(List.of(PROCESS_JOB, START_CHAT), events.stream()
                .filter(event -> PROCESS_JOB.equals(event) || START_CHAT.equals(event)).toList());
        assertEventBefore(PROCESS_JOB, "scroll");
        assertEventBefore("scroll", "hover");
        assertEventBefore("read-chat-button", START_CHAT);
        assertEventBefore(START_CHAT, "reserve-communication");
        assertEventBefore("reserve-communication", "click-chat-button");
        assertEventBefore("click-chat-button", "observe-established");
        assertEventBefore("observe-established", "confirm-communication");
        assertEventBefore("confirm-communication", "wait-chat-header");
        verify(pacer).pauseBeforeAction(eq(page), eq(PROCESS_JOB), any(Runnable.class));
        verify(pacer).pauseBeforeAction(eq(page), eq(START_CHAT), any(Runnable.class));
        verify(card.hr()).hover(any(Locator.HoverOptions.class));
        verify(card.chatButton()).click();
        verifyConfirmedCommunication(101L);
        assertEquals(1, snapshots.get(0).getDelivered());
        assertEquals(1, results().size());
        verify(page, never()).context();
    }

    @Test
    void emptyApiSnapshotCacheSkipsAllActions() {
        Card card = addCard(101L);

        assertDoesNotThrow(this::submitJobs);

        verifyNoInteractions(pacer, service);
        verifyNoCardActions(card);
        verifyNoPageActions();
        assertTrue(results().isEmpty());
    }

    @Test
    void dailySalaryWithoutAnExplicitDailyMinimumSkipsAllActions() {
        Card card = addCard(101L);
        snapshots.add(snapshot(101L, "Java实习生", "100-200元/天"));
        ReflectionTestUtils.setField(liepin, "minimumDailySalary", 0);

        assertDoesNotThrow(this::submitJobs);

        verifyNoInteractions(pacer, service);
        verifyNoCardActions(card);
        verifyNoPageActions();
        assertTrue(results().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("jobsAtExplicitDailyMinimum")
    void explicitlyConfiguredDailyMinimumUsesTheDailySalaryLowerBound(String salary, boolean accepted) {
        Card card = addCard(101L);
        snapshots.add(snapshot(101L, "Java实习生", salary));
        ReflectionTestUtils.setField(liepin, "minimumDailySalary", 125);

        assertDoesNotThrow(this::submitJobs);

        if (accepted) {
            verify(pacer).pauseBeforeAction(eq(page), eq(PROCESS_JOB), any(Runnable.class));
            verify(pacer).pauseBeforeAction(eq(page), eq(START_CHAT), any(Runnable.class));
            verify(card.chatButton()).click();
            verifyConfirmedCommunication(101L);
            assertEquals(1, results().size());
        } else {
            verifyNoInteractions(pacer, service);
            verifyNoCardActions(card);
            verifyNoPageActions();
            assertTrue(results().isEmpty());
        }
    }

    static Stream<Arguments> jobsAtExplicitDailyMinimum() {
        return Stream.of(Arguments.of("100-200元/天", false), Arguments.of("125-200元/天", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"150元/天", "300-400元/天"})
    void defaultDailyMinimumAcceptsQualifiedDailyOffersOnlyAfterConfirmation(String salary) {
        Card card = addCard(101L);
        snapshots.add(snapshot(101L, "Java实习生", salary));

        assertDoesNotThrow(this::submitJobs);

        verify(card.chatButton(), times(1)).click();
        verify(card.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verifyConfirmedCommunication(101L);
        assertEventBefore("reserve-communication", "click-chat-button");
        assertEventBefore("observe-established", "confirm-communication");
        assertEquals(1, snapshots.get(0).getDelivered());
        assertEquals(1, results().size());
    }

    @Test
    void reorderedApiSnapshotsAreMatchedByJobIdWithoutCrossingJobs() {
        Card lowerPaid = addCard(101L);
        Card accepted = addCard(202L);
        // API order is the reverse of visible card order.
        snapshots.add(snapshot(202L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(101L, "Java开发工程师", "4-8K"));

        assertDoesNotThrow(this::submitJobs);

        verifyNoCardActions(lowerPaid);
        verify(accepted.hr()).hover(any(Locator.HoverOptions.class));
        verify(accepted.chatButton()).click();
        verify(pacer, times(1)).pauseBeforeAction(eq(page), eq(PROCESS_JOB), any(Runnable.class));
        verify(pacer, times(1)).pauseBeforeAction(eq(page), eq(START_CHAT), any(Runnable.class));
        verify(service).reserveCommunication(202L);
        verify(service).confirmCommunication(202L);
        verify(service, never()).reserveCommunication(101L);
        verify(service, never()).confirmCommunication(101L);
        verify(service, never()).markDelivered(anyLong());
        verifyNoMoreInteractions(service);
        assertEquals(1, results().size());
        assertTrue(results().get(0).contains("company-202"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void alreadyDeliveredOrUnknownSnapshotSkipsTheWholeActionFlow(int delivered) {
        Card card = addCard(101L);
        LiepinEntity existing = snapshot(101L, "Java开发工程师", "6-8K");
        existing.setDelivered(delivered);
        snapshots.add(existing);

        assertDoesNotThrow(this::submitJobs);

        verifyNoInteractions(pacer, service, communicationObserver);
        verifyNoCardActions(card);
        verifyNoPageActions();
        assertTrue(results().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.liepin.com/job/202.shtml", "/job/202.shtml?from=search",
            "/job/202.html"})
    void jobLinkSuppliesTheIdWhenTrackingAttributesAreMissing(String href) {
        Locator card = mock(Locator.class);
        when(card.locator(anyString())).thenReturn(empty);
        Locator link = mock(Locator.class);
        when(card.locator("a[href*='/job/']")).thenReturn(link);
        when(link.count()).thenReturn(1);
        when(link.first()).thenReturn(link);
        when(link.getAttribute("href")).thenReturn(href);

        Long jobId = ReflectionTestUtils.invokeMethod(liepin, "extractJobIdFromCard", card);

        assertEquals(202L, jobId);
        verifyNoInteractions(pacer, service);
        verifyNoPageActions();
    }

    @Test
    void reservationRejectedByPersistencePreventsAnyCommunicationClick() {
        Card card = addCard(101L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        doReturn(false).when(service).reserveCommunication(101L);

        assertDoesNotThrow(this::submitJobs);

        verify(service).reserveCommunication(101L);
        verify(service, never()).confirmCommunication(anyLong());
        verify(service, never()).markDelivered(anyLong());
        verifyNoMoreInteractions(service);
        verifyNoInteractions(communicationObserver);
        verify(card.chatButton(), never()).click();
        verify(card.chatButton(), never()).click(any(Locator.ClickOptions.class));
        assertTrue(results().isEmpty());
    }

    @Test
    void reservationPersistenceFailurePausesBeforeClickAndBeforeLaterCards() {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        doThrow(new IllegalStateException("record unavailable"))
                .when(service).reserveCommunication(101L);

        LiepinTaskPausedException failure = assertThrows(LiepinTaskPausedException.class, this::submitJobs);

        assertEquals(LiepinTaskPausedException.Reason.RECORD_STATE_UNAVAILABLE, failure.getReason());
        verify(service).reserveCommunication(101L);
        verify(service, never()).confirmCommunication(anyLong());
        verify(service, never()).markDelivered(anyLong());
        verifyNoMoreInteractions(service);
        verifyNoInteractions(communicationObserver);
        verify(first.chatButton(), never()).click();
        verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verify(cards, never()).nth(1);
        verifyNoCardActions(later);
        assertTrue(results().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "timeout"})
    void unconfirmedObservationPausesAfterOneClickAndRetainsUnknownState(String failureKind) {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        RuntimeException observationFailure = "unknown".equals(failureKind)
                ? LiepinTaskPausedException.communicationUnconfirmed(101L, "observation", null)
                : new TimeoutError("communication observation timed out");
        doThrow(observationFailure).when(communicationObserver)
                .awaitEstablished(eq(page), eq(101L), any(Runnable.class));

        LiepinTaskPausedException failure = assertThrows(LiepinTaskPausedException.class, this::submitJobs);

        assertEquals(LiepinTaskPausedException.Reason.COMMUNICATION_UNCONFIRMED, failure.getReason());
        if ("unknown".equals(failureKind)) assertSame(observationFailure, failure);
        verify(first.chatButton(), times(1)).click();
        verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verify(service).reserveCommunication(101L);
        verify(communicationObserver).awaitEstablished(eq(page), eq(101L), any(Runnable.class));
        verify(service, never()).confirmCommunication(anyLong());
        verify(service, never()).markDelivered(anyLong());
        verifyNoMoreInteractions(service, communicationObserver);
        verify(page, never()).waitForSelector(eq(CHAT_HEADER), any(Page.WaitForSelectorOptions.class));
        verify(cards, never()).nth(1);
        verifyNoCardActions(later);
        assertEquals(2, snapshots.get(0).getDelivered());
        assertTrue(results().isEmpty());
    }

    @Test
    void clickTimeoutPausesWithoutRetryingAndRetainsUnknownState() {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        doThrow(new TimeoutError("click timed out after dispatch"))
                .when(first.chatButton()).click();

        LiepinTaskPausedException failure = assertThrows(LiepinTaskPausedException.class, this::submitJobs);

        assertEquals(LiepinTaskPausedException.Reason.COMMUNICATION_UNCONFIRMED, failure.getReason());
        verify(first.chatButton(), times(1)).click();
        verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verify(service).reserveCommunication(101L);
        verify(service, never()).confirmCommunication(anyLong());
        verify(service, never()).markDelivered(anyLong());
        verifyNoMoreInteractions(service);
        verifyNoInteractions(communicationObserver);
        verify(cards, never()).nth(1);
        verifyNoCardActions(later);
        assertEquals(2, snapshots.get(0).getDelivered());
        assertTrue(results().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rejected", "exception"})
    void failedConfirmationPersistencePausesWithoutCountingOrContinuing(String failureKind) {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        if ("rejected".equals(failureKind)) {
            doReturn(false).when(service).confirmCommunication(101L);
        } else {
            doThrow(new IllegalStateException("record unavailable"))
                    .when(service).confirmCommunication(101L);
        }

        LiepinTaskPausedException failure = assertThrows(LiepinTaskPausedException.class, this::submitJobs);

        assertEquals(LiepinTaskPausedException.Reason.RECORD_STATE_UNAVAILABLE, failure.getReason());
        verify(first.chatButton(), times(1)).click();
        verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verify(service).reserveCommunication(101L);
        verify(communicationObserver).awaitEstablished(eq(page), eq(101L), any(Runnable.class));
        verify(service).confirmCommunication(101L);
        verify(service, never()).markDelivered(anyLong());
        verifyNoMoreInteractions(service, communicationObserver);
        verify(page, never()).waitForSelector(eq(CHAT_HEADER), any(Page.WaitForSelectorOptions.class));
        verify(cards, never()).nth(1);
        verifyNoCardActions(later);
        assertEquals(2, snapshots.get(0).getDelivered());
        assertTrue(results().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "close"})
    void confirmedCommunicationSurvivesOrdinaryCleanupTimeoutWithoutRepeating(String cleanupStage) {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        LiepinEntity alreadyUnknown = snapshot(202L, "后端开发工程师", "6-8K");
        alreadyUnknown.setDelivered(2);
        snapshots.add(alreadyUnknown);
        injectFailure("header".equals(cleanupStage) ? FailurePoint.CHAT_WAIT : FailurePoint.CHAT_CLOSE,
                first, new TimeoutError("cleanup timed out"));

        try (MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            assertDoesNotThrow(this::submitJobs);
        }

        verify(first.chatButton(), times(1)).click();
        verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verifyConfirmedCommunication(101L);
        assertEventBefore("confirm-communication", "wait-chat-header");
        verify(cards).nth(1);
        verifyNoCardActions(later);
        assertEquals(1, snapshots.get(0).getDelivered());
        assertEquals(1, results().size());
        if ("close".equals(cleanupStage)) {
            verify(chatClose, times(1)).click(any(Locator.ClickOptions.class));
        }
    }

    @Test
    void cancellationAfterClickPreservesUnknownStateWithoutConfirmationOrLaterCards() {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        AtomicBoolean stopped = new AtomicBoolean();
        liepin.setShouldStopCallback(stopped::get);
        doAnswer(ignored -> {
            events.add("click-chat-button");
            stopped.set(true);
            return null;
        }).when(first.chatButton()).click();

        assertThrows(CancellationException.class, this::submitJobs);

        verify(first.chatButton(), times(1)).click();
        verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verify(service).reserveCommunication(101L);
        verify(service, never()).confirmCommunication(anyLong());
        verify(service, never()).markDelivered(anyLong());
        verifyNoMoreInteractions(service);
        verify(cards, never()).nth(1);
        verifyNoCardActions(later);
        assertEquals(2, snapshots.get(0).getDelivered());
        assertTrue(results().isEmpty());
    }

    @Test
    void existingConversationIsObservedAndConfirmedWithoutSendingOrAddingCount() {
        Card card = addCard(101L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        doReturn("继续聊").when(card.chatButton()).textContent();

        assertDoesNotThrow(this::submitJobs);

        verify(card.chatButton(), never()).click();
        verify(card.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verify(pacer, never()).pauseBeforeAction(eq(page), eq(START_CHAT), any(Runnable.class));
        verifyConfirmedCommunication(101L);
        assertEventBefore("observe-established", "reserve-communication");
        assertEventBefore("reserve-communication", "confirm-communication");
        assertEquals(1, snapshots.get(0).getDelivered());
        assertTrue(results().isEmpty());
    }

    @Test
    void unconfirmedExistingConversationPausesWithoutSendingOrUpdatingPersistence() {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        doReturn("继续聊").when(first.chatButton()).textContent();
        LiepinTaskPausedException unconfirmed = LiepinTaskPausedException.communicationUnconfirmed(
                101L, "existing conversation observation", null);
        doThrow(unconfirmed).when(communicationObserver)
                .awaitEstablished(eq(page), eq(101L), any(Runnable.class));

        assertSame(unconfirmed, assertThrows(LiepinTaskPausedException.class, this::submitJobs));

        verify(first.chatButton(), never()).click();
        verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verify(communicationObserver).awaitEstablished(eq(page), eq(101L), any(Runnable.class));
        verifyNoInteractions(service);
        verify(cards, never()).nth(1);
        verifyNoCardActions(later);
        assertTrue(results().isEmpty());
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("nestedFailures")
    void nestedCatchesPropagatePausesAndCancellationWithoutRepeatingOrContinuing(
            FailurePoint point, String failureKind) {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        RuntimeException failure = "pause".equals(failureKind)
                ? LiepinTaskPausedException.verificationRequired()
                : new CancellationException("用户取消猎聘任务");
        injectFailure(point, first, failure);

        try (MockedStatic<PlaywrightUtil> sleeps = mockStatic(PlaywrightUtil.class)) {
            RuntimeException propagated = assertThrows(failure.getClass(), this::submitJobs);
            assertSame(failure, propagated);
        }

        verify(service, never()).markDelivered(anyLong());
        verify(cards, never()).nth(1);
        verifyNoCardActions(later);
        if (point == FailurePoint.CHAT_WAIT || point == FailurePoint.CHAT_CLOSE) {
            verifyConfirmedCommunication(101L);
            assertEquals(1, results().size());
            assertEquals(1, snapshots.get(0).getDelivered());
            assertEventBefore("confirm-communication", "wait-chat-header");
        } else {
            verify(service, never()).confirmCommunication(anyLong());
            assertTrue(results().isEmpty());
        }
        if (point == FailurePoint.COMMUNICATION_OBSERVATION
                || point == FailurePoint.CHAT_WAIT || point == FailurePoint.CHAT_CLOSE) {
            verify(first.chatButton(), times(1)).click();
            verify(service).reserveCommunication(101L);
            if (point == FailurePoint.COMMUNICATION_OBSERVATION) {
                assertEquals(2, snapshots.get(0).getDelivered());
            }
        } else {
            verify(first.chatButton(), never()).click();
            verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        }
        if (point == FailurePoint.SCROLL) {
            verify(page, never()).evaluate(anyString());
        }
        if (point == FailurePoint.HOVER) {
            verify(first.hr(), times(1)).hover(any(Locator.HoverOptions.class));
        }
        verify(page, never()).context();
    }

    static Stream<Arguments> nestedFailures() {
        return Stream.of(FailurePoint.values()).flatMap(point -> Stream.of(
                Arguments.of(point, "pause"), Arguments.of(point, "cancel")));
    }

    @ParameterizedTest
    @ValueSource(strings = {PROCESS_JOB, START_CHAT})
    void userCancellationDuringActionWaitPreventsCommunicationAndLaterCards(String cancelledStage) {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        AtomicBoolean stopped = new AtomicBoolean();
        liepin.setShouldStopCallback(stopped::get);
        doAnswer(invocation -> {
            if (cancelledStage.equals(invocation.getArgument(1))) stopped.set(true);
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(pacer).pauseBeforeAction(eq(page), anyString(), any(Runnable.class));

        assertThrows(CancellationException.class, this::submitJobs);

        verify(first.chatButton(), never()).click();
        verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verify(service, never()).markDelivered(anyLong());
        verify(service, never()).reserveCommunication(anyLong());
        verify(service, never()).confirmCommunication(anyLong());
        verify(cards, never()).nth(1);
        verifyNoCardActions(later);
        assertTrue(results().isEmpty());
        if (PROCESS_JOB.equals(cancelledStage)) verifyNoPageActions();
    }

    @ParameterizedTest
    @ValueSource(strings = {PROCESS_JOB, START_CHAT})
    void cardIdChangingDuringEitherActionWaitSkipsCommunication(String changedStage) {
        Card card = addCard(101L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        // Both jobs pass the filter; even a valid replacement must not reuse the original action.
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        AtomicLong currentJobId = new AtomicLong(101L);
        when(card.idData().getAttribute("data-tlg-ext"))
                .thenAnswer(ignored -> encodedJobId(currentJobId.get()));
        doAnswer(invocation -> {
            String stage = invocation.getArgument(1);
            events.add(stage);
            if (changedStage.equals(stage)) currentJobId.set(202L);
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(pacer).pauseBeforeAction(eq(page), anyString(), any(Runnable.class));

        assertDoesNotThrow(this::submitJobs);

        verify(pacer).pauseBeforeAction(eq(page), eq(PROCESS_JOB), any(Runnable.class));
        verify(card.chatButton(), never()).click();
        verify(card.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verifyNoInteractions(service);
        assertTrue(results().isEmpty());
        if (PROCESS_JOB.equals(changedStage)) {
            verifyNoCardActions(card);
            verifyNoPageActions();
            verify(pacer, never()).pauseBeforeAction(eq(page), eq(START_CHAT), any(Runnable.class));
        } else {
            verify(pacer).pauseBeforeAction(eq(page), eq(START_CHAT), any(Runnable.class));
            verify(card.hr()).hover(any(Locator.HoverOptions.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop callback", "thread interrupt"})
    void cancellationAtCardLoopEntrySkipsAllCardsAndPreservesInterrupt(String cancellationSource) {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        boolean interrupted = "thread interrupt".equals(cancellationSource);
        if (interrupted) {
            Thread.currentThread().interrupt();
        } else {
            liepin.setShouldStopCallback(() -> true);
        }

        try {
            assertThrows(CancellationException.class, this::submitJobs);
            if (interrupted) assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Do not leave this deliberately interrupted JUnit worker in a cancelled state.
            Thread.interrupted();
        }

        verify(cards, never()).nth(anyInt());
        verifyNoCardActions(first);
        verifyNoCardActions(later);
        verifyNoPageActions();
        verifyNoInteractions(pacer, service);
        assertTrue(results().isEmpty());
    }

    @Test
    void interruptionAfterMouseMoveCancelsWithoutSendingAndRestoresTheInterruptFlag() {
        Card first = addCard(101L);
        Card later = addCard(202L);
        snapshots.add(snapshot(101L, "Java开发工程师", "6-8K"));
        snapshots.add(snapshot(202L, "后端开发工程师", "6-8K"));
        BoundingBox bounds = new BoundingBox();
        bounds.x = 10;
        bounds.y = 20;
        bounds.width = 30;
        bounds.height = 40;
        when(first.chatButton().boundingBox()).thenReturn(bounds);
        Mouse mouse = mock(Mouse.class);
        when(page.mouse()).thenReturn(mouse);
        doAnswer(ignored -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(mouse).move(anyDouble(), anyDouble());

        try {
            assertThrows(CancellationException.class, this::submitJobs);
            // Thread.sleep clears the flag when it throws; the production catch must restore it.
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        verify(mouse, times(1)).move(anyDouble(), anyDouble());
        verify(first.chatButton(), never()).click();
        verify(first.chatButton(), never()).click(any(Locator.ClickOptions.class));
        verify(pacer, never()).pauseBeforeAction(eq(page), eq(START_CHAT), any(Runnable.class));
        verifyNoInteractions(service);
        verify(cards, never()).nth(1);
        verifyNoCardActions(later);
        assertTrue(results().isEmpty());
    }

    private void injectFailure(FailurePoint point, Card card, RuntimeException failure) {
        switch (point) {
            case SCROLL -> doThrow(failure).when(page).evaluate(anyString(), any());
            case HOVER -> doThrow(failure).when(card.hr()).hover(any(Locator.HoverOptions.class));
            case BUTTON_READ -> doThrow(failure).when(card.chatButton()).textContent();
            case BUTTON_BOUNDS -> doThrow(failure).when(card.chatButton()).boundingBox();
            case COMMUNICATION_OBSERVATION -> doThrow(failure).when(communicationObserver)
                    .awaitEstablished(eq(page), eq(101L), any(Runnable.class));
            case CHAT_WAIT -> doAnswer(ignored -> {
                events.add("wait-chat-header");
                throw failure;
            }).when(page).waitForSelector(eq(CHAT_HEADER), any(Page.WaitForSelectorOptions.class));
            case CHAT_CLOSE -> {
                when(chatClose.count()).thenReturn(1);
                doThrow(failure).when(chatClose).click();
                doThrow(failure).when(chatClose).click(any(Locator.ClickOptions.class));
            }
        }
    }

    private Card addCard(long jobId) {
        Locator card = mock(Locator.class);
        when(card.locator(anyString())).thenReturn(empty);
        Locator idData = mock(Locator.class);
        when(card.locator(CHAT_ID_SELECTOR)).thenReturn(idData);
        when(idData.count()).thenReturn(1);
        when(idData.first()).thenReturn(idData);
        when(idData.getAttribute("data-tlg-ext")).thenReturn(encodedJobId(jobId));
        Locator hr = mock(Locator.class);
        when(card.locator(".recruiter-info-box")).thenReturn(hr);
        when(hr.count()).thenReturn(1);
        when(hr.first()).thenReturn(hr);
        when(hr.isVisible()).thenReturn(true);
        doAnswer(ignored -> {
            events.add("hover");
            return null;
        }).when(hr).hover(any(Locator.HoverOptions.class));
        Locator chatButton = mock(Locator.class);
        when(card.locator(CHAT_BUTTON_SELECTOR)).thenReturn(chatButton);
        when(chatButton.count()).thenReturn(1);
        when(chatButton.nth(0)).thenReturn(chatButton);
        when(chatButton.isVisible()).thenReturn(true);
        when(chatButton.textContent()).thenAnswer(ignored -> {
            events.add("read-chat-button");
            return "聊一聊";
        });
        doAnswer(ignored -> {
            events.add("click-chat-button");
            return null;
        }).when(chatButton).click();
        Card harness = new Card(card, idData, hr, chatButton);
        visibleCards.add(harness);
        return harness;
    }

    private LiepinEntity snapshot(long jobId, String title, String salary) {
        LiepinEntity entity = new LiepinEntity();
        entity.setJobId(jobId);
        entity.setJobTitle(title);
        entity.setJobSalaryText(salary);
        entity.setCompName("company-" + jobId);
        entity.setDelivered(0);
        return entity;
    }

    private void submitJobs() {
        ReflectionTestUtils.invokeMethod(liepin, "submitJob");
    }

    private void setPersistedDelivery(long jobId, int delivered) {
        snapshots.stream().filter(entity -> Long.valueOf(jobId).equals(entity.getJobId()))
                .findFirst().ifPresent(entity -> entity.setDelivered(delivered));
    }

    private void verifyConfirmedCommunication(long jobId) {
        verify(service).reserveCommunication(jobId);
        verify(communicationObserver).awaitEstablished(eq(page), eq(jobId), any(Runnable.class));
        verify(service).confirmCommunication(jobId);
        verify(service, never()).markDelivered(anyLong());
        verifyNoMoreInteractions(service, communicationObserver);
    }

    private String encodedJobId(long jobId) {
        return URLEncoder.encode("{\"jobId\":\"" + jobId + "\"}", StandardCharsets.UTF_8);
    }

    private void assertEventBefore(String earlier, String later) {
        int earlierIndex = events.indexOf(earlier);
        int laterIndex = events.indexOf(later);
        assertTrue(earlierIndex >= 0 && laterIndex > earlierIndex,
                () -> earlier + " must precede " + later + ": " + events);
    }

    @SuppressWarnings("unchecked")
    private List<String> results() {
        return (List<String>) ReflectionTestUtils.getField(liepin, "resultList");
    }

    private void verifyNoCardActions(Card card) {
        verify(card.card(), never()).elementHandle();
        verify(card.card(), never()).hover(any(Locator.HoverOptions.class));
        verify(card.hr(), never()).hover(any(Locator.HoverOptions.class));
        verify(card.chatButton(), never()).click();
        verify(card.chatButton(), never()).click(any(Locator.ClickOptions.class));
    }

    private void verifyNoPageActions() {
        verify(page, never()).evaluate(anyString());
        verify(page, never()).evaluate(anyString(), any());
        verify(page, never()).mouse();
        verify(page, never()).context();
    }

    private record Card(Locator card, Locator idData, Locator hr, Locator chatButton) { }

    private enum FailurePoint {
        SCROLL, HOVER, BUTTON_READ, BUTTON_BOUNDS, COMMUNICATION_OBSERVATION, CHAT_WAIT, CHAT_CLOSE
    }
}
