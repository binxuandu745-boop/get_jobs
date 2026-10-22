package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.Timing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure mock events: never starts a browser, sends HTTP, or waits on real clocks. */
class BossJobDetailSessionTest {
    private static final int TIMEOUT_MS = 60_000;
    private static final String DETAIL_URL = "https://www.zhipin.com/wapi/zpgeek/job/detail.json";
    private Page page;
    private Locator verification;
    private Runnable selection;
    private Runnable checkStopped;
    private AtomicReference<Consumer<Request>> onRequest;
    private AtomicReference<Consumer<Response>> onResponse;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        verification = mock(Locator.class);
        selection = mock(Runnable.class);
        checkStopped = mock(Runnable.class);
        onRequest = new AtomicReference<>();
        onResponse = new AtomicReference<>();
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        doAnswer(invocation -> {
            onRequest.set(invocation.getArgument(0));
            return null;
        }).when(page).onRequest(anyRequestListener());
        doAnswer(invocation -> {
            onResponse.set(invocation.getArgument(0));
            return null;
        }).when(page).onResponse(anyResponseListener());
    }

    @Test
    void defaultFirstResponseIsReusedWithoutAnotherSelectionOrBodyReadInCallback() {
        Response early = response("first");
        try (BossJobDetailSession session = opened()) {
            emit(early);
            verify(early, never()).text();
            var result = session.await("first", selection, checkStopped, TIMEOUT_MS);
            assertEquals("first", id(result));
            verify(selection).run();
            verify(early).text();
            verify(page, never()).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));
            verifyNoNavigationOrDirectRequest();
        }
        verifyRemovedListeners();
    }

    @Test
    void cacheHitStillRunsSelectionBeforeReturningAndDoesNotRereadBody() {
        Response early = response("first");
        try (BossJobDetailSession session = opened()) {
            emit(early);
            var first = session.await("first", () -> { }, checkStopped, TIMEOUT_MS);
            var again = session.await("first", selection, checkStopped, TIMEOUT_MS);
            assertSame(first, again);
            verify(selection).run();
            verify(early, times(1)).text();
        }
    }

    @Test
    void wrongJobIdCannotSatisfySelectionAndCorrectLaterResponseCan() {
        Response wrong = response("other");
        Response expected = response("first");
        try (BossJobDetailSession session = opened()) {
            emit(wrong);
            doAnswer(invocation -> {
                BooleanSupplier condition = invocation.getArgument(0);
                assertFalse(condition.getAsBoolean());
                emit(expected);
                verify(expected, never()).text();
                assertTrue(condition.getAsBoolean());
                return null;
            }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));
            assertEquals("first", id(session.await("first", selection, checkStopped, TIMEOUT_MS)));
            verify(selection, times(1)).run();
            verifyNoNavigationOrDirectRequest();
        }
    }

    @Test
    void responseFromRequestObservedBeforeNewNavigationIsIgnored() {
        Response old = response("first");
        Response current = response("first");
        try (BossJobDetailSession session = opened()) {
            onRequest.get().accept(old.request());
            session.beginNavigation();
            onResponse.get().accept(old);
            doAnswer(invocation -> {
                BooleanSupplier condition = invocation.getArgument(0);
                assertFalse(condition.getAsBoolean());
                emit(current);
                assertTrue(condition.getAsBoolean());
                return null;
            }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));
            assertEquals("first", id(session.await("first", selection, checkStopped, TIMEOUT_MS)));
            verify(old, never()).text();
        }
    }

    @Test
    void navigationInvalidatesAlreadyParsedDetails() {
        Response old = response("first");
        try (BossJobDetailSession session = opened()) {
            emit(old);
            session.await("first", selection, checkStopped, TIMEOUT_MS);
            session.beginNavigation();
            timeoutWithoutResponse();
            assertThrows(BossTaskPausedException.class,
                    () -> session.await("first", selection, checkStopped, TIMEOUT_MS));
            verify(old, times(1)).text();
        }
    }

    @Test
    void oldRequestQueuedAfterNewNavigationIsExcludedUsingStartTime() {
        Response old = response("first");
        Timing timing = new Timing();
        timing.startTime = System.currentTimeMillis() - 60_000;
        when(old.request().timing()).thenReturn(timing);
        try (BossJobDetailSession session = opened()) {
            emit(old);
            timeoutWithoutResponse();
            assertThrows(BossTaskPausedException.class,
                    () -> session.await("first", selection, checkStopped, TIMEOUT_MS));
            verify(old, never()).text();
        }
    }

    @Test
    void responseWithoutObservedRequestAndOtherEndpointAreIgnored() {
        Response unobserved = response("first");
        Response otherEndpoint = response("first");
        when(otherEndpoint.request().url()).thenReturn(DETAIL_URL + "/not-the-endpoint");
        try (BossJobDetailSession session = opened()) {
            onResponse.get().accept(unobserved);
            emit(otherEndpoint);
            timeoutWithoutResponse();
            assertThrows(BossTaskPausedException.class,
                    () -> session.await("first", selection, checkStopped, TIMEOUT_MS));
            verify(unobserved, never()).text();
            verify(otherEndpoint, never()).text();
        }
    }

    @Test
    void boundedPendingBufferDoesNotGrowWithAllPreviouslySeenResponses() {
        Response oldest = response("oldest");
        try (BossJobDetailSession session = opened()) {
            emit(oldest);
            for (int i = 0; i < BossJobDetailSession.MAX_PENDING_RESPONSES; i++) {
                emit(response("job-" + i));
            }
            String newestId = "job-" + (BossJobDetailSession.MAX_PENDING_RESPONSES - 1);
            assertEquals(newestId, id(session.await(newestId, selection, checkStopped, TIMEOUT_MS)));
            verify(oldest, never()).text();
        }
    }

    @Test
    void missingResponseTimesOutWithoutRepeatingSelectionOrClaimingVerification() {
        try (BossJobDetailSession session = opened()) {
            timeoutWithoutResponse();
            var failure = assertThrows(BossTaskPausedException.class,
                    () -> session.await("first", selection, checkStopped, TIMEOUT_MS));
            assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
            verify(selection, times(1)).run();
            verifyNoNavigationOrDirectRequest();
        }
        verifyRemovedListeners();
    }

    @Test
    void cancellationPreventsSelectionAndCloseStillRemovesListeners() {
        doThrow(new CancellationException("cancelled")).when(checkStopped).run();
        assertThrows(CancellationException.class, () -> {
            try (BossJobDetailSession session = opened()) {
                session.await("first", selection, checkStopped, TIMEOUT_MS);
            }
        });
        verifyNoInteractions(selection);
        verifyRemovedListeners();
    }

    @Test
    void safetyVerificationPreventsSelectionAndIsNotDowngraded() {
        when(page.url()).thenReturn("https://www.zhipin.com/web/passport/zp/verify.html");
        var failure = assertThrows(BossTaskPausedException.class, () -> {
            try (BossJobDetailSession session = opened()) {
                session.await("first", selection, checkStopped, TIMEOUT_MS);
            }
        });
        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verifyNoInteractions(selection);
        verifyRemovedListeners();
    }

    @Test
    void closeIsIdempotentAndLateCallbacksDoNotReadBodies() {
        BossJobDetailSession session = opened();
        session.close();
        session.close();
        Response late = response("first");
        emit(late);
        verify(late, never()).text();
        assertThrows(BossTaskPausedException.class,
                () -> session.await("first", selection, checkStopped, TIMEOUT_MS));
        verifyNoInteractions(selection);
        verifyRemovedListeners();
    }

    @Test
    void missingBodyIdentityIsNeverAcceptedAsCurrentJob() {
        Response missing = response("first");
        when(missing.text()).thenReturn("{\"zpData\":{\"jobInfo\":{\"jobName\":\"Java\"}}}");
        try (BossJobDetailSession session = opened()) {
            emit(missing);
            var failure = assertThrows(BossTaskPausedException.class,
                    () -> session.await("first", selection, checkStopped, TIMEOUT_MS));
            assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        }
    }

    @Test
    void pendingResponseOnNavigationIsInvalidatedWithoutReadingIt() {
        Response old = response("first");
        try (BossJobDetailSession session = opened()) {
            emit(old);
            session.beginNavigation();
            timeoutWithoutResponse();
            assertThrows(BossTaskPausedException.class,
                    () -> session.await("first", selection, checkStopped, TIMEOUT_MS));
            verify(old, never()).text();
        }
    }

    @Test
    void lateOldResponseDoesNotDisplaceTheNewExpectedResponse() {
        Response old = response("first");
        Timing timing = new Timing();
        timing.startTime = System.currentTimeMillis() - 60_000;
        when(old.request().timing()).thenReturn(timing);
        Response current = response("first");
        try (BossJobDetailSession session = opened()) {
            emit(current);
            emit(old);
            assertEquals("first", id(session.await("first", selection, checkStopped, TIMEOUT_MS)));
            verify(old, never()).text();
            verify(current).text();
        }
    }

    @Test
    void failedCleanupOfOneListenerStillAttemptsTheOther() {
        doThrow(new IllegalStateException("private token"))
                .when(page).offResponse(anyResponseListener());
        BossJobDetailSession session = opened();
        assertDoesNotThrow(session::close);
        verifyRemovedListeners();
    }

    @Test
    void metadataSessionAcceptsAndCachesAnIdentifiedResponseWithoutOldJobFields() {
        Response response = response("first");
        when(response.text()).thenReturn("{\"zpData\":{\"jobInfo\":{\"encryptId\":\"first\"}}}");
        try (BossJobDetailSession session = new BossJobDetailSession(page, true)) {
            session.beginNavigation();
            emit(response);
            verify(response, never()).text();
            var first = session.await("first", selection, checkStopped, TIMEOUT_MS);
            assertEquals("first", id(first));
            assertSame(first, session.await("first", () -> { }, checkStopped, TIMEOUT_MS));
            verify(response, times(1)).text();
            verifyNoNavigationOrDirectRequest();
        }
        verifyRemovedListeners();
    }

    @Test
    void metadataSessionCannotAcceptAResponseWithoutIdentity() {
        Response missingId = response("first");
        when(missingId.text()).thenReturn("{\"zpData\":{\"jobInfo\":{\"jobName\":\"Java\"}}}");
        try (BossJobDetailSession session = new BossJobDetailSession(page, true)) {
            session.beginNavigation();
            emit(missingId);
            assertThrows(BossTaskPausedException.class,
                    () -> session.await("first", selection, checkStopped, TIMEOUT_MS));
            verify(selection, times(1)).run();
        }
        verifyRemovedListeners();
    }

    @Test
    void originalSessionConstructorStillRequiresTheOldJobName() {
        Response response = response("first");
        when(response.text()).thenReturn("{\"zpData\":{\"jobInfo\":{\"encryptId\":\"first\"}}}");
        try (BossJobDetailSession session = opened()) {
            emit(response);
            assertThrows(BossTaskPausedException.class,
                    () -> session.await("first", selection, checkStopped, TIMEOUT_MS));
        }
        verifyRemovedListeners();
    }

    private BossJobDetailSession opened() {
        BossJobDetailSession session = new BossJobDetailSession(page);
        session.beginNavigation();
        return session;
    }

    private void timeoutWithoutResponse() {
        doAnswer(invocation -> {
            assertFalse(((BooleanSupplier) invocation.getArgument(0)).getAsBoolean());
            throw new TimeoutError("private URL/token");
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));
    }

    private Response response(String id) {
        Response response = mock(Response.class);
        Request request = mock(Request.class);
        when(response.request()).thenReturn(request);
        when(request.url()).thenReturn(DETAIL_URL);
        when(request.method()).thenReturn("GET");
        when(response.status()).thenReturn(200);
        when(response.text()).thenReturn("{\"zpData\":{\"jobInfo\":{\"jobName\":\"Java\",\"encryptId\":\""
                + id + "\"}}}");
        return response;
    }

    private void emit(Response response) {
        onRequest.get().accept(response.request());
        onResponse.get().accept(response);
    }

    private static String id(BossJobDetailReader.Detail detail) {
        return detail.root().getJSONObject("zpData").getJSONObject("jobInfo").getString("encryptId");
    }

    private void verifyRemovedListeners() {
        verify(page, times(1)).offRequest(same(onRequest.get()));
        verify(page, times(1)).offResponse(same(onResponse.get()));
    }

    private void verifyNoNavigationOrDirectRequest() {
        verify(page, never()).navigate(anyString());
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page, never()).reload();
        verify(page, never()).reload(any(Page.ReloadOptions.class));
        verify(page, never()).request();
        verify(page, never()).context();
    }

    private static BooleanSupplier anyCondition() {
        return org.mockito.ArgumentMatchers.<BooleanSupplier>any();
    }

    private static Consumer<Request> anyRequestListener() {
        return org.mockito.ArgumentMatchers.<Consumer<Request>>any();
    }

    private static Consumer<Response> anyResponseListener() {
        return org.mockito.ArgumentMatchers.<Consumer<Response>>any();
    }
}
