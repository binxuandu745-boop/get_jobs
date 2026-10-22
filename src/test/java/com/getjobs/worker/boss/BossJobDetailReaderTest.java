package com.getjobs.worker.boss;

import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossJobDetailReaderTest {
    @Test
    void missingResponseStopsRatherThanFilteringNullFields() {
        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossJobDetailReader.read(null));
        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "<html>verification page</html>", "{bad json",
            "{}", "{\"zpData\":null}", "{\"zpData\":{\"jobInfo\":{}}}",
            "{\"zpData\":{\"jobInfo\":{\"jobName\":null}}}",
            "{\"zpData\":{\"jobInfo\":{\"jobName\":\"  \"}}}"})
    void emptyMalformedAndMissingCriticalDataStopWithoutClaimingConfirmedRisk(String body) {
        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossJobDetailReader.read(response(200, body)));
        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
        assertTrue(failure.getMessage().contains("尚未确认是否触发安全验证"));
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 403, 429, 500})
    void failedHttpResponseStopsBeforeReadingItsBody(int status) {
        Response response = response(status, "must not be read");
        assertThrows(BossTaskPausedException.class, () -> BossJobDetailReader.read(response));
        verify(response, never()).text();
    }

    @Test
    void missingCompanyAloneDoesNotProveSafetyVerification() {
        String body = "{\"zpData\":{\"jobInfo\":{\"jobName\":\"Java开发工程师\"}}}";
        var detail = BossJobDetailReader.read(response(200, body));
        assertEquals(body, detail.body());
        assertEquals("Java开发工程师", detail.root().getJSONObject("zpData")
                .getJSONObject("jobInfo").getString("jobName"));
    }

    @Test
    void failedBodyReadStopsWithoutExposingTheUnderlyingMessage() {
        Response response = response(200, "");
        when(response.text()).thenThrow(new IllegalStateException("private response details"));
        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossJobDetailReader.read(response));
        assertFalse(failure.getMessage().contains("private response details"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"zpData\":{\"jobInfo\":{\"encryptId\":\"job-id\"}}}",
            "{\"zpData\":{\"jobInfo\":{\"encryptId\":\"job-id\",\"jobName\":null}}}",
            "{\"zpData\":{\"jobInfo\":{\"encryptId\":\"job-id\",\"jobName\":\" \"}}}"
    })
    void screenshotMetadataModeNeedsIdentityButDoesNotNeedTheOldJobTitle(String body) {
        var detail = BossJobDetailReader.read(response(200, body), true);
        assertEquals("job-id", detail.root().getJSONObject("zpData").getJSONObject("jobInfo").getString("encryptId"));
        assertEquals(body, detail.body());
        assertThrows(BossTaskPausedException.class, () -> BossJobDetailReader.read(response(200, body)));
        assertThrows(BossTaskPausedException.class, () -> BossJobDetailReader.read(response(200, body), false));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "{bad json", "{}", "{\"zpData\":null}",
            "{\"zpData\":{\"jobInfo\":{\"jobName\":\"Java\"}}}",
            "{\"zpData\":{\"jobInfo\":{\"encryptId\":null}}}",
            "{\"zpData\":{\"jobInfo\":{\"encryptId\":\" \"}}}",
            "{\"zpData\":{\"jobInfo\":{\"encryptId\":123}}}"})
    void metadataModeStillRejectsMalformedResponsesAndMissingOrNonStringIdentity(String body) {
        var failure = assertThrows(BossTaskPausedException.class,
                () -> BossJobDetailReader.read(response(200, body), true));
        assertEquals(BossTaskPausedException.Reason.JOB_DETAILS_UNAVAILABLE, failure.getReason());
    }

    @Test
    void metadataModeStillRequiresSuccessfulHttpBeforeReadingTheBody() {
        Response failed = response(403, "private body");
        assertThrows(BossTaskPausedException.class, () -> BossJobDetailReader.read(failed, true));
        verify(failed, never()).text();
    }

    private Response response(int status, String body) {
        Response response = mock(Response.class);
        when(response.status()).thenReturn(status);
        when(response.text()).thenReturn(body);
        return response;
    }
}
