package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure in-memory tests: no browser, network, application context or database. */
class BossTaskVisitLedgerTest {
    private final BossTaskVisitLedger ledger = new BossTaskVisitLedger();

    @Test
    void exposesCanonicalIdentityForMatchingTheSameDetailResponse() {
        assertEquals("abc123", BossTaskVisitLedger.identityOf(
                "https://www.zhipin.com/job_detail/abc123.html?source=one#job"));
    }

    @Test
    void overlappingKeywordResultsClaimTheSameJobOnlyOnce() {
        assertTrue(ledger.claim("/job_detail/abc123-_XYZ.html?source=keyword-one"));
        assertFalse(ledger.claim("/job_detail/abc123-_XYZ.html?source=keyword-two"));
    }

    @Test
    void absoluteRelativeAndBareIdReferencesUseTheSameIdentity() {
        assertTrue(ledger.claim("https://www.zhipin.com/job_detail/abc123.html?source=one#job"));
        assertFalse(ledger.claim("/job_detail/abc123.html?source=two"));
        assertFalse(ledger.claim("job_detail/abc123.html#different-fragment"));
        assertFalse(ledger.claim("//www.zhipin.com/job_detail/abc123.html"));
        assertFalse(ledger.claim("abc123"));
    }

    @Test
    void differentIdsAreNotMergedEvenWhenTheirTitlesOrCompaniesCouldMatch() {
        assertTrue(ledger.claim("/job_detail/first-job.html"));
        assertTrue(ledger.claim("/job_detail/second-job.html"));
        assertFalse(ledger.claim("first-job"));
        assertFalse(ledger.claim("second-job"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "null", "undefined", "Java 后端实习生", "公司：同名公司",
            "/web/geek/jobs?jobId=abc123",
            "https://www.zhipin.com/web/geek/jobs?redirect=/job_detail/abc123.html",
            "/job_detail/.html", "/job_detail/null.html", "/job_detail/undefined.html",
            "/job_detail/abc123.html/extra", "/other/job_detail/abc123.html",
            "https://example.com/job_detail/abc123.html",
            "https://not-zhipin.com/job_detail/abc123.html",
            "javascript:/job_detail/abc123.html", "https://[invalid/job_detail/abc123.html"
    })
    void missingOrUnrecognizedIdentityNeverSuppressesOtherUnknownCards(String reference) {
        assertTrue(ledger.claim(reference));
        assertTrue(ledger.claim(reference));
    }

    @Test
    void invalidReferenceDoesNotConsumeTheIdContainedInItsQuery() {
        assertTrue(ledger.claim("/web/geek/jobs?redirect=/job_detail/abc123.html"));
        assertTrue(ledger.claim("abc123"));
        assertFalse(ledger.claim("abc123"));
    }

    @Test
    void resetAllowsTheSameJobInANewTask() {
        assertTrue(ledger.claim("abc123"));
        assertFalse(ledger.claim("abc123"));
        ledger.reset();
        assertTrue(ledger.claim("abc123"));
    }

    @Test
    void referenceWhitespaceIsTrimmedButIdsRemainCaseSensitive() {
        assertTrue(ledger.claim("  /job_detail/Abc123.html  "));
        assertFalse(ledger.claim("Abc123"));
        assertTrue(ledger.claim("abc123"));
    }

    @Test
    void concurrentClaimsStillHaveOnlyOneWinner() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> claims = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(ignored -> (Callable<Boolean>) () -> ledger.claim("abc123"))
                    .toList();
            int winners = 0;
            for (var result : executor.invokeAll(claims)) {
                if (result.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        }
    }
}
