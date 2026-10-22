package com.getjobs.application.screenshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local test files and mock inference only; no paid request, browser or delivery services. */
class OfflineScreenshotServiceTest {
    private static final String VALID = """
            {"jobTitle":"Java开发实习生","company":null,"city":"天津", "salaryText":"150-200元/天",
             "salaryMin":150,"salaryMax":200,"salaryUnit":"DAY","daysPerWeek":4,"durationMonths":3,
             "education":"本科","skills":["Java","MySQL"],"graduationYears":[],
             "description":"参与Java后端开发","uncertainFields":[]}
            """;
    @TempDir Path temp;
    private VisionModelClient client;
    private OfflineScreenshotService service;

    @BeforeEach
    void setUp() {
        client = mock(VisionModelClient.class);
        ScreenshotVisionConfigService config = mock(ScreenshotVisionConfigService.class);
        when(config.publicConfig()).thenReturn(new ScreenshotVisionConfigService.PublicConfig(
                "https://api.example", "test-vision", true, "SCREENSHOT", "SCREENSHOT", true));
        service = new OfflineScreenshotService(client, config, new ScreenshotResultStore(temp, new ObjectMapper()));
    }

    @Test
    void validScreenshotGetsParsedEvaluatedAndStoredWithTheUnmodifiedSource() throws Exception {
        byte[] image = png();
        when(client.recognize(any(byte[].class), eq("image/png"))).thenReturn(VALID);
        var result = service.analyze(image, "C:\\private\\job.png", ScreenshotRules.defaults());
        assertEquals("COMPLETE", result.status());
        assertEquals("MATCH", result.evaluation().decision()); // Missing year passes by the user's screening policy.
        assertTrue(result.evaluation().warnings().stream().anyMatch(warning -> warning.contains("毕业届次")));
        assertEquals("Java开发实习生", result.fields().jobTitle());
        assertEquals("job.png", result.sourceName());
        assertArrayEquals(image, Files.readAllBytes(temp.resolve(result.id()).resolve("source.png")));
        assertEquals(result, service.result(result.id()));
        verify(client, times(1)).recognize(any(byte[].class), eq("image/png"));
    }

    @Test
    void invalidJsonIsSavedAsAnErrorRatherThanBeingTreatedAsAMatch() throws Exception {
        when(client.recognize(any(byte[].class), anyString())).thenReturn("not JSON, provider secret");
        var result = service.analyze(png(), "job.png", ScreenshotRules.defaults());
        assertEquals("ERROR", result.status());
        assertEquals("MISMATCH", result.evaluation().decision());
        assertEquals("INVALID_EXTRACTION", result.errorCode());
        assertNull(result.fields());
        assertTrue(result.errorMessage().contains("未通过字段校验"));
        assertFalse(result.errorMessage().contains("not JSON"));
        String saved = Files.readString(temp.resolve(result.id()).resolve("result.json"));
        assertFalse(saved.contains("provider secret"));
    }

    @Test
    void providerFailureIsRecordedAndIsNotAutomaticallyRetried() throws Exception {
        when(client.recognize(any(byte[].class), anyString()))
                .thenThrow(new ScreenshotVisionException("AUTH_ERROR", "请检查截图识别API Key"));
        var result = service.analyze(png(), "job.png", ScreenshotRules.defaults());
        assertEquals("AUTH_ERROR", result.errorCode());
        assertEquals("MISMATCH", result.evaluation().decision());
        assertEquals("ERROR", service.result(result.id()).status());
        verify(client, times(1)).recognize(any(byte[].class), anyString());
    }

    @Test
    void completelyUnrecognizableImageResultIsSavedAsNotMatching() throws Exception {
        when(client.recognize(any(byte[].class), anyString())).thenReturn("{}");
        var result = service.analyze(png(), "blank.png", ScreenshotRules.defaults());
        assertEquals("COMPLETE", result.status());
        assertEquals("MISMATCH", result.evaluation().decision());
        assertEquals(result, service.result(result.id()));
        assertNull(result.fields().jobTitle());
    }

    @Test
    void partiallyRecognizedJobPassesWithoutInventingMissingSalaryOrYear() throws Exception {
        when(client.recognize(any(byte[].class), anyString())).thenReturn("{\"jobTitle\":\"Java开发实习生\"}");
        var result = service.analyze(png(), "partial.png", ScreenshotRules.defaults());
        assertEquals("MATCH", result.evaluation().decision());
        assertNull(result.fields().salaryMin());
        assertTrue(result.fields().graduationYears().isEmpty());
        assertFalse(result.evaluation().warnings().isEmpty());
        assertEquals(result, service.result(result.id()));
    }

    @Test
    void malformedExtractedFieldIsAnErrorAndNotMatchingRatherThanMissingAndMatching() throws Exception {
        when(client.recognize(any(byte[].class), anyString()))
                .thenReturn("{\"jobTitle\":\"Java实习生\",\"salaryMin\":\"not-a-number\"}");
        var result = service.analyze(png(), "invalid.png", ScreenshotRules.defaults());
        assertEquals("ERROR", result.status());
        assertEquals("MISMATCH", result.evaluation().decision());
        assertEquals("INVALID_EXTRACTION", result.errorCode());
        assertNull(result.fields());
        assertTrue(result.errorMessage().contains("salaryMin"));
    }

