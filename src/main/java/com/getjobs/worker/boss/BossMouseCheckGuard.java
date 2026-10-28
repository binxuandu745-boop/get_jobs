package com.getjobs.worker.boss;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Per-movement checks; the caller still performs an unconditional full check before clicking. */
final class BossMouseCheckGuard implements Runnable {
    static final long FULL_CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final Runnable fullCheck;
    private final Runnable lightweightCheck;
    private final Runnable identityGuard;
    private final LongSupplier nanoTime;
    private boolean fullCheckCompleted;
    private long lastFullCheckCompleted;
    private int fullCheckCount;
    private long fullCheckNanos;
    private int lightweightCheckCount;
    private long lightweightCheckNanos;

    BossMouseCheckGuard(Runnable fullCheck, Runnable lightweightCheck, Runnable identityGuard) {
        this(fullCheck, lightweightCheck, identityGuard, System::nanoTime);
    }

    BossMouseCheckGuard(Runnable fullCheck, Runnable lightweightCheck, Runnable identityGuard,
                        LongSupplier nanoTime) {
        this.fullCheck = Objects.requireNonNull(fullCheck);
        this.lightweightCheck = Objects.requireNonNull(lightweightCheck);
        this.identityGuard = Objects.requireNonNull(identityGuard);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    void checkFully() {
        long started = nanoTime.getAsLong();
        fullCheckCount++;
        try {
            fullCheck.run();
            identityGuard.run();
            // Start the interval after the probe completes, even when a browser query was slow.
            lastFullCheckCompleted = nanoTime.getAsLong();
            fullCheckCompleted = true;
        } finally {
            fullCheckNanos += nanoTime.getAsLong() - started;
        }
    }

    @Override
    public void run() {
        long started = nanoTime.getAsLong();
        lightweightCheckCount++;
        try {
            lightweightCheck.run();
            // Identity and cancellation must never be throttled along with the DOM probe.
            identityGuard.run();
        } finally {
            lightweightCheckNanos += nanoTime.getAsLong() - started;
        }
        if (!fullCheckCompleted
                || nanoTime.getAsLong() - lastFullCheckCompleted >= FULL_CHECK_INTERVAL_NANOS) {
            checkFully();
        }
    }

    Statistics statistics() {
        return new Statistics(fullCheckCount, TimeUnit.NANOSECONDS.toMillis(fullCheckNanos),
                lightweightCheckCount, TimeUnit.NANOSECONDS.toMillis(lightweightCheckNanos));
    }

    record Statistics(int fullCheckCount, long fullCheckMillis,
                      int lightweightCheckCount, long lightweightCheckMillis) { }
}
