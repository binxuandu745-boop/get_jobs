package com.getjobs.worker.boss;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Only URL parsing and configuration are exercised; no browser or recruitment service is used. */
class BossManualSearchFiltersTest {
    private static final String BASE = "https://www.zhipin.com/web/geek/jobs";
    private static final String INITIAL = BASE + "?city=101010100&query=Java%E5%AE%9E%E4%B9%A0"
            + "&position=100101&jobType=1902&experience=108&salary=404&degree=203"
            + "&scale=301&industry=100001&stage=801";
    private BossConfig config;

    @BeforeEach
    void setUp() {
        config = new BossConfig();
        config.setCityCode(List.of("101020100")); // The next round independently picked Shanghai.
        config.setKeywords(List.of("Java实习", "后端实习"));
        config.setPosition("old-position");
        config.setJobType("old-job-type");
        config.setExperience(List.of("old-experience"));
        config.setSalary(List.of("old-salary"));
        config.setDegree(List.of("old-degree"));
        config.setScale(List.of("old-scale"));
        config.setIndustry(List.of("old-industry"));
        config.setStage(List.of("old-stage"));
    }

    @Test
    void rejectsUnsafeRawCodesBeforeTheyCanBecomeNewUrlParameters() {
        config.setPosition("100101&jobType=1901");
        assertThrows(IllegalArgumentException.class, () -> BossManualSearchFilters.validateInitialConfig(config));
        config.setPosition("100101");
        config.setCityCode(List.of("101010100#fragment"));
        assertThrows(IllegalArgumentException.class, () -> BossManualSearchFilters.validateInitialConfig(config));
        config.setCityCode(List.of("101010100"));
        config.setExperience(List.of("108&salary=405"));
        assertThrows(IllegalArgumentException.class, () -> BossManualSearchFilters.validateInitialConfig(config));
    }

    @Test
    void initialConfigAllowsSafeAndUnlimitedCodesAndArbitraryEncodedKeywords() {
        config.setPosition("100101,100102");
        config.setJobType("0");
        config.setExperience(List.of("108", "0"));
        config.setKeywords(List.of("Java 后端&实习"));
        assertDoesNotThrow(() -> BossManualSearchFilters.validateInitialConfig(config));
    }