    @Test
    void disguisedTextEmptyAndOversizedFilesNeverReachAI() {
        assertThrows(IllegalArgumentException.class, () -> service.analyze(
                "<html>not a png</html>".getBytes(StandardCharsets.UTF_8), "fake.png", null));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(new byte[0], "empty.png", null));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(
                new byte[OfflineScreenshotService.MAX_IMAGE_BYTES + 1], "huge.png", null));
        verifyNoInteractions(client);
    }

    @Test
    void resultLookupRejectsPathsInsteadOfReadingArbitraryFiles() {
        assertThrows(IllegalArgumentException.class, () -> service.result("../../db/getjobs.db"));
        assertThrows(IllegalArgumentException.class, () -> service.result("1-1-1-1-1"));
    }

    @Test
    void consecutiveScreenshotsUseOneJointRequestAndSaveEveryOriginalInOrder() throws Exception {
        byte[] top = png();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpeg", output);
        byte[] bottom = output.toByteArray();
        String combined = VALID.replace("\"graduationYears\":[]", "\"graduationYears\":[2027]")
                .replace("参与Java后端开发", "参与Java后端开发；补充要求：只接受2027届");
        when(client.recognize(anyList())).thenReturn(combined);

        var result = service.analyze(List.of(
                new OfflineScreenshotService.Upload(top, "C:\\private\\top.png"),
                new OfflineScreenshotService.Upload(bottom, "folder/bottom.jpg")), ScreenshotRules.defaults());

        assertEquals("COMPLETE", result.status());
        assertEquals("MISMATCH", result.evaluation().decision());
        assertEquals(List.of(2027), result.fields().graduationYears());
        assertTrue(result.fields().description().contains("补充要求"));
        assertEquals(2, result.imageCount());
        assertEquals("top.png", result.sourceName());
        assertEquals(List.of("top.png", "bottom.jpg"), result.sources().stream().map(ScreenshotSource::sourceName).toList());
        assertEquals(List.of(1, 2), result.sources().stream().map(ScreenshotSource::index).toList());
        assertNotEquals(result.sources().getFirst().imageSha256(), result.sources().getLast().imageSha256());
        assertArrayEquals(top, Files.readAllBytes(temp.resolve(result.id()).resolve("source.png")));
        assertArrayEquals(bottom, Files.readAllBytes(temp.resolve(result.id()).resolve("source-2.jpg")));
        assertEquals(result, service.result(result.id()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VisionImage>> images = ArgumentCaptor.forClass(List.class);
        verify(client).recognize(images.capture());
        assertEquals("image/png", images.getValue().getFirst().mimeType());
        assertEquals("image/jpeg", images.getValue().getLast().mimeType());
        assertArrayEquals(top, images.getValue().getFirst().bytes());
        assertArrayEquals(bottom, images.getValue().getLast().bytes());
        verifyNoMoreInteractions(client);
    }

    @Test
    void unreadableImageAnywhereInSetStopsBeforeModelAndDoesNotSavePartialSuccess() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.analyze(List.of(
                new OfflineScreenshotService.Upload(png(), "top.png"),
                new OfflineScreenshotService.Upload("broken".getBytes(StandardCharsets.UTF_8), "bottom.png")), null));
        verifyNoInteractions(client);
        try (var entries = Files.list(temp)) { assertEquals(0, entries.count()); }
    }

    @Test
    void multiImageFailureRetainsAllProvenanceAndDoesNotRetryOrFallBackToSingleImages() throws Exception {
        byte[] image = png();
        when(client.recognize(anyList())).thenThrow(new ScreenshotVisionException("API_ERROR", "模型调用失败"));
        var result = service.analyze(List.of(new OfflineScreenshotService.Upload(image, "top.png"),
                new OfflineScreenshotService.Upload(image, "bottom.png")), null);
        assertEquals("ERROR", result.status());
        assertEquals("MISMATCH", result.evaluation().decision());
        assertEquals(2, result.imageCount());
        assertNull(result.fields());
        assertArrayEquals(image, Files.readAllBytes(temp.resolve(result.id()).resolve("source-2.png")));
        assertEquals(result, service.result(result.id()));
        verify(client).recognize(anyList());
        verifyNoMoreInteractions(client);
    }

    @Test
    void setBoundsRejectMissingTooManyAndTooLargeInputsBeforeDecodingOrModelUse() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.analyze((List<OfflineScreenshotService.Upload>) null, null));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(List.of(), null));
        var upload = new OfflineScreenshotService.Upload(png(), "job.png");
        assertThrows(IllegalArgumentException.class, () -> service.analyze(java.util.Collections.nCopies(7, upload), null));
        var eightMiB = new OfflineScreenshotService.Upload(new byte[OfflineScreenshotService.MAX_IMAGE_BYTES], "large.png");
        assertThrows(IllegalArgumentException.class, () -> service.analyze(java.util.Collections.nCopies(4, eightMiB), null));
        verifyNoInteractions(client);
    }

    @Test
    void legacySingleImageJsonCanBeReadWithNormalizedSourceMetadata() throws Exception {
        when(client.recognize(any(byte[].class), anyString())).thenReturn(VALID);
        var result = service.analyze(png(), "job.png", null);
        ObjectMapper mapper = new ObjectMapper();
        var legacy = mapper.valueToTree(result);
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove(List.of("sources", "imageCount"));
        mapper.writeValue(temp.resolve(result.id()).resolve("result.json").toFile(), legacy);
        var reread = service.result(result.id());
        assertEquals(1, reread.imageCount());
        assertEquals(List.of(new ScreenshotSource(1, result.sourceName(), result.imageSha256())), reread.sources());
    }

    private byte[] png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
