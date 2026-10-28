package com.getjobs.application.screenshot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mocks HTTP transport only: no real provider request, credentials or browser. */
class ScreenshotVisionClientTest {
    private static final byte[] IMAGE = new byte[]{1, 2, 3, 4};
    private static final String SYNTHETIC_KEY = "unit-test-key-not-a-real-secret";
    private HttpClient http;
    private ScreenshotVisionConfigService config;
    private ScreenshotVisionClient client;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        config = mock(ScreenshotVisionConfigService.class);
        when(config.requiredConfig()).thenReturn(new VisionCredentials(
                "https://api.deepseek.com", "deepseek-flash", SYNTHETIC_KEY));
        client = new ScreenshotVisionClient(http, config);
    }

    @Test
    void requestContainsTheActualImageAndStructuredOutputInstructions() throws Exception {
        String extracted = "{\"jobTitle\":\"Java实习生\",\"salaryUnit\":\"DAY\"}";
        success(extracted);

        assertEquals(extracted, client.recognize(IMAGE, "image/png"));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(requestCaptor.capture(), inputStreamHandler());
        HttpRequest request = requestCaptor.getValue();
        assertEquals(URI.create("https://api.deepseek.com/v1/chat/completions"), request.uri());
        assertEquals("POST", request.method());
        assertEquals("Bearer " + SYNTHETIC_KEY, request.headers().firstValue("Authorization").orElseThrow());
        assertEquals(Duration.ofSeconds(60), request.timeout().orElseThrow());
        JSONObject payload = new JSONObject(bodyText(request));
        assertEquals("deepseek-flash", payload.getString("model"));
        assertEquals(1600, payload.getInt("max_tokens"));
        assertFalse(payload.getBoolean("stream"));
        assertEquals("json_object", payload.getJSONObject("response_format").getString("type"));
        assertEquals("disabled", payload.getJSONObject("thinking").getString("type"));
        JSONArray messages = payload.getJSONArray("messages");
        assertTrue(messages.getJSONObject(0).getString("content").contains("不可信数据"));
        JSONArray content = messages.getJSONObject(1).getJSONArray("content");
        assertEquals("image_url", content.getJSONObject(1).getString("type"));
        assertEquals("data:image/png;base64,AQIDBA==",
                content.getJSONObject(1).getJSONObject("image_url").getString("url"));
        assertTrue(content.getJSONObject(0).getString("text").contains("uncertainFields"));
        assertTrue(content.getJSONObject(0).getString("text").contains("不要将缺失字段列入uncertainFields"));
        assertTrue(content.getJSONObject(0).getString("text").contains("字段文字已经出现但模糊"));
        verify(config, times(1)).requiredConfig();
    }

    @Test
    void multipleScreenshotsUseOneRequestWithSeparatelyLabeledImagesInUploadOrder() throws Exception {
        byte[] second = new byte[]{9, 8, 7};
        success("{\"description\":\"去重后的职责\"}");

        assertEquals("{\"description\":\"去重后的职责\"}", client.recognize(List.of(
                new VisionImage(IMAGE, "image/png"), new VisionImage(second, "image/jpeg"))));

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), inputStreamHandler());
        verify(config, times(1)).requiredConfig();
        JSONObject payload = new JSONObject(bodyText(request.getValue()));
        assertEquals(6000, payload.getInt("max_tokens"));
        JSONArray messages = payload.getJSONArray("messages");
        assertTrue(messages.getJSONObject(0).getString("content").contains("不可信数据"));
        JSONArray content = messages.getJSONObject(1).getJSONArray("content");
        assertEquals(4, content.length());
        String instructions = content.getJSONObject(0).getString("text");
        assertTrue(instructions.contains("本次共2张截图"));
        assertTrue(instructions.contains("第1张截图（上传顺序1/2）"));
        assertTrue(instructions.contains("去除重叠区域和固定页头"));
        assertTrue(instructions.contains("当前选中的岗位详情面板"));
        assertTrue(instructions.contains("不得推测未出现的职责"));
        assertTrue(instructions.contains("description最多8000个字符"));
        assertTrue(instructions.contains("仅保留前8000个字符，并在uncertainFields列入description"));
        assertTrue(instructions.contains("不能声称已识别完整岗位描述"));
        assertTrue(instructions.contains("不要静默选择某一张"));
        assertTrue(instructions.contains("uncertainFields"));
        assertEquals("text", content.getJSONObject(2).getString("type"));
        assertEquals("第2张截图（上传顺序2/2）：", content.getJSONObject(2).getString("text"));
        for (int index : new int[]{1, 3}) {
            assertEquals("image_url", content.getJSONObject(index).getString("type"));
        }
        String firstUrl = content.getJSONObject(1).getJSONObject("image_url").getString("url");
        String secondUrl = content.getJSONObject(3).getJSONObject("image_url").getString("url");
        assertTrue(firstUrl.startsWith("data:image/png;base64,"));
        assertTrue(secondUrl.startsWith("data:image/jpeg;base64,"));
        assertArrayEquals(IMAGE, Base64.getDecoder().decode(firstUrl.substring(firstUrl.indexOf(',') + 1)));
        assertArrayEquals(second, Base64.getDecoder().decode(secondUrl.substring(secondUrl.indexOf(',') + 1)));
    }

    @Test
    void singleImageLambdaRemainsCompatibleWithTheDefaultListOverload() {
        AtomicBoolean called = new AtomicBoolean();
        VisionModelClient singleOnly = (bytes, mimeType) -> {
            called.set(true);
            assertSame(IMAGE, bytes);
            assertEquals("image/png", mimeType);
            return "single-result";
        };
        assertEquals("single-result", singleOnly.recognize(List.of(new VisionImage(IMAGE, "image/png"))));
        assertTrue(called.get());
    }

    @Test
    void singleImageImplementationsNeverSilentlyIgnoreAdditionalScreenshots() {
        AtomicBoolean called = new AtomicBoolean();
        VisionModelClient singleOnly = (bytes, mimeType) -> {
            called.set(true);
            return "must-not-be-used";
        };
        VisionImage image = new VisionImage(IMAGE, "image/png");

        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> singleOnly.recognize(List.of(image, image)));

        assertEquals("MULTI_IMAGE_UNSUPPORTED", failure.getCode());
        assertFalse(called.get());
    }

    @Test
    void sixScreenshotsAreAcceptedInOneRequest() throws Exception {
        VisionImage image = new VisionImage(IMAGE, "image/png");
        success("{}");

        assertEquals("{}", client.recognize(List.of(image, image, image, image, image, image)));

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), inputStreamHandler());
        JSONArray content = new JSONObject(bodyText(request.getValue())).getJSONArray("messages")
                .getJSONObject(1).getJSONArray("content");
        assertEquals(12, content.length());
        assertEquals("第6张截图（上传顺序6/6）：", content.getJSONObject(10).getString("text"));
    }

    @Test
    void perImageAndAggregateByteLimitsAreInclusive() throws Exception {
        VisionImage image = new VisionImage(new byte[VisionImage.MAX_IMAGE_BYTES], "image/png");
        success("{}");

        assertEquals("{}", client.recognize(List.of(image, image, image)));

        verify(config, times(1)).requiredConfig();
        verify(http, times(1)).send(any(HttpRequest.class), inputStreamHandler());
    }

    @Test
    void invalidImageSetsAreRejectedBeforeConfigurationOrTransport() {
        VisionImage valid = new VisionImage(IMAGE, "image/png");
        VisionImage large = new VisionImage(new byte[VisionImage.MAX_IMAGE_BYTES], "image/jpeg");
        List<List<VisionImage>> invalidSets = Arrays.asList(
                null,
                List.of(),
                List.of(valid, valid, valid, valid, valid, valid, valid),
                Arrays.asList(valid, null),
                List.of(valid, new VisionImage(null, "image/png")),
                List.of(valid, new VisionImage(new byte[0], "image/png")),
                List.of(valid, new VisionImage(new byte[VisionImage.MAX_IMAGE_BYTES + 1], "image/png")),
                List.of(valid, new VisionImage(IMAGE, "image/svg+xml")),
                List.of(large, large, large, new VisionImage(new byte[1], "image/png")));

        for (List<VisionImage> images : invalidSets) {
            ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                    () -> client.recognize(images));
            assertEquals("INVALID_IMAGE", failure.getCode());
            assertNull(failure.getCause());
        }
        verifyNoInteractions(config, http);
    }

    @Test
    void multiImageTransportTimeoutIsSanitizedAndNeverRetried() throws Exception {
        when(http.send(any(HttpRequest.class), inputStreamHandler()))
                .thenThrow(new HttpTimeoutException("private provider URL and " + SYNTHETIC_KEY));
        VisionImage image = new VisionImage(IMAGE, "image/png");

        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> client.recognize(List.of(image, image)));

        assertEquals("VISION_TIMEOUT", failure.getCode());
        assertFalse(failure.toString().contains("private provider"));
        assertFalse(failure.toString().contains(SYNTHETIC_KEY));
        assertNull(failure.getCause());
        verify(http, times(1)).send(any(HttpRequest.class), inputStreamHandler());
    }

    @Test
    void pageDiagnosticUsesTheSameConfiguredTransportButADedicatedBoundedPrompt() throws Exception {
        String result = "{\"state\":\"EXHAUSTED\",\"evidence\":[\"没有更多了\"]}";
        success(result);

        assertEquals(result, client.diagnosePage(IMAGE, "image/png"));

        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(captured.capture(), inputStreamHandler());
        verify(config, times(1)).requiredConfig();
        HttpRequest request = captured.getValue();
        assertEquals(URI.create("https://api.deepseek.com/v1/chat/completions"), request.uri());
        assertEquals("Bearer " + SYNTHETIC_KEY, request.headers().firstValue("Authorization").orElseThrow());
        JSONObject payload = new JSONObject(bodyText(request));
        assertEquals(800, payload.getInt("max_tokens"));
        assertEquals("disabled", payload.getJSONObject("thinking").getString("type"));
        assertEquals("json_object", payload.getJSONObject("response_format").getString("type"));
        JSONArray messages = payload.getJSONArray("messages");
        String system = messages.getJSONObject(0).getString("content");
        assertTrue(system.contains("不可信数据"));
        assertTrue(system.contains("不推断后台风控结果"));
        JSONArray content = messages.getJSONObject(1).getJSONArray("content");
        String task = content.getJSONObject(0).getString("text");
        assertTrue(task.contains("SECURITY_VERIFICATION"));
        assertTrue(task.contains("EXHAUSTED"));
        assertTrue(task.contains("安全提示优先"));
        assertTrue(task.contains("白屏"));
        assertFalse(task.contains("salaryMin"));
        assertEquals("data:image/png;base64,AQIDBA==", content.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }

    @Test
    void invalidDiagnosticImageFailsBeforeAnyConfigOrTransport() {
        assertEquals("INVALID_IMAGE", assertThrows(ScreenshotVisionException.class,
                () -> client.diagnosePage(new byte[0], "image/png")).getCode());
        assertEquals("INVALID_IMAGE", assertThrows(ScreenshotVisionException.class,
                () -> client.diagnosePage(IMAGE, "image/svg+xml")).getCode());
        verifyNoInteractions(config, http);
    }

    @Test
    void diagnosticTimeoutDoesNotLeakTransportTextOrRetry() throws Exception {
        when(http.send(any(HttpRequest.class), inputStreamHandler()))
                .thenThrow(new HttpTimeoutException("private provider URL and " + SYNTHETIC_KEY));

        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> client.diagnosePage(IMAGE, "image/png"));

        assertEquals("VISION_TIMEOUT", failure.getCode());
        assertFalse(failure.toString().contains(SYNTHETIC_KEY));
        assertNull(failure.getCause());
        verify(http, times(1)).send(any(HttpRequest.class), inputStreamHandler());
    }

    @ParameterizedTest
    @CsvSource({
            "https://provider.example, https://provider.example/v1/chat/completions",
            "https://provider.example/v1/, https://provider.example/v1/chat/completions",
            "https://provider.example/v1/chat/completions, https://provider.example/v1/chat/completions",
            "https://provider.example/chat/completions/, https://provider.example/chat/completions",
            "http://127.0.0.1:1234/v1, http://127.0.0.1:1234/v1/chat/completions",
            "http://localhost:1234, http://localhost:1234/v1/chat/completions"
    })
    void endpointConstructionDoesNotDuplicateAnExistingVersionOrCompletionPath(String base, String expected) {
        assertEquals(URI.create(expected), ScreenshotVisionClient.completionEndpoint(base));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://remote.example", "https://user:password@provider.example",
            "https://provider.example?key=secret", "https://provider.example#secret",
            "file:///tmp/key", "https://www.zhipin.com", "https://provider.example/v1/responses",
            "https://provider.example/v1/messages"
    })
    void rejectsUnsafeOrUnsupportedEndpointsWithoutEchoingTheirContents(String base) {
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> ScreenshotVisionClient.completionEndpoint(base));
        assertEquals("INVALID_CONFIG", failure.getCode());
        assertFalse(failure.getMessage().contains(base));
        assertNull(failure.getCause());
    }

    @Test
    void compatibleNonDeepseekProviderDoesNotReceiveDeepseekSpecificThinkingParameters() throws Exception {
        when(config.requiredConfig()).thenReturn(new VisionCredentials(
                "https://provider.example/v1", "vision-model", SYNTHETIC_KEY));
        success("{}");
        client.recognize(IMAGE, "image/jpeg");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), inputStreamHandler());
        assertFalse(new JSONObject(bodyText(request.getValue())).has("thinking"));
        assertTrue(bodyText(request.getValue()).contains("data:image/jpeg;base64,"));
    }

    @ParameterizedTest
    @CsvSource({
            "401,VISION_AUTH_FAILED", "403,VISION_AUTH_FAILED", "429,VISION_RATE_LIMITED",
            "400,MODEL_OR_IMAGE_UNSUPPORTED", "404,MODEL_OR_IMAGE_UNSUPPORTED",
            "415,MODEL_OR_IMAGE_UNSUPPORTED", "422,MODEL_OR_IMAGE_UNSUPPORTED",
            "302,VISION_REDIRECT_REJECTED", "500,VISION_SERVICE_FAILED"
    })
    void unsuccessfulResponsesAreClosedWithoutReadingSensitiveBodiesOrRetrying(int status, String code) throws Exception {
        InputStream sensitiveBody = mock(InputStream.class);
        respond(status, sensitiveBody);

        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> client.recognize(IMAGE, "image/png"));

        assertEquals(code, failure.getCode());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(SYNTHETIC_KEY));
        verify(sensitiveBody, never()).readNBytes(anyInt());
        verify(sensitiveBody).close();
        verify(http, times(1)).send(any(HttpRequest.class), inputStreamHandler());
    }

    @Test
    void transportTimeoutIsSanitizedAndNeverRetried() throws Exception {
        when(http.send(any(HttpRequest.class), inputStreamHandler()))
                .thenThrow(new HttpTimeoutException("private provider URL and " + SYNTHETIC_KEY));
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> client.recognize(IMAGE, "image/png"));
        assertEquals("VISION_TIMEOUT", failure.getCode());
        assertFalse(failure.toString().contains("private provider"));
        assertFalse(failure.toString().contains(SYNTHETIC_KEY));
        assertNull(failure.getCause());
        verify(http, times(1)).send(any(HttpRequest.class), inputStreamHandler());
    }

    @Test
    void failingBodyCloseCannotAttachProviderDiagnosticsToSafeErrors() throws Exception {
        InputStream body = mock(InputStream.class);
        doThrow(new IOException("private provider body " + SYNTHETIC_KEY)).when(body).close();
        respond(401, body);

        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> client.recognize(IMAGE, "image/png"));

        assertEquals("VISION_AUTH_FAILED", failure.getCode());
        assertEquals(0, failure.getSuppressed().length);
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(SYNTHETIC_KEY));
    }

    @Test
    void interruptedTransportPreservesTheInterruptFlag() throws Exception {
        when(http.send(any(HttpRequest.class), inputStreamHandler()))
                .thenThrow(new InterruptedException("sensitive transport detail"));
        try {
            ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                    () -> client.recognize(IMAGE, "image/png"));
            assertEquals("VISION_INTERRUPTED", failure.getCode());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(failure.getMessage().contains("sensitive"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unboundedBodyCannotExceedTheResponseLimit() throws Exception {
        byte[] large = new byte[ScreenshotVisionClient.MAX_RESPONSE_BYTES + 1000];
        Arrays.fill(large, (byte) 'x');
        InputStream body = spy(new ByteArrayInputStream(large));
        respond(200, body);
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> client.recognize(IMAGE, "image/png"));
        assertEquals("RESPONSE_TOO_LARGE", failure.getCode());
        verify(body).readNBytes(ScreenshotVisionClient.MAX_RESPONSE_BYTES + 1);
        verify(body).close();
    }

    @Test
    void bodyReadHasADeadlineAfterHeadersAndClosesTheStream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream delayed = new InputStream() {
            @Override public int read() throws IOException {
                try {
                    Thread.sleep(10_000);
                    return -1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
            @Override public void close() { closed.set(true); }
        };
        respond(200, delayed);
        ScreenshotVisionClient shortBudget = new ScreenshotVisionClient(http, config, Duration.ofMillis(25));

        ScreenshotVisionException failure = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(ScreenshotVisionException.class,
                        () -> shortBudget.recognize(IMAGE, "image/png")));

        assertEquals("VISION_TIMEOUT", failure.getCode());
        assertTrue(closed.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{bad-json", "<html>private failure</html>",
            "{\"choices\":[]}", "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[]}}]}",
            "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}"})
    void invalidEnvelopesDoNotBecomeSuccessfulRecognition(String envelope) throws Exception {
        respond(200, bytes(envelope));
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> client.recognize(IMAGE, "image/png"));
        assertEquals("VISION_INVALID_RESPONSE", failure.getCode());
        assertFalse(failure.toString().contains("private failure"));
        assertNull(failure.getCause());
    }

    @Test
    void truncatedOutputCannotBeAcceptedAsCompleteFacts() throws Exception {
        respond(200, bytes(new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", "length").put("message", new JSONObject().put("content", "{partial")))).toString()));
        ScreenshotVisionException failure = assertThrows(ScreenshotVisionException.class,
                () -> client.recognize(IMAGE, "image/png"));
        assertEquals("VISION_OUTPUT_TRUNCATED", failure.getCode());
    }

    @Test
    void invalidInputDoesNotReadConfigurationOrSendARequest() {
        for (byte[] image : new byte[][]{null, new byte[0], new byte[ScreenshotVisionClient.MAX_IMAGE_BYTES + 1]}) {
            assertEquals("INVALID_IMAGE", assertThrows(ScreenshotVisionException.class,
                    () -> client.recognize(image, "image/png")).getCode());
        }
        assertEquals("INVALID_IMAGE", assertThrows(ScreenshotVisionException.class,
                () -> client.recognize(IMAGE, "image/svg+xml")).getCode());
        verifyNoInteractions(config, http);
    }

    @Test
    void credentialToStringDoesNotExposeTheKeyOrEndpoint() {
        VisionCredentials credentials = new VisionCredentials("https://private.provider.example", "vision", SYNTHETIC_KEY);
        assertFalse(credentials.toString().contains(SYNTHETIC_KEY));
        assertFalse(credentials.toString().contains("private.provider"));
    }

    private void success(String extracted) throws Exception {
        JSONObject envelope = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", "stop").put("message", new JSONObject().put("content", extracted))));
        respond(200, bytes(envelope.toString()));
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, InputStream body) throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), inputStreamHandler())).thenReturn(response);
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse.BodyHandler<InputStream> inputStreamHandler() {
        return org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any();
    }

    private static String bodyText(HttpRequest request) throws Exception {
        CompletableFuture<String> collected = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.writeBytes(chunk);
            }
            @Override public void onError(Throwable failure) { collected.completeExceptionally(failure); }
            @Override public void onComplete() { collected.complete(output.toString(StandardCharsets.UTF_8)); }
        });
        return collected.get(1, TimeUnit.SECONDS);
    }
}
