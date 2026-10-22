package com.getjobs.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Position codes are Boss-owned numeric ids; anything else must never reach the search URL. */
class BossPositionConfigurationTest {
    @Test
    void numericCodesAreAcceptedDeduplicatedAndJoined() {
        assertEquals("100101", BossService.normalizePositionCodes("100101"));
        assertEquals("100101,100102", BossService.normalizePositionCodes(" 100101 , 100102 ,100101 "));
    }

    @Test
    void unusableValuesAreDroppedInsteadOfBeingWrittenIntoTheSearchUrl() {
        assertNull(BossService.normalizePositionCodes(null));
        assertNull(BossService.normalizePositionCodes("   "));
        assertNull(BossService.normalizePositionCodes("Java"));
        assertNull(BossService.normalizePositionCodes("100101&jobType=1902"));
        assertNull(BossService.normalizePositionCodes("100101,后端"));
    }
}
