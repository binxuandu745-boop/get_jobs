package com.getjobs.application.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.Job51Service;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.Job51JobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Offline SSE contract tests: no database, browser, task, credential, or Spring application is opened. */
class JobControllerSsePlatformTest {
    private static final String LOGIN_STREAM = "/api/jobs/login-status/stream";
    private static final ObjectMapper JSON = new ObjectMapper();

    private PlaywrightManager manager;
    private JobController controller;
    private MockMvc mvc;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;
    private final List<MvcResult> subscriptions = new ArrayList<>();
    private final List<Consumer<PlaywrightManager.LoginStatusChange>> listeners = new ArrayList<>();

    @BeforeEach
    void setUp() {
        manager = mock(PlaywrightManager.class);
        controller = new JobController(mock(Job51Service.class), mock(Job51JobService.class),
                manager, mock(CookieService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(JSON))
                .build();

        when(manager.isLoggedIn("boss")).thenReturn(true);
        when(manager.isLoggedIn("liepin")).thenReturn(false);
        when(manager.isLoggedIn("51job")).thenReturn(true);
        when(manager.isLoggedIn("zhilian")).thenReturn(false);
        when(manager.getBossLoginState()).thenReturn("LOGGED_IN");
        when(manager.getBossLoginMessage()).thenReturn("已登录");
        when(manager.getLiepinLoginState()).thenReturn("UNKNOWN");
        when(manager.getLiepinLoginMessage()).thenReturn("尚未确认登录");
        doAnswer(invocation -> {
            listeners.add(invocation.getArgument(0));
            return null;
        }).when(manager).addLoginStatusListener(any());

        logger = (Logger) LoggerFactory.getLogger(JobController.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        for (MvcResult subscription : subscriptions) {
            if (subscription.getRequest().isAsyncStarted()) {
                ((MockAsyncContext) subscription.getRequest().getAsyncContext()).complete();
            }
        }
        logger.detachAppender(logs);
        logs.stop();
    }

    @ParameterizedTest
    @CsvSource({"boss,BOSS", "liepin,猎聘", "51job,51job", "zhilian,智联招聘"})
    void completionLogUsesTheSubscribedPlatformRatherThanTheCurrentBrowserMode(
            String platform, String label) throws Exception {
        when(manager.getBrowserPlatformMode()).thenReturn("boss");
        MvcResult subscription = subscribe(platform);
        assertInitialStatusUnchanged(subscription, label);

        // A browser-mode change after subscription must not relabel an old page's SSE connection.
        when(manager.getBrowserPlatformMode()).thenReturn("liepin");
        complete(subscription);

        assertTrue(messages().stream().anyMatch(message -> message.startsWith("[" + label + "]")
                && message.contains("登录状态") && message.contains("连接已关闭")), messages().toString());
        assertFalse(messages().stream().anyMatch(message -> message.contains("连接已完成")));
        verify(manager, times(1)).addLoginStatusListener(any());
        assertNoBrowserOrDeliveryStarted();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void olderClientsWithoutAPlatformKeepTheSharedLabel(String platform) throws Exception {
        MvcResult subscription = subscribe(platform);
        assertInitialStatusUnchanged(subscription, "共享");
        complete(subscription);

        assertTrue(messages().stream().anyMatch(message -> message.startsWith("[共享]")
                && message.contains("登录状态") && message.contains("连接已关闭")), messages().toString());
        assertNoBrowserOrDeliveryStarted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "job51", "unknown-platform", "sk-not-a-real-key"})
    void invalidPlatformIsRejectedWithoutSubscriptionOrBrowserOperations(String invalid) throws Exception {
        MvcResult result = mvc.perform(get(LOGIN_STREAM).param("platform", invalid))
                .andExpect(status().isBadRequest())
                .andExpect(request().asyncNotStarted())
                .andReturn();

        assertFalse(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(invalid));
        assertFalse(messages().stream().anyMatch(message -> message.contains(invalid)));
        assertTrue(listeners.isEmpty());
        verifyNoInteractions(manager);
    }

    @Test
    void multipleSubscribersRegisterOneListenerAndKeepBroadcastPayloadAndHeartbeats() throws Exception {
        MvcResult boss = subscribe("boss");
        MvcResult job51 = subscribe("51job");
        MvcResult legacy = subscribe(null);
        verify(manager, times(1)).addLoginStatusListener(any());
        assertEquals(1, listeners.size());

        long timestamp = 123456789L;
        listeners.getFirst().accept(new PlaywrightManager.LoginStatusChange(
                "liepin", true, timestamp, "LOGGED_IN", "已登录"));
        controller.heartbeatLoginStatus();

        assertInitialStatusUnchanged(boss, "BOSS");
        assertInitialStatusUnchanged(job51, "51job");
        assertInitialStatusUnchanged(legacy, "共享");
        for (MvcResult subscription : List.of(boss, job51, legacy)) {
            JsonNode change = eventData(subscription, "login-status");
            assertEquals(5, change.size());
            assertEquals("liepin", change.get("platform").asText());
            assertTrue(change.get("isLoggedIn").asBoolean());
            assertEquals("LOGGED_IN", change.get("state").asText());
            assertEquals("已登录", change.get("message").asText());
            assertEquals(timestamp, change.get("timestamp").asLong());
            assertTrue(body(subscription).contains("event:ping\ndata:keep-alive"));
        }

        complete(boss);
        int closedBossLength = body(boss).length();
        listeners.getFirst().accept(new PlaywrightManager.LoginStatusChange(
                "51job", false, timestamp + 1, "LOGGED_OUT", "未登录"));
        controller.heartbeatLoginStatus();
        assertEquals(closedBossLength, body(boss).length(), "Completed SSE streams must be removed");
        assertEquals(2, countEvent(job51, "login-status"));
        assertEquals(2, countEvent(legacy, "login-status"));
        complete(job51);
        complete(legacy);

        assertEquals(1, messages().stream().filter(message -> message.startsWith("[BOSS]")
                && message.contains("连接已关闭")).count());
        assertEquals(1, messages().stream().filter(message -> message.startsWith("[51job]")
                && message.contains("连接已关闭")).count());
        assertEquals(1, messages().stream().filter(message -> message.startsWith("[共享]")
                && message.contains("连接已关闭")).count());
        assertNoBrowserOrDeliveryStarted();
    }

    @Test
    void job51ProgressCompletionDescribesTheManagementConnectionNotTaskCompletion() throws Exception {
        MvcResult subscription = mvc.perform(get("/api/51job/stream"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        subscriptions.add(subscription);
        assertTrue(body(subscription).contains("event:connected"));
        complete(subscription);

        assertTrue(messages().stream().anyMatch(message -> message.startsWith("[51job]")
                && message.contains("管理页投递进度 SSE 连接已关闭")), messages().toString());
        verifyNoInteractions(manager);
    }

    @Test
    void bossProgressCompletionKeepsBossLabelEvenWhenAnotherPlatformIsSelected() throws Exception {
        BossJobService bossJobs = mock(BossJobService.class);
        CookieService cookies = mock(CookieService.class);
        BossController bossController = new BossController(bossJobs, manager, cookies);
        MockMvc bossMvc = MockMvcBuilders.standaloneSetup(bossController)
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(JSON))
                .build();
        Logger bossLogger = (Logger) LoggerFactory.getLogger(BossController.class);
        ListAppender<ILoggingEvent> bossLogs = new ListAppender<>();
        bossLogs.start();
        bossLogger.addAppender(bossLogs);
        try {
            when(manager.getBrowserPlatformMode()).thenReturn("51job");
            MvcResult subscription = bossMvc.perform(get("/api/boss/stream"))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andReturn();
            subscriptions.add(subscription);
            assertTrue(body(subscription).contains("event:connected"));
            complete(subscription);

            assertTrue(bossLogs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.equals("[BOSS] 管理页投递进度 SSE 连接已关闭")));
            assertFalse(bossLogs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("连接已完成") || message.startsWith("[51job]")));
            verifyNoInteractions(manager, bossJobs, cookies);
        } finally {
            bossLogger.detachAppender(bossLogs);
            bossLogs.stop();
        }
    }

    private MvcResult subscribe(String platform) throws Exception {
        MockHttpServletRequestBuilder request = get(LOGIN_STREAM);
        if (platform != null) request.param("platform", platform);
        MvcResult result = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        subscriptions.add(result);
        return result;
    }

    private static void complete(MvcResult result) {
        ((MockAsyncContext) result.getRequest().getAsyncContext()).complete();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static JsonNode eventData(MvcResult result, String name) throws Exception {
        String prefix = "event:" + name + "\n";
        for (String event : body(result).split("\n\n")) {
            if (!event.startsWith(prefix)) continue;
            StringBuilder data = new StringBuilder();
            for (String line : event.split("\n")) {
                if (!line.startsWith("data:")) continue;
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring("data:".length()));
            }
            return JSON.readTree(data.toString());
        }
        throw new AssertionError("Missing SSE event: " + name);
    }

    private static long countEvent(MvcResult result, String name) throws Exception {
        return body(result).lines().filter(line -> line.equals("event:" + name)).count();
    }

    private static void assertInitialStatusUnchanged(MvcResult result, String label) throws Exception {
        JsonNode connected = eventData(result, "connected");
        assertEquals(9, connected.size());
        assertEquals("已连接到" + label + "登录状态推送", connected.get("message").asText());
        assertTrue(connected.get("bossLoggedIn").asBoolean());
        assertEquals("LOGGED_IN", connected.get("bossLoginState").asText());
        assertEquals("已登录", connected.get("bossLoginMessage").asText());
        assertFalse(connected.get("liepinLoggedIn").asBoolean());
        assertEquals("UNKNOWN", connected.get("liepinLoginState").asText());
        assertEquals("尚未确认登录", connected.get("liepinLoginMessage").asText());
        assertTrue(connected.get("job51LoggedIn").asBoolean());
        assertFalse(connected.get("zhilianLoggedIn").asBoolean());
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertNoBrowserOrDeliveryStarted() {
        verify(manager, never()).init();
        verify(manager, never()).ensureReady();
        verify(manager, never()).reopenBrowser();
        verify(manager, never()).startPlatformTask(anyString(), any(Runnable.class));
    }
}
