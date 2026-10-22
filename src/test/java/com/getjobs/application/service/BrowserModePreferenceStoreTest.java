package com.getjobs.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Uses a mock configuration service and never opens a database or application context. */
class BrowserModePreferenceStoreTest {
    private static final String CONFIG_KEY = "BROWSER_PLATFORM_MODE";
    private static final String SECRET = "API_KEY=private-key; jdbc:sqlite:private-user.db";

    private ConfigService configService;
    private BrowserModePreferenceStore store;

    @BeforeEach
    void createStore() {
        configService = mock(ConfigService.class);
        store = new BrowserModePreferenceStore(configService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", "\u2003"})
    void absentOrBlankSavedModeHasNoPreference(String mode) {
        when(configService.getConfigValue(CONFIG_KEY)).thenReturn(mode);

        assertNull(store.readSavedMode());

        verify(configService).getConfigValue(CONFIG_KEY);
        verifyNoMoreInteractions(configService);
    }

    @ParameterizedTest
    @MethodSource("validModes")
    void readsOnlyTheModeKeyAndNormalizesSupportedValues(String mode, String expected) {
        when(configService.getConfigValue(CONFIG_KEY)).thenReturn(mode);

        assertEquals(expected, store.readSavedMode());

        verify(configService).getConfigValue(CONFIG_KEY);
        verifyNoMoreInteractions(configService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"other", "lagou", "job51", "boss,liepin", SECRET})
    void invalidSavedModeFailsWithoutExposingTheValue(String mode) {
        when(configService.getConfigValue(CONFIG_KEY)).thenReturn(mode);

        IllegalStateException failure = assertThrows(IllegalStateException.class, store::readSavedMode);

        assertEquals("Saved browser platform mode must be boss, liepin, 51job, zhilian or legacy all", failure.getMessage());
        assertNull(failure.getCause());
        verify(configService).getConfigValue(CONFIG_KEY);
        verifyNoMoreInteractions(configService);
    }

    @ParameterizedTest
    @MethodSource("configFailures")
    void readFailuresExposeOnlyTheExceptionType(RuntimeException original) {
        when(configService.getConfigValue(CONFIG_KEY)).thenThrow(original);

        IllegalStateException failure = assertThrows(IllegalStateException.class, store::readSavedMode);

        assertEquals("Unable to read saved browser platform mode: " + original.getClass().getSimpleName(),
                failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        verify(configService).getConfigValue(CONFIG_KEY);
        verifyNoMoreInteractions(configService);
    }

    @ParameterizedTest
    @MethodSource("validModes")
    void savesOnlyTheModeKeyWithNormalizedValue(String mode, String expected) {
        when(configService.updateConfig(CONFIG_KEY, expected)).thenReturn(true);

        store.save(mode);

        verify(configService).updateConfig(CONFIG_KEY, expected);
        verifyNoMoreInteractions(configService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", "\u2003", "other", "lagou", "job51", "boss,liepin", SECRET})
    void invalidInputIsRejectedBeforeAnyConfigurationAccess(String mode) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> store.save(mode));

        assertEquals("Browser platform mode must be boss, liepin, 51job, zhilian or legacy all", failure.getMessage());
        assertNull(failure.getCause());
        verifyNoInteractions(configService);
    }

    @Test
    void anUnsuccessfulWriteIsNeverReportedAsSuccess() {
        when(configService.updateConfig(CONFIG_KEY, "boss")).thenReturn(false);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> store.save("boss"));

        assertEquals("Unable to save browser platform mode", failure.getMessage());
        assertNull(failure.getCause());
        verify(configService).updateConfig(CONFIG_KEY, "boss");
        verifyNoMoreInteractions(configService);
    }

    @ParameterizedTest
    @MethodSource("configFailures")
    void writeFailuresExposeOnlyTheExceptionType(RuntimeException original) {
        when(configService.updateConfig(CONFIG_KEY, "liepin")).thenThrow(original);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> store.save("liepin"));

        assertEquals("Unable to save browser platform mode: " + original.getClass().getSimpleName(),
                failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        verify(configService).updateConfig(CONFIG_KEY, "liepin");
        verifyNoMoreInteractions(configService);
    }

    @Test
    void readsSavedPreferenceAgainOnEachCall() {
        when(configService.getConfigValue(CONFIG_KEY)).thenReturn("boss", "liepin");

        assertEquals("boss", store.readSavedMode());
        assertEquals("liepin", store.readSavedMode());

        verify(configService, times(2)).getConfigValue(CONFIG_KEY);
        verifyNoMoreInteractions(configService);
    }

    private static Stream<Arguments> validModes() {
        return Stream.of(
                Arguments.of("boss", "boss"),
                Arguments.of("  BoSs\t", "boss"),
                Arguments.of("liepin", "liepin"),
                Arguments.of("\n LiEpIn ", "liepin"),
                Arguments.of("51job", "51job"),
                Arguments.of("\n 51JoB ", "51job"),
                Arguments.of("zhilian", "zhilian"),
                Arguments.of("\t ZhiLian  ", "zhilian"),
                Arguments.of("all", "all"),
                Arguments.of("\tALL  ", "all")
        );
    }

    private static Stream<RuntimeException> configFailures() {
        return Stream.of(
                new IllegalStateException(SECRET),
                new DataAccessResourceFailureException(SECRET, new SQLException(SECRET))
        );
    }
}
