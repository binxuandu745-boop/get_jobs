package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BossMouseCheckGuardTest {
    private static final long MILLISECOND = 1_000_000L;

    @Test
    void repeatedCheckpointsKeepLocalAndIdentityChecksWithoutRepeatedDomProbes() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger full = new AtomicInteger(), local = new AtomicInteger(), identity = new AtomicInteger();
        BossMouseCheckGuard guard = new BossMouseCheckGuard(full::incrementAndGet,
                local::incrementAndGet, identity::incrementAndGet, clock::get);

        guard.checkFully();
        for (int i = 0; i < 200; i++) {
            clock.addAndGet(MILLISECOND);
            guard.run();
        }

        assertEquals(1, full.get());
        assertEquals(200, local.get());
        assertEquals(201, identity.get());
        assertEquals(1, guard.statistics().fullCheckCount());
        assertEquals(200, guard.statistics().lightweightCheckCount());
    }

    @Test
    void longMovementStillPerformsPeriodicFullProbes() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger full = new AtomicInteger();
        BossMouseCheckGuard guard = new BossMouseCheckGuard(full::incrementAndGet,
                () -> { }, () -> { }, clock::get);

        guard.checkFully();
        clock.set(999 * MILLISECOND);
        guard.run();
        assertEquals(1, full.get());
        clock.set(1000 * MILLISECOND);
        guard.run();
        assertEquals(2, full.get());
        clock.set(1999 * MILLISECOND);
        guard.run();
        assertEquals(2, full.get());
        clock.set(2000 * MILLISECOND);
        guard.run();
        assertEquals(3, full.get());
    }

    @Test
    void slowProbeStartsNextIntervalAfterItCompletes() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger full = new AtomicInteger();
        BossMouseCheckGuard guard = new BossMouseCheckGuard(() -> {
            full.incrementAndGet();
            clock.addAndGet(2000 * MILLISECOND);
        }, () -> { }, () -> { }, clock::get);

        guard.checkFully();
        guard.run();
        assertEquals(1, full.get(), "A slow successful probe must not immediately trigger another probe");
        clock.addAndGet(1000 * MILLISECOND);
        guard.run();
        assertEquals(2, full.get());
        assertEquals(4000, guard.statistics().fullCheckMillis());
    }

    @Test
    void cancellationFromLocalCheckpointPropagatesWithoutRunningAnotherProbe() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger full = new AtomicInteger(), identity = new AtomicInteger();
        CancellationException cancelled = new CancellationException("test stop");
        BossMouseCheckGuard guard = new BossMouseCheckGuard(full::incrementAndGet,
                () -> { throw cancelled; }, identity::incrementAndGet, clock::get);

        guard.checkFully();
        clock.addAndGet(2000 * MILLISECOND);
        assertSame(cancelled, assertThrows(CancellationException.class, guard::run));
        assertEquals(1, full.get());
        assertEquals(1, identity.get());
    }

    @Test
    void identityChangesAreNotDelayedUntilPeriodicProbe() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger identity = new AtomicInteger();
        BossTaskPausedException changed = BossTaskPausedException.detailsUnavailable("test identity changed");
        BossMouseCheckGuard guard = new BossMouseCheckGuard(() -> { }, () -> { }, () -> {
            if (identity.incrementAndGet() == 2) throw changed;
        }, clock::get);

        guard.checkFully();
        assertSame(changed, assertThrows(BossTaskPausedException.class, guard::run));
        assertEquals(1, guard.statistics().fullCheckCount());
    }

    @Test
    void periodicVerificationFailurePropagatesUnchanged() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger full = new AtomicInteger();
        BossTaskPausedException verification = BossTaskPausedException.verificationRequired();
        BossMouseCheckGuard guard = new BossMouseCheckGuard(() -> {
            if (full.incrementAndGet() == 2) throw verification;
        }, () -> { }, () -> { }, clock::get);

        guard.checkFully();
        clock.addAndGet(1000 * MILLISECOND);
        assertSame(verification, assertThrows(BossTaskPausedException.class, guard::run));
        assertEquals(2, guard.statistics().fullCheckCount());
    }

    @Test
    void initialFullFailureStopsBeforeIdentityOrLocalChecks() {
        AtomicInteger local = new AtomicInteger(), identity = new AtomicInteger();
        BossTaskPausedException unavailable = BossTaskPausedException.detailsUnavailable("test closed");
        BossMouseCheckGuard guard = new BossMouseCheckGuard(() -> { throw unavailable; },
                local::incrementAndGet, identity::incrementAndGet, () -> 0);

        assertSame(unavailable, assertThrows(BossTaskPausedException.class, guard::checkFully));
        assertEquals(0, local.get());
        assertEquals(0, identity.get());
    }

    @Test
    void timingsSeparateFullProbesFromLightweightCheckpoints() {
        AtomicLong clock = new AtomicLong();
        BossMouseCheckGuard guard = new BossMouseCheckGuard(
                () -> clock.addAndGet(8 * MILLISECOND),
                () -> clock.addAndGet(2 * MILLISECOND),
                () -> clock.addAndGet(MILLISECOND), clock::get);

        guard.checkFully();
        guard.run();
        guard.run();

        assertEquals(9, guard.statistics().fullCheckMillis());
        assertEquals(6, guard.statistics().lightweightCheckMillis());
    }
}