    @Test
    void emptyConfiguredKeywordsRemainASearchableEmptyKeywordInEachFreshRound() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(BASE, BASE);
        config.setKeywords(null);
        filters.applyTo(config);
        assertEquals(List.of(""), config.getKeywords());
        config.setKeywords(List.of(" "));
        filters.applyTo(config);
        assertEquals(List.of(""), config.getKeywords());
    }

    @Test
    void absentCityRemainsAnUnlimitedSearchInEachFreshRound() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(BASE, BASE);
        config.setCityCode(null);
        filters.applyTo(config);
        assertEquals(List.of("0"), config.getCityCode());
        config.setCityCode(List.of());
        filters.applyTo(config);
        assertEquals(List.of("0"), config.getCityCode());
    }

    @Test
    void missingEntriesDoNotChangeTheFirstValidCityOrBreakLaterKeywords() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(BASE, BASE);
        config.setCityCode(java.util.Arrays.asList(null, "", "101010100"));
        config.setKeywords(java.util.Arrays.asList(null, "Java"));
        filters.applyTo(config);
        assertEquals(List.of("101010100"), config.getCityCode());
        assertEquals(List.of("", "Java"), config.getKeywords());
    }

    @Test
    void capturesEditedRoleAndAllSupportedFiltersWhileKeepingUnchangedCityAndKeywords() {
        String selected = BASE + "?query=Java%E5%AE%9E%E4%B9%A0&city=101010100"
                + "&position=100102&jobType=1901&experience=109,108&salary=405,404"
                + "&degree=204&scale=302&industry=100002&stage=802&ka=tracking";
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL, selected);

        filters.applyTo(config);

        assertEquals("100102", config.getPosition());
        assertEquals("1901", config.getJobType());
        assertEquals(List.of("108", "109"), config.getExperience());
        assertEquals(List.of("404", "405"), config.getSalary());
        assertEquals(List.of("204"), config.getDegree());
        assertEquals(List.of("302"), config.getScale());
        assertEquals(List.of("100002"), config.getIndustry());
        assertEquals(List.of("802"), config.getStage());
        assertEquals(List.of("101020100"), config.getCityCode());
        assertEquals(List.of("Java实习", "后端实习"), config.getKeywords());
        assertFalse(filters.cityChanged());
    }

    @Test
    void omittedEmptyAndUnlimitedSelectionsClearOldFilterValues() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL,
                BASE + "?city=101010100&query=Java%E5%AE%9E%E4%B9%A0&position=0&jobType=0"
                        + "&experience=108,0&salary=&degree=0&scale=0&industry=0&stage=0");

        filters.applyTo(config);

        assertEquals("", config.getPosition());
        assertEquals("", config.getJobType());
        assertEquals(List.of(), config.getExperience());
        assertEquals(List.of(), config.getSalary());
        assertEquals(List.of(), config.getDegree());
        assertEquals(List.of(), config.getScale());
        assertEquals(List.of(), config.getIndustry());
        assertEquals(List.of(), config.getStage());
    }

    @Test
    void manuallyChangedCityOverridesEachRoundCityWithoutModifyingOtherConfig() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL,
                BASE + "?city=101280600&query=Java%E5%AE%9E%E4%B9%A0");
        assertTrue(filters.cityChanged());
        assertEquals("101280600", filters.selectedCity());

        filters.applyTo(config);
        assertEquals(List.of("101280600"), config.getCityCode());
        config.setCityCode(List.of("101270100"));
        filters.applyTo(config);
        assertEquals(List.of("101280600"), config.getCityCode());
    }

    @Test
    void changedCityToUnlimitedUsesExistingZeroCityConvention() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL,
                BASE + "?city=0&query=Java%E5%AE%9E%E4%B9%A0");

        filters.applyTo(config);

        assertTrue(filters.cityChanged());
        assertEquals("", filters.selectedCity());
        assertEquals(List.of("0"), config.getCityCode());
        assertFalse(Boss.buildSearchUrl(config, config.getCityCode().getFirst()).contains("city="));
    }

    @Test
    void changedMultiCitySelectionPreservesEachCanonicalCity() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL,
                BASE + "?city=101280600%2C101020100&query=Java%E5%AE%9E%E4%B9%A0");

        filters.applyTo(config);

        assertEquals(List.of("101020100", "101280600"), config.getCityCode());
    }

    @Test
    void manuallyChangedQueryBecomesTheOnlyKeywordAndRoundTripPreservesAllFilters() {
        String selected = BASE + "?city=101280600&query=Java+%E5%90%8E%E7%AB%AF%26%E4%BA%91"
                + "&position=100102&jobType=1902&salary=404%2C405&experience=108";
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL, selected);

        filters.applyTo(config);

        assertEquals(List.of("Java 后端&云"), config.getKeywords());
        String actualUrl = Boss.buildSearchUrl(config, config.getCityCode().getFirst())
                + "&query=" + URLEncoder.encode(config.getKeywords().getFirst(), StandardCharsets.UTF_8);
        assertTrue(filters.matchesSelectedUrl(actualUrl));
    }

    @Test
    void manuallyClearedQueryBecomesOneEmptyKeywordRatherThanRestoringOldKeywords() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL,
                BASE + "?city=101010100&query=");

        filters.applyTo(config);

        assertEquals(List.of(""), config.getKeywords());
    }

    @Test
    void equivalentEncodedValuesAndTrackingDoNotCountAsUserChanges() {
        String selected = "https://zhipin.com/web/geek/jobs/?degree=203&salary=404&jobType=1902"
                + "&city=101010100&query=Java%E5%AE%9E%E4%B9%A0&position=100101"
                + "&experience=108&scale=301&industry=100001&stage=801&ka=unrelated";
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL, selected);

        filters.applyTo(config);

        assertTrue(filters.matchesSelectedUrl(INITIAL + "&randomTracking=x"));
        assertFalse(filters.cityChanged());
        assertEquals(List.of("101020100"), config.getCityCode());
        assertEquals(List.of("Java实习", "后端实习"), config.getKeywords());
        assertFalse(filters.matchesSelectedUrl(BASE + "?city=101010100&query=Python"));
    }

    @Test
    void snapshotIsUnaffectedByLaterConfigurationChanges() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL,
                BASE + "?city=101010100&query=Java%E5%AE%9E%E4%B9%A0&jobType=1903");

        filters.applyTo(config);
        config.setJobType("another-value");
        filters.applyTo(config);

        assertEquals("1903", config.getJobType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://www.zhipin.com/web/geek/jobs", "https://example.com/web/geek/jobs",
            "https://www.zhipin.com/web/geek/chat", "https://www.zhipin.com/web/geek/jobs?query=%broken",
            "https://www.zhipin.com/web/geek/jobs?jobType=1901&jobType=1902"})
    void invalidOfficialPageAndConflictingFiltersCannotProduceSnapshot(String url) {
        assertThrows(IllegalArgumentException.class, () -> BossManualSearchFilters.capture(INITIAL, url));
        assertThrows(IllegalArgumentException.class, () -> BossManualSearchFilters.capture(url, INITIAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"position=100101%26jobType%3D1901", "salary=404%23x", "city=101%3Fquery%3Dx",
            "experience=%31%3D%32", "jobType=%25broken", "degree=%E6%9C%AC%E7%A7%91"})
    void unescapedOrNonCodeFilterValuesCannotBeReintroducedIntoSearchUrl(String parameter) {
        assertThrows(IllegalArgumentException.class,
                () -> BossManualSearchFilters.capture(INITIAL, BASE + "?" + parameter));
    }

    @Test
    void invalidOrNullCurrentUrlsCannotMatchTheStoredSelection() {
        BossManualSearchFilters filters = BossManualSearchFilters.capture(INITIAL, INITIAL);
        assertFalse(filters.matchesSelectedUrl(null));
        assertFalse(filters.matchesSelectedUrl("https://example.com/web/geek/jobs"));
        assertFalse(filters.matchesSelectedUrl(BASE + "?jobType=1901&jobType=1902"));
        assertThrows(IllegalArgumentException.class, () -> filters.applyTo(null));
    }
}
