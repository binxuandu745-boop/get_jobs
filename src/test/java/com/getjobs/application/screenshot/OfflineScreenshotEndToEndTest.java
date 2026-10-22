package com.getjobs.application.screenshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.controller.OfflineScreenshotController;
import com.getjobs.application.service.ConfigService;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real local HTTP transport to a fake model only. Never starts the app or contacts a provider/site. */
class OfflineScreenshotEndToEndTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"PARTIAL", "UNREADABLE", "INVALID_JSON", "MULTI_PARTIAL", "MULTI_BOTTOM_REJECT"})
    void uploadedPngPassesThroughVisionTransportJavaRulesAndPersistentResult(String scenario) throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JSONObject> observed = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        provider.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            observed.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String extracted = switch (scenario) {
                case "UNREADABLE" -> "{}";
                case "INVALID_JSON" -> "not a JSON object";
                default -> """
                    {"jobTitle":"java开发实习生","company":null,"city":"天津", "salaryText":"150-200元/天",
                     "salaryMin":150,"salaryMax":200,"salaryUnit":"DAY","daysPerWeek":4,"durationMonths":3,
                     "education":"本科","skills":["Java","SpringCloud","MySQL","Redis"],
                     "graduationYears":[],"description":"参与Java后端系统开发","uncertainFields":[]}
                    """;
            };
            if (scenario.equals("MULTI_BOTTOM_REJECT")) {
                extracted = extracted.replace("\"graduationYears\":[]", "\"graduationYears\":[2027]")
                        .replace("参与Java后端系统开发", "参与Java后端系统开发；下屏补充：只招2027届");
            }
            byte[] response = new JSONObject().put("choices", new org.json.JSONArray().put(new JSONObject()
                    .put("finish_reason", "stop").put("message", new JSONObject().put("content", extracted))))
                    .toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();
        try {
            ConfigService repository = mock(ConfigService.class);
            Map<String, String> values = new HashMap<>();
            when(repository.getConfigValue(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
            when(repository.batchUpdateConfigs(anyMap())).thenAnswer(call -> {
                Map<String, String> updates = call.getArgument(0);
                values.putAll(updates);
                return updates.size();
            });
            ScreenshotVisionConfigService config = new ScreenshotVisionConfigService(repository);
            config.save(new ScreenshotVisionConfigService.ConfigUpdate(
                    "http://127.0.0.1:" + provider.getAddress().getPort(), "local-test-vision", "offline-test-key", false));
            ObjectMapper mapper = new ObjectMapper();
            ScreenshotResultStore store = new ScreenshotResultStore(temp, mapper);
            var service = new OfflineScreenshotService(new ScreenshotVisionClient(config), config, store);
            var mvc = MockMvcBuilders.standaloneSetup(new OfflineScreenshotController(service, config, mapper)).build();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", output);
            byte[] image = output.toByteArray();
            output.reset();
            ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpeg", output);
            byte[] bottom = output.toByteArray();
            boolean multiple = scenario.startsWith("MULTI_");
            String expectedStatus = scenario.equals("INVALID_JSON") ? "ERROR" : "COMPLETE";
            String expectedDecision = scenario.equals("PARTIAL") || scenario.equals("MULTI_PARTIAL") ? "MATCH" : "MISMATCH";
            var upload = multipart("/api/offline-screenshots/analyze")
                    .file(new MockMultipartFile(multiple ? "files" : "file", "job.png", "image/png", image));
            if (multiple) upload.file(new MockMultipartFile("files", "bottom.jpg", "image/jpeg", bottom));
            String response = mvc.perform(upload
                            .header("X-Offline-Screenshot", "1").param("consent", "true"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(expectedStatus))
                    .andExpect(jsonPath("$.data.evaluation.decision").value(expectedDecision))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertEquals(1, requests.get());
            JSONObject payload = observed.get();
            assertEquals("local-test-vision", payload.getString("model"));
            String dataUrl = payload.getJSONArray("messages").getJSONObject(1)
                    .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url");
            assertArrayEquals(image, java.util.Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1)));
            var content = payload.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
            assertEquals(multiple ? 4 : 2, content.length());
            if (multiple) {
                String second = content.getJSONObject(3).getJSONObject("image_url").getString("url");
                assertArrayEquals(bottom, java.util.Base64.getDecoder().decode(second.substring(second.indexOf(',') + 1)));
                assertTrue(second.startsWith("data:image/jpeg;base64,"));
            }
            String id = new JSONObject(response).getJSONObject("data").getString("id");
            var saved = service.result(id);
            assertEquals(expectedStatus, saved.status());
            assertEquals(expectedDecision, saved.evaluation().decision());
            assertEquals(multiple ? 2 : 1, saved.imageCount());
            JSONObject data = new JSONObject(response).getJSONObject("data");
            assertEquals(multiple ? 2 : 1, data.getInt("imageCount"));
            assertEquals(saved.imageCount(), data.getJSONArray("sources").length());
            if (scenario.equals("PARTIAL") || scenario.equals("MULTI_PARTIAL")) {
                assertEquals("DAY", saved.fields().salaryUnit());
                assertEquals(150, saved.fields().salaryMin().intValueExact());
                assertTrue(saved.evaluation().warnings().stream().anyMatch(warning -> warning.contains("毕业届次")));
            } else if (scenario.equals("MULTI_BOTTOM_REJECT")) {
                assertEquals(java.util.List.of(2027), saved.fields().graduationYears());
                assertTrue(saved.fields().description().contains("下屏补充"));
            } else if (scenario.equals("INVALID_JSON")) {
                assertNull(saved.fields());
                assertEquals("INVALID_EXTRACTION", saved.errorCode());
            } else {
                assertNull(saved.fields().jobTitle());
                assertTrue(saved.evaluation().reasons().stream().anyMatch(reason -> reason.contains("无法识别")));
            }
            assertArrayEquals(image, Files.readAllBytes(temp.resolve(id).resolve("source.png")));
            if (multiple) {
                assertArrayEquals(bottom, Files.readAllBytes(temp.resolve(id).resolve("source-2.jpg")));
                assertEquals("bottom.jpg", saved.sources().getLast().sourceName());
            }
            assertFalse(Files.readString(temp.resolve(id).resolve("result.json")).contains("offline-test-key"));
            verify(repository, never()).getConfigValue("API_KEY");
        } finally {
            provider.stop(0);
        }
    }
}
