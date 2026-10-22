package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Service lifecycle checks with mocks only: no browser, database, or messages are used. */
class BossJobServiceCityTest {
    private static final String BEIJING = "101010100";
    private static final String SHANGHAI = "101020100";
    private static final String GUANGZHOU = "101280100";
    private static final List<String> CITY_POOL = List.of(BEIJING, SHANGHAI, GUANGZHOU);

    private PlaywrightManager manager;
    private ConfigService configService;
    private ObjectProvider<Boss> provider;
    private Page page;
    private Boss boss;
    private BossPauseNotifier notifier;
    private BossJobService service;
    private List<JobProgressMessage> messages;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(PlaywrightManager.class);
        configService = mock(ConfigService.class);
        provider = mock(ObjectProvider.class);
        page = mock(Page.class);
        boss = mock(Boss.class);
        notifier = mock(BossPauseNotifier.class);
        service = spy(new BossJobService(manager, configService, provider, notifier));
        messages = new ArrayList<>();
        ReflectionTestUtils.setField(service, "randomCityPool", CITY_POOL);
        doReturn(0).when(service).randomCityIndex(anyInt());
    }

    @Test
    void disabledRandomCityKeepsConfiguredCitiesWithoutDrawing() {
        ReflectionTestUtils.setField(service, "randomCityEnabled", false);
        BossConfig config = configuredSearch();
        List<String> configuredCities = config.getCityCode();

        service.applyRandomCity(config);

        assertSame(configuredCities, config.getCityCode());
        verify(service, never()).randomCityIndex(anyInt());
        verifyNoInteractions(manager, configService, provider, page, boss);
    }

    @Test
    void deterministicChoiceKeepsKeywordsExperienceSalaryAndSearchUrlFilters() {
        ReflectionTestUtils.setField(service, "randomCityEnabled", true);
        BossConfig config = configuredSearch();
        List<String> keywords = config.getKeywords();
        List<String> experience = config.getExperience();
        List<String> salary = config.getSalary();

        service.applyRandomCity(config);

        assertEquals(List.of(BEIJING), config.getCityCode());
        assertSame(keywords, config.getKeywords());
        assertSame(experience, config.getExperience());
        assertSame(salary, config.getSalary());
        assertEquals("1901", config.getJobType());
        assertEquals("https://www.zhipin.com/web/geek/jobs?city=" + BEIJING
                        + "&jobType=1901&salary=405&experience=104,105&degree=203",
                Boss.buildSearchUrl(config, config.getCityCode().get(0)));
        verify(service, times(1)).randomCityIndex(CITY_POOL.size());
        verifyNoInteractions(manager, configService, provider, page, boss);
    }

    @Test
    void internship1902SurvivesRandomCitySelectionAndIsReportedForTheActualTask() {
        ReflectionTestUtils.setField(service, "randomCityEnabled", true);
        BossConfig config = configuredSearch();
        config.setJobType("1902");
        List<String> keywords = config.getKeywords();
        List<String> experience = config.getExperience();
        List<String> salary = config.getSalary();
        when(configService.getBossConfig()).thenReturn(config);
        when(manager.getBossPage()).thenReturn(page);
        when(manager.refreshBossLoginStatus()).thenReturn(true);
        when(provider.getObject()).thenReturn(boss);
        when(manager.callOnPlaywright(any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(0)).call());

        service.executeDelivery(messages::add);

        assertEquals(List.of(BEIJING), config.getCityCode());
        assertEquals("1902", config.getJobType());
        assertSame(keywords, config.getKeywords());
        assertSame(experience, config.getExperience());
        assertSame(salary, config.getSalary());
        assertEquals("https://www.zhipin.com/web/geek/jobs?city=" + BEIJING
                        + "&jobType=1902&salary=405&experience=104,105&degree=203",
                Boss.buildSearchUrl(config, config.getCityCode().getFirst()));
        assertTrue(messages.stream().anyMatch(message -> message.getMessage().contains("本轮工作类型：实习")));
        verify(boss).setConfig(same(config));
        verify(boss).execute();
        verify(service, times(1)).randomCityIndex(CITY_POOL.size());
        verify(configService, times(1)).getBossConfig();
        verifyNoMoreInteractions(configService);
        verifyNoInteractions(page, notifier);
    }

    @Test
    void consecutiveExplicitRunsChooseDifferentCitiesAndDrawOnlyOncePerRun() {
        ReflectionTestUtils.setField(service, "randomCityEnabled", true);
        BossConfig firstConfig = configuredSearch();
        BossConfig secondConfig = configuredSearch();
        when(configService.getBossConfig()).thenReturn(firstConfig, secondConfig);
        when(manager.getBossPage()).thenReturn(page);
        when(manager.refreshBossLoginStatus()).thenReturn(true);
        when(provider.getObject()).thenReturn(boss);
        when(manager.callOnPlaywright(any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(0)).call());

        service.executeDelivery(messages::add);

        String firstCity = firstConfig.getCityCode().get(0);
        assertEquals(List.of(BEIJING), firstConfig.getCityCode());
        assertEquals(firstCity, service.getStatus().get("cityCode"));
        assertEquals("北京", service.getStatus().get("cityName"));
        assertEquals(true, service.getStatus().get("randomCityEnabled"));
        verify(service, times(1)).applyRandomCity(firstConfig);
        verify(service, times(1)).randomCityIndex(anyInt());

        service.executeDelivery(messages::add);

        assertEquals(1, secondConfig.getCityCode().size());
        String secondCity = secondConfig.getCityCode().get(0);
        assertNotEquals(firstCity, secondCity);
        assertTrue(CITY_POOL.contains(secondCity));
        assertEquals(secondCity, service.getStatus().get("cityCode"));
        assertFalse(service.getStatus().get("cityName").toString().isBlank());
        verify(service, times(1)).applyRandomCity(secondConfig);
        verify(service, times(2)).randomCityIndex(anyInt());
        verify(configService, times(2)).getBossConfig();
        verify(boss).setConfig(firstConfig);
        verify(boss).setConfig(secondConfig);
        verify(boss, times(2)).execute();
        verify(manager, times(2)).callOnPlaywright(any());
        verify(manager, times(2)).resumeBossMonitoring();
        assertFalse(service.isRunning());
        assertEquals(2, messages.stream().filter(message -> "success".equals(message.getType())).count());
        verifyNoInteractions(page, notifier);
    }

    @ParameterizedTest
    @MethodSource("invalidCityPools")
    void emptyOrInvalidPoolFailsWithoutBecomingAnUnlimitedSearch(List<String> invalidPool) {
        ReflectionTestUtils.setField(service, "randomCityEnabled", true);
        ReflectionTestUtils.setField(service, "randomCityPool", invalidPool);
        BossConfig config = configuredSearch();
        List<String> configuredCities = config.getCityCode();

        assertThrows(IllegalArgumentException.class, () -> service.applyRandomCity(config));

        assertSame(configuredCities, config.getCityCode());
        assertFalse(config.getCityCode().contains("0"));
        verify(service, never()).randomCityIndex(anyInt());
        verifyNoInteractions(manager, configService, provider, page, boss);
    }

    private static Stream<List<String>> invalidCityPools() {
        return Stream.of(
                List.<String>of(),
                List.of("0"),
                List.of(""),
                List.of(" "),
                List.of("999999999"),
                List.of(BEIJING, "0"),
                List.of(BEIJING, "999999999")
        );
    }

    private static BossConfig configuredSearch() {
        BossConfig config = new BossConfig();
        config.setCityCode(List.of(SHANGHAI, GUANGZHOU));
        config.setKeywords(List.of("Java", "后端", "服务端"));
        config.setExperience(List.of("104", "105"));
        config.setSalary(List.of("405"));
        config.setDegree(List.of("203"));
        config.setJobType("1901");
        config.setDebugger(false);
        return config;
    }
}
