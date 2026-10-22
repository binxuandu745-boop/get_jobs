package com.getjobs.worker.manager;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserPlatformModeTest {
    @ParameterizedTest
    @MethodSource("explicitModes")
    void explicitModeOverridesEitherLegacyFlagAfterTrimmingAndIgnoringCase(
            String value, BrowserPlatformMode expected) {
        assertEquals(expected, BrowserPlatformMode.resolve(value, true));
        assertEquals(expected, BrowserPlatformMode.resolve(value, false));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n"})
    void absentModePreservesTheLegacyFlag(String value) {
        assertEquals(BrowserPlatformMode.BOSS, BrowserPlatformMode.resolve(value, true));
        assertEquals(BrowserPlatformMode.ALL, BrowserPlatformMode.resolve(value, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"other", "job51", "boss,liepin", "all-platforms", "/liepin/"})
    void invalidModesFailForEitherLegacyFlag(String value) {
        assertThrows(IllegalArgumentException.class, () -> BrowserPlatformMode.resolve(value, true));
        assertThrows(IllegalArgumentException.class, () -> BrowserPlatformMode.resolve(value, false));
    }

    @ParameterizedTest
    @MethodSource("platformSelections")
    void eachModeEnablesOnlyItsSelectedKnownPlatforms(BrowserPlatformMode mode, String platform,
                                                    boolean expected) {
        assertEquals(expected, mode.enables(platform));
        assertEquals(expected, mode.enables(" " + platform.toUpperCase(Locale.ROOT) + "\t"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "unknown", "job51", "boss,liepin", "all"})
    void unknownPlatformsAreDisabledForEveryMode(String platform) {
        for (BrowserPlatformMode mode : BrowserPlatformMode.values()) {
            assertFalse(mode.enables(platform), mode.name());
        }
    }

    private static Stream<Arguments> explicitModes() {
        return Stream.of(
                Arguments.of("boss", BrowserPlatformMode.BOSS),
                Arguments.of("  BoSs\t", BrowserPlatformMode.BOSS),
                Arguments.of("liepin", BrowserPlatformMode.LIEPIN),
                Arguments.of("\n LiEpIn ", BrowserPlatformMode.LIEPIN),
                Arguments.of("51job", BrowserPlatformMode.JOB51),
                Arguments.of(" \t51JoB\n", BrowserPlatformMode.JOB51),
                Arguments.of("zhilian", BrowserPlatformMode.ZHILIAN),
                Arguments.of("\n ZhIlIaN \t", BrowserPlatformMode.ZHILIAN),
                Arguments.of("all", BrowserPlatformMode.ALL),
                Arguments.of("\tALL  ", BrowserPlatformMode.ALL)
        );
    }

    private static Stream<Arguments> platformSelections() {
        return Stream.of(
                Arguments.of(BrowserPlatformMode.BOSS, "boss", true),
                Arguments.of(BrowserPlatformMode.BOSS, "liepin", false),
                Arguments.of(BrowserPlatformMode.BOSS, "51job", false),
                Arguments.of(BrowserPlatformMode.BOSS, "zhilian", false),
                Arguments.of(BrowserPlatformMode.LIEPIN, "boss", false),
                Arguments.of(BrowserPlatformMode.LIEPIN, "liepin", true),
                Arguments.of(BrowserPlatformMode.LIEPIN, "51job", false),
                Arguments.of(BrowserPlatformMode.LIEPIN, "zhilian", false),
                Arguments.of(BrowserPlatformMode.JOB51, "boss", false),
                Arguments.of(BrowserPlatformMode.JOB51, "liepin", false),
                Arguments.of(BrowserPlatformMode.JOB51, "51job", true),
                Arguments.of(BrowserPlatformMode.JOB51, "zhilian", false),
                Arguments.of(BrowserPlatformMode.ZHILIAN, "boss", false),
                Arguments.of(BrowserPlatformMode.ZHILIAN, "liepin", false),
                Arguments.of(BrowserPlatformMode.ZHILIAN, "51job", false),
                Arguments.of(BrowserPlatformMode.ZHILIAN, "zhilian", true),
                Arguments.of(BrowserPlatformMode.ALL, "boss", true),
                Arguments.of(BrowserPlatformMode.ALL, "liepin", true),
                Arguments.of(BrowserPlatformMode.ALL, "51job", true),
                Arguments.of(BrowserPlatformMode.ALL, "zhilian", true)
        );
    }
}
