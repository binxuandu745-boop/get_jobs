package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.Timing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock event delivery only: no browser, real timeout, extra request, or AI call. */
class BossJobDetailAwaiterTest {
    private static final int TIMEOUT_MS = 60_000;
    private static final String DETAIL_URL = "https://www.zhipin.com/wapi/zpgeek/job/detail.json";
    private static final String VALID_BODY = """
            {"zpData":{"jobInfo":{"jobName":"Java开发实习生"}}}
            """;

    private Page page;
    private Locator verification;
    private Runnable selectJobOnce;
    private Runnable checkStopped;
    private AtomicReference<Consumer<Response>> listener;
    private AtomicReference<Consumer<Request>> requestListener;
    private AtomicReference<String> currentUrl;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        verification = mock(Locator.class);
        selectJobOnce = mock(Runnable.class);
        checkStopped = mock(Runnable.class);
        listener = new AtomicReference<>();
        requestListener = new AtomicReference<>();
        currentUrl = new AtomicReference<>("https://www.zhipin.com/web/geek/jobs");
        when(page.url()).thenAnswer(ignored -> currentUrl.get());
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        doAnswer(invocation -> {
            requestListener.set(invocation.getArgument(0));
            return null;
        }).when(page).onRequest(anyRequestListener());
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(page).onResponse(anyResponseListener());
    }

    @Test
    void responseAfterSeveralChecksIsReadWithoutRepeatingSelection() {
        Response response = response(200, VALID_BODY);
        doAnswer(invocation -> {
            BooleanSupplier condition = invocation.getArgument(0);
            Page.WaitForConditionOptions options = invocation.getArgument(1);
            assertEquals((double) TIMEOUT_MS, options.timeout);
            assertFalse(condition.getAsBoolean());
            assertFalse(condition.getAsBoolean());
            assertFalse(condition.getAsBoolean());

            clearInvocations(page, verification);
            emitResponse(response);
            // Event handlers must not perform page probes or read the response body.
            verifyNoInteractions(page, verification);
            verify(response, never()).text();
            assertTrue(condition.getAsBoolean());
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        var detail = await();

        assertEquals(VALID_BODY, detail.body());
        assertEquals("Java开发实习生", detail.root().getJSONObject("zpData")
                .getJSONObject("jobInfo").getString("jobName"));
        verify(response).text();
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void listenerIsInstalledBeforeSelectionAndCapturesAnImmediateResponse() {
        Response response = response(200, VALID_BODY);
        doAnswer(ignored -> {
            assertNotNull(listener.get());
            assertNotNull(requestListener.get());
            emitResponse(response);
            verify(response, never()).text();
            return null;
        }).when(selectJobOnce).run();
        doAnswer(invocation -> {
            assertTrue(((BooleanSupplier) invocation.getArgument(0)).getAsBoolean());
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        assertEquals(VALID_BODY, await().body());
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void onlyFirstMatchingGetResponseIsRead() {
        Response otherPath = response(200, VALID_BODY);
        when(otherPath.url()).thenReturn("https://www.zhipin.com/wapi/zpgeek/job/list.json");
        when(otherPath.request().url()).thenReturn("https://www.zhipin.com/wapi/zpgeek/job/list.json");
        Response post = response(200, VALID_BODY);
        when(post.request().method()).thenReturn("POST");
        Response first = response(200, VALID_BODY);
        Response later = response(200, "must not be read");
        doAnswer(invocation -> {
            BooleanSupplier condition = invocation.getArgument(0);
            emitResponse(otherPath);
            emitResponse(post);
            assertFalse(condition.getAsBoolean());
            emitResponse(first);
            emitResponse(later);
            assertTrue(condition.getAsBoolean());
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        assertEquals(VALID_BODY, await().body());
        verify(otherPath, never()).text();
        verify(post, never()).text();
        verify(later, never()).text();
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void lateResponseFromAnUnobservedOldRequestIsIgnored() {
        Response oldResponse = response(200, "must not be read");
        Response currentResponse = response(200, VALID_BODY);
        doAnswer(invocation -> {
            BooleanSupplier condition = invocation.getArgument(0);
            listener.get().accept(oldResponse);
            assertFalse(condition.getAsBoolean());
            emitResponse(currentResponse);
            assertTrue(condition.getAsBoolean());
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        assertEquals(VALID_BODY, await().body());
        verify(oldResponse, never()).text();
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void requestObservedBeforeSelectionIsIgnoredEvenWhenItsResponseArrivesFirst() {
        Response oldResponse = response(200, "must not be read");
        Response currentResponse = response(200, VALID_BODY);
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            // Simulate a previously issued request arriving while both handlers are being registered.
            requestListener.get().accept(oldResponse.request());
            return null;
        }).when(page).onResponse(anyResponseListener());
        doAnswer(invocation -> {
            BooleanSupplier condition = invocation.getArgument(0);
            listener.get().accept(oldResponse);
            assertFalse(condition.getAsBoolean());
            emitResponse(currentResponse);
            assertTrue(condition.getAsBoolean());
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        assertEquals(VALID_BODY, await().body());
        verify(oldResponse, never()).text();
        verifySelectedOnceAndRemovedListener();
    }

    @Test
    void queuedOldRequestEventAfterSelectionIsExcludedByItsStartTime() {
        Response oldResponse = response(200, "must not be read");
        Timing oldTiming = new Timing();
        oldTiming.startTime = System.currentTimeMillis() - 60_000;
        when(oldResponse.request().timing()).thenReturn(oldTiming);
        Response currentResponse = response(200, VALID_BODY);
        Timing currentTiming = new Timing();
        when(currentResponse.request().timing()).thenReturn(currentTiming);
        doAnswer(ignored -> {
            currentTiming.startTime = System.currentTimeMillis();
            return null;
        }).when(selectJobOnce).run();
        doAnswer(invocation -> {
            BooleanSupplier condition = invocation.getArgument(0);
            // Its request event was queued, but metadata proves it started before this selection.
            emitResponse(oldResponse);
            assertFalse(condition.getAsBoolean());
            emitResponse(currentResponse);
            assertTrue(condition.getAsBoolean());
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        assertEquals(VALID_BODY, await().body());
        verify(oldResponse, never()).text();
        verifySelectedOnceAndRemovedListener();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void unavailableStartTimeKeepsRequestIdentityAssociation(double startTime) {
        Response response = response(200, VALID_BODY);
        Timing timing = new Timing();
        timing.startTime = startTime;
        when(response.request().timing()).thenReturn(timing);
        deliverResponse(response);

        assertEquals(VALID_BODY, await().body());
        verifySelectedOnceAndRemovedListener();
    }

    @ParameterizedTest
    @ValueSource(strings = {"response", "request"})
    void cleanupFailureDoesNotReplaceVerificationOrSkipOtherListenerCleanup(String failedEvent) {
        failCleanup(failedEvent);
        doAnswer(invocation -> {
            currentUrl.set("https://www.zhipin.com/web/passport/zp/verify.html");
            ((BooleanSupplier) invocation.getArgument(0)).getAsBoolean();
            fail("Verification must stop at this condition check");
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        assertFalse(failure.getMessage().contains("private cleanup"));
        verifySelectedOnceAndRemovedListener();
    }

    @Test
    void failuresRemovingBothListenersStillPreserveTheOriginalCancellation() {
        failCleanup("response");
        failCleanup("request");
        CancellationException cancellation = new CancellationException("cancelled");
        doAnswer(invocation -> {
            doThrow(cancellation).when(checkStopped).run();
            ((BooleanSupplier) invocation.getArgument(0)).getAsBoolean();
            fail("Cancellation must stop at this condition check");
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        assertSame(cancellation, assertThrows(CancellationException.class, this::await));
        verifySelectedOnceAndRemovedListener();
    }

    @Test
    void cleanupFailureDoesNotReplaceASuccessfulDetailRead() {
        failCleanup("response");
        failCleanup("request");
        deliverResponse(response(200, VALID_BODY));

        assertEquals(VALID_BODY, await().body());
        verifySelectedOnceAndRemovedListener();
    }

    @Test
    void verificationUrlDuringWaitImmediatelyStopsAndCleansUp() {
        doAnswer(invocation -> {
            BooleanSupplier condition = invocation.getArgument(0);
            assertFalse(condition.getAsBoolean());
            currentUrl.set("https://www.zhipin.com/web/passport/zp/verify.html");
            condition.getAsBoolean();
            fail("Verification must stop at this condition check");
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void visibleVerificationDuringWaitStopsBeforeReadingEvenWhenResponseArrives() {
        Response response = response(200, VALID_BODY);
        doAnswer(invocation -> {
            emitResponse(response);
            when(verification.isVisible()).thenReturn(true);
            ((BooleanSupplier) invocation.getArgument(0)).getAsBoolean();
            fail("Verification must take priority over an available response");
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verify(response, never()).text();
        verifySelectedOnceAndRemovedListener();
    }

    @Test
    void closedPageDuringWaitStopsAndRemovesListener() {
        doAnswer(invocation -> {
            when(page.isClosed()).thenReturn(true);
            ((BooleanSupplier) invocation.getArgument(0)).getAsBoolean();
            fail("A closed page must stop at this condition check");
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void readTimeoutMentionsTheBudgetWithoutClaimingConfirmedVerification() {
        doThrow(new TimeoutError("private URL/token in SDK timeout"))
                .when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        assertTrue(failure.getMessage().contains("读取超时"));
        assertTrue(failure.getMessage().contains("60000 ms"));
        assertTrue(failure.getMessage().contains("尚未确认是否触发安全验证"));
        assertFalse(failure.getMessage().contains("private URL/token"));
        verify(page, times(2)).isClosed();
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void verificationDiscoveredAfterTimeoutIsPreserved() {
        doAnswer(ignored -> {
            currentUrl.set("https://www.zhipin.com/web/user/safe/verify-slider");
            throw new TimeoutError("private timeout details");
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void cancellationDuringWaitPropagatesAndRemovesListener() {
        AtomicBoolean cancelled = new AtomicBoolean();
        CancellationException cancellation = new CancellationException("cancelled");
        doAnswer(ignored -> {
            if (cancelled.get()) throw cancellation;
            return null;
        }).when(checkStopped).run();
        doAnswer(invocation -> {
            BooleanSupplier condition = invocation.getArgument(0);
            assertFalse(condition.getAsBoolean());
            cancelled.set(true);
            condition.getAsBoolean();
            fail("Cancellation must stop at this condition check");
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        assertSame(cancellation, assertThrows(CancellationException.class, this::await));
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void cancellationAfterWaitPreventsReadingResponse() {
        Response response = response(200, VALID_BODY);
        CancellationException cancellation = new CancellationException("cancelled");
        doAnswer(invocation -> {
            emitResponse(response);
            assertTrue(((BooleanSupplier) invocation.getArgument(0)).getAsBoolean());
            doThrow(cancellation).when(checkStopped).run();
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        assertSame(cancellation, assertThrows(CancellationException.class, this::await));
        verify(response, never()).text();
        verifySelectedOnceAndRemovedListener();
    }

    @Test
    void selectionFailureIsSanitizedAndListenerIsRemoved() {
        doThrow(new IllegalStateException("private URL/body/token"))
                .when(selectJobOnce).run();

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        assertFalse(failure.getMessage().contains("private"));
        verify(page, never()).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void unexpectedWaitFailureIsSanitizedAndListenerIsRemoved() {
        doThrow(new IllegalStateException("private URL/body/token"))
                .when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        assertFalse(failure.getMessage().contains("private"));
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "<html>verification</html>", "{bad json"})
    void invalidResponsesRemainTechnicalReadFailures(String body) {
        Response response = response(200, body);
        deliverResponse(response);

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        assertTrue(failure.getMessage().contains("尚未确认是否触发安全验证"));
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 403, 429, 500})
    void nonSuccessfulHttpResponsesDoNotReadBodyOrClaimVerification(int status) {
        Response response = response(status, "private body");
        deliverResponse(response);

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        verify(response, never()).text();
        verifySelectedOnceAndRemovedListener();
    }

    @Test
    void bodyReadFailureStillRemovesListenerAndDoesNotExposeDetails() {
        Response response = response(200, VALID_BODY);
        when(response.text()).thenThrow(new IllegalStateException("private URL/body/token"));
        deliverResponse(response);

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        assertFalse(failure.getMessage().contains("private"));
        verifySelectedOnceAndRemovedListener();
    }

    @Test
    void existingVerificationPreventsSelectionOrListenerInstallation() {
        currentUrl.set("https://www.zhipin.com/web/passport/zp/verify.html");

        var failure = assertThrows(BossTaskPausedException.class, this::await);

        assertEquals(BossTaskPausedException.Reason.SECURITY_VERIFICATION, failure.getReason());
        verifyNoInteractions(selectJobOnce);
        verify(page, never()).onResponse(anyResponseListener());
        verify(page, never()).onRequest(anyRequestListener());
        verifyNoNavigationOrExtraRequest();
    }

    private BossJobDetailReader.Detail await() {
        return BossJobDetailAwaiter.await(page, selectJobOnce, checkStopped, TIMEOUT_MS);
    }

    @Test
    void metadataModePropagatesToReaderAndAllowsAResponseWithoutOldJobFields() {
        String metadata = "{\"zpData\":{\"jobInfo\":{\"encryptId\":\"metadata-job\"}}}";
        Response response = response(200, metadata);
        deliverResponse(response);

        var detail = BossJobDetailAwaiter.await(page, selectJobOnce, checkStopped, TIMEOUT_MS, true);

        assertEquals(metadata, detail.body());
        verify(response, times(1)).text();
        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void metadataAwaitStillRejectsAResponseWithoutAJobIdentityAndRemovesListeners() {
        Response response = response(200, VALID_BODY);
        deliverResponse(response);

        assertThrows(BossTaskPausedException.class,
                () -> BossJobDetailAwaiter.await(page, selectJobOnce, checkStopped, TIMEOUT_MS, true));

        verifySelectedOnceAndRemovedListener();
        verifyNoNavigationOrExtraRequest();
    }

    @Test
    void originalAwaitOverloadStillRejectsMetadataWithoutAJobName() {
        Response response = response(200, "{\"zpData\":{\"jobInfo\":{\"encryptId\":\"metadata-job\"}}}");
        deliverResponse(response);

        assertThrows(BossTaskPausedException.class, this::await);

        verifySelectedOnceAndRemovedListener();
    }

    private void deliverResponse(Response response) {
        doAnswer(invocation -> {
            emitResponse(response);
            assertTrue(((BooleanSupplier) invocation.getArgument(0)).getAsBoolean());
            return null;
        }).when(page).waitForCondition(anyCondition(), any(Page.WaitForConditionOptions.class));
    }

    private Response response(int status, String body) {
        Response response = mock(Response.class);
        Request request = mock(Request.class);
        when(response.url()).thenReturn(DETAIL_URL);
        when(response.request()).thenReturn(request);
        when(request.url()).thenReturn(DETAIL_URL);
        when(request.method()).thenReturn("GET");
        when(response.status()).thenReturn(status);
        when(response.text()).thenReturn(body);
        return response;
    }

    private void verifySelectedOnceAndRemovedListener() {
        verify(selectJobOnce, times(1)).run();
        assertNotNull(listener.get());
        assertNotNull(requestListener.get());
        verify(page, times(1)).offResponse(same(listener.get()));
        verify(page, times(1)).offRequest(same(requestListener.get()));
    }

    private void verifyNoNavigationOrExtraRequest() {
        verify(page, never()).navigate(anyString());
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page, never()).reload();
        verify(page, never()).reload(any(Page.ReloadOptions.class));
        verify(page, never()).context();
        verify(page, never()).request();
        verify(page, never()).waitForTimeout(anyDouble());
    }

    private static Consumer<Response> anyResponseListener() {
        return org.mockito.ArgumentMatchers.<Consumer<Response>>any();
    }

    private static Consumer<Request> anyRequestListener() {
        return org.mockito.ArgumentMatchers.<Consumer<Request>>any();
    }

    private void emitResponse(Response response) {
        requestListener.get().accept(response.request());
        listener.get().accept(response);
    }

    private void failCleanup(String eventType) {
        if ("response".equals(eventType)) {
            doThrow(new IllegalStateException("private cleanup URL/body/token"))
                    .when(page).offResponse(anyResponseListener());
        } else {
            doThrow(new IllegalStateException("private cleanup URL/body/token"))
                    .when(page).offRequest(anyRequestListener());
        }
    }

    private static BooleanSupplier anyCondition() {
        return org.mockito.ArgumentMatchers.<BooleanSupplier>any();
    }
}
