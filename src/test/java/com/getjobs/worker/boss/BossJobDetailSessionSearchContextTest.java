package com.getjobs.worker.boss;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock URL/navigation metadata only; no browser, actual request, or external API. */
class BossJobDetailSessionSearchContextTest {
    private static final String A = "https://www.zhipin.com/web/geek/jobs?city=101&jobType=chosen-type&query=Java";
    private static final String B = "https://www.zhipin.com/web/geek/jobs?city=101&jobType=other-type&query=Java";
    private Page page;
    private Frame main;
    private AtomicReference<String> url;
    private AtomicReference<Consumer<Frame>> navigated;
    private AtomicReference<Consumer<Request>> requested;
    private AtomicReference<Consumer<Response>> responded;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        main = mock(Frame.class);
        url = new AtomicReference<>(A);
        navigated = new AtomicReference<>();
        requested = new AtomicReference<>();
        responded = new AtomicReference<>();
        when(page.url()).thenAnswer(ignored -> url.get());
        when(page.mainFrame()).thenReturn(main);
        when(main.url()).thenAnswer(ignored -> url.get());
        Locator verification = mock(Locator.class);
        when(page.locator(BossPageSafety.VERIFICATION_SELECTOR)).thenReturn(verification);
        when(verification.first()).thenReturn(verification);
        doAnswer(call -> { navigated.set(call.getArgument(0)); return null; })
                .when(page).onFrameNavigated(any());
        doAnswer(call -> { requested.set(call.getArgument(0)); return null; }).when(page).onRequest(any());
        doAnswer(call -> { responded.set(call.getArgument(0)); return null; }).when(page).onResponse(any());
    }

    @Test
    void boundCacheAndPendingRequestsAreInvalidatedWhenTypeChanges() {
        try (BossJobDetailSession session = bound()) {
            Response cached = response("cached");
            emit(cached);
            session.await("cached", () -> {}, () -> {}, 1000);
            emit(response("pending"));
            requested.get().accept(response("inflight").request());
            assertSize(session, "details", 1);
            assertSize(session, "pending", 1);
            assertSize(session, "requests", 1);

            url.set(B);
            assertChanged(session);

            assertSize(session, "details", 0);
            assertSize(session, "pending", 0);
            assertSize(session, "requests", 0);
            assertSize(session, "requestOrder", 0);
            url.set(A);
            Runnable selection = mock(Runnable.class);
            assertThrows(BossTaskPausedException.class, () -> session.await("cached", selection, () -> {}, 1000));
            verifyNoInteractions(selection);
            verify(cached, times(1)).text();
        }
    }

    @Test
    void mainFrameAtoBtoARemainsInvalidEvenWhenNoPollHappenedAtB() {
        try (BossJobDetailSession session = bound()) {
            url.set(B);
            navigated.get().accept(main);
            url.set(A);
            navigated.get().accept(main);

            assertChanged(session);
        }
    }

    @Test
    void aNavigationDispatchedDuringBodyReadCannotRefillTheInvalidatedCache() {
        try (BossJobDetailSession session = bound()) {
            Response response = response("inflight");
            when(response.text()).thenAnswer(ignored -> {
                url.set(B);
                navigated.get().accept(main);
                url.set(A);
                navigated.get().accept(main);
                return "{\"zpData\":{\"jobInfo\":{\"encryptId\":\"inflight\",\"jobName\":\"Java\"}}}";
            });
            emit(response);

            assertThrows(BossTaskPausedException.class, () -> session.await("inflight", () -> {}, () -> {}, 1000));

            assertSize(session, "details", 0);
            assertSize(session, "pending", 0);
        }
    }

    @Test
    void subframeNavigationIsIgnored() {
        try (BossJobDetailSession session = bound()) {
            Frame child = mock(Frame.class);
            when(child.url()).thenReturn(B);

            navigated.get().accept(child);

            assertDoesNotThrow(session::requireSearchContextUnchanged);
            verify(child, never()).url();
        }
    }

    @Test
    void trackingAndQueryOrderChangesDoNotPauseTheTask() {
        try (BossJobDetailSession session = bound()) {
            url.set("https://zhipin.com/web/geek/jobs/?query=Java&jobType=chosen-type&city=101&ka=tracking&encryptId=new-job");
            navigated.get().accept(main);

            assertDoesNotThrow(session::requireSearchContextUnchanged);
        }
    }

    @Test
    void keywordAndCityChangesAreAlsoDetected() {
        for (String changed : new String[]{A.replace("city=101", "city=102"), A.replace("query=Java", "query=Python")}) {
            url.set(A);
            try (BossJobDetailSession session = bound()) {
                url.set(changed);
                assertChanged(session);
            }
        }
    }

    @Test
    void aNewProgramNavigationExplicitlyResetsTheBindingAndAllowsNewFilters() {
        try (BossJobDetailSession session = bound()) {
            url.set(B);
            assertChanged(session);

            session.beginNavigation();
            assertDoesNotThrow(session::requireSearchContextUnchanged);
            session.bindSearchContext(B);

            assertDoesNotThrow(session::requireSearchContextUnchanged);
            emit(response("new"));
            assertNotNull(session.await("new", () -> {}, () -> {}, 1000));
        }
    }

    @Test
    void bindingRequiresTheExpectedSearchAndMismatchRemainsTerminal() {
        try (BossJobDetailSession session = opened()) {
            url.set(B);

            assertThrows(BossTaskPausedException.class, () -> session.bindSearchContext(A));
            url.set(A);
            assertChanged(session);
        }
    }

    @Test
    void unboundExistingConstructorRetainsItsPreviousBehavior() {
        try (BossJobDetailSession session = opened()) {
            url.set(B);
            navigated.get().accept(main);
            clearInvocations(page);

            assertDoesNotThrow(session::requireSearchContextUnchanged);

            verify(page, never()).url();
            emit(response("old-style"));
            assertNotNull(session.await("old-style", () -> {}, () -> {}, 1000));
        }
    }

    @Test
    void navigationCallbackOnlyReadsCachedFrameMetadataAndNeverParsesResponses() {
        try (BossJobDetailSession session = bound()) {
            Response queued = response("pending");
            emit(queued);
            clearInvocations(page);
            url.set(B);

            navigated.get().accept(main);

            verify(page).mainFrame();
            verifyNoMoreInteractions(page);
            verify(queued, never()).text();
        }
    }

    @Test
    void closeRemovesTheNavigationListenerExactlyOnce() {
        BossJobDetailSession session = opened();

        session.close();
        session.close();

        verify(page, times(1)).offFrameNavigated(same(navigated.get()));
    }

    private BossJobDetailSession opened() {
        BossJobDetailSession session = new BossJobDetailSession(page);
        session.beginNavigation();
        return session;
    }

    private BossJobDetailSession bound() {
        BossJobDetailSession session = opened();
        session.bindSearchContext(A);
        return session;
    }

    private void assertChanged(BossJobDetailSession session) {
        var failure = assertThrows(BossTaskPausedException.class, session::requireSearchContextUnchanged);
        assertTrue(failure.getMessage().contains("本轮搜索筛选条件已改变，请重新开始"));
        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
    }

    private void assertSize(BossJobDetailSession session, String name, int expected) {
        Object value = ReflectionTestUtils.getField(session, name);
        int size = value instanceof Map<?, ?> map ? map.size() : ((Collection<?>) value).size();
        assertEquals(expected, size, name);
    }

    private void emit(Response response) {
        requested.get().accept(response.request());
        responded.get().accept(response);
    }

    private Response response(String id) {
        Response response = mock(Response.class);
        Request request = mock(Request.class);
        when(request.url()).thenReturn("https://www.zhipin.com/wapi/zpgeek/job/detail.json");
        when(request.method()).thenReturn("GET");
        when(response.request()).thenReturn(request);
        when(response.status()).thenReturn(200);
        when(response.text()).thenReturn("{\"zpData\":{\"jobInfo\":{\"encryptId\":\"" + id
                + "\",\"jobName\":\"Java\"}}}");
        return response;
    }
}
