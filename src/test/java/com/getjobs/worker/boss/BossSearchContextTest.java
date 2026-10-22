package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class BossSearchContextTest {
    private static final String BASE = "https://www.zhipin.com/web/geek/jobs";

    @Test
    void queryOrderTrackingIdsAndOfficialHostAliasDoNotChangeSearch() {
        var context = BossSearchContext.fromUrl(BASE + "?city=101&jobType=known-type&query=Java+%E5%90%8E%E7%AB%AF&degree=3,2,3");

        assertTrue(context.matches("https://zhipin.com/web/geek/jobs/?degree=2%2C3&query=+Java%20%E5%90%8E%E7%AB%AF+&jobType=known-type&city=101&ka=trace&encryptId=other"));
    }

    @Test
    void absentEmptyAndZeroFiltersAreEquivalentIncludingCsvUnlimited() {
        var context = BossSearchContext.fromUrl(BASE);

        assertTrue(context.matches(BASE + "?city=0&jobType=0&salary=&degree=2,0&experience=0,0&stage="));
    }

    @Test
    void allKnownMultiselectFiltersAreSortedAndDeduplicated() {
        var context = BossSearchContext.fromUrl(BASE + "?salary=b,a,a&experience=2,1&degree=4,3&scale=6,5&industry=8,7&stage=y,x");

        assertTrue(context.matches(BASE + "?stage=x,y&industry=7,8&scale=5,6&degree=3,4&experience=1,2&salary=a,b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"city=102", "jobType=another-type", "query=Python", "experience=2", "salary=2",
            "degree=2", "scale=2", "industry=2", "stage=2"})
    void everyMaterialFilterChangeIsDetected(String change) {
        assertFalse(BossSearchContext.fromUrl(BASE + "?city=101&jobType=known-type&query=Java")
                .matches(BASE + "?city=101&jobType=known-type&query=Java&" + change));
    }

    @Test
    void duplicateEquivalentParametersAreAcceptedButConflictingOnesAreInvalid() {
        assertTrue(BossSearchContext.fromUrl(BASE + "?degree=2,3").matches(BASE + "?degree=3,2&degree=2,3"));
        assertFalse(BossSearchContext.fromUrl(BASE).matches(BASE + "?jobType=a&jobType=b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a url", "http://www.zhipin.com/web/geek/jobs", "https://evil.example/web/geek/jobs",
            "https://www.zhipin.com.evil.example/web/geek/jobs", "https://user@www.zhipin.com/web/geek/jobs",
            "https://www.zhipin.com:444/web/geek/jobs", "https://www.zhipin.com/job_detail/a.html",
            "https://www.zhipin.com/web/geek/chat", "https://www.zhipin.com/web/geek/jobs?query=%broken"})
    void invalidOrUnrelatedUrlsNeverMatch(String url) {
        assertFalse(BossSearchContext.fromUrl(BASE).matches(url));
        assertThrows(IllegalArgumentException.class, () -> BossSearchContext.fromUrl(url));
    }

    @Test
    void nullIsInvalidAndAZeroSearchTermIsStillATerm() {
        assertFalse(BossSearchContext.fromUrl(BASE).matches(null));
        assertThrows(IllegalArgumentException.class, () -> BossSearchContext.fromUrl(null));
        assertFalse(BossSearchContext.fromUrl(BASE).matches(BASE + "?query=0"));
    }
}
