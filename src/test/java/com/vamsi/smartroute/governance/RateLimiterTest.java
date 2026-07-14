package com.vamsi.smartroute.governance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    /** A clock the test advances by hand — no sleeping, no wall-clock flakiness. */
    private final AtomicLong nanos = new AtomicLong(0);

    private RateLimiter limiterWith(double capacity, double refillPerSec) {
        return new RateLimiter(capacity, refillPerSec, nanos::get);
    }

    private void advanceSeconds(double seconds) {
        nanos.addAndGet((long) (seconds * 1_000_000_000.0));
    }

    @Test
    void allowsUpToTheBurstCapacityThenRefuses() {
        RateLimiter limiter = limiterWith(5, 1);   // burst 5, refill 1/s
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("acme"), "request " + (i + 1) + " should be admitted within the burst");
        }
        assertFalse(limiter.tryAcquire("acme"), "the 6th request in the same instant should be rate-limited");
    }

    @Test
    void refillsOverElapsedTime() {
        RateLimiter limiter = limiterWith(5, 2);   // refill 2 tokens/sec
        for (int i = 0; i < 5; i++) limiter.tryAcquire("acme");   // drain the burst
        assertFalse(limiter.tryAcquire("acme"));

        advanceSeconds(1.0);                        // 1s -> +2 tokens
        assertTrue(limiter.tryAcquire("acme"));
        assertTrue(limiter.tryAcquire("acme"));
        assertFalse(limiter.tryAcquire("acme"), "only 2 tokens refilled in 1s");
    }

    @Test
    void refillDoesNotAccumulateBeyondCapacity() {
        RateLimiter limiter = limiterWith(3, 100);  // very fast refill
        limiter.tryAcquire("acme");                 // touch the bucket once
        advanceSeconds(10.0);                       // would add 1000 tokens if uncapped
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("acme"), "capacity is the ceiling regardless of idle time");
        }
        assertFalse(limiter.tryAcquire("acme"), "must not exceed the burst capacity of 3");
    }

    @Test
    void tenantsAreIsolated() {
        RateLimiter limiter = limiterWith(2, 1);
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"), "tenant a exhausted its burst");
        // tenant b has its own full bucket
        assertTrue(limiter.tryAcquire("b"));
        assertTrue(limiter.tryAcquire("b"));
    }

    @Test
    void neverAdmitsMoreThanCapacityUnderConcurrentLoad() throws InterruptedException {
        // Same rigor as SpendLedger's TOCTOU test: with the clock frozen (no refill), N concurrent
        // callers must together be admitted at most `capacity` times -- never more, even racing.
        int capacity = 50, threads = 200;
        RateLimiter limiter = limiterWith(capacity, 0);   // no refill: capacity is a hard total
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                if (limiter.tryAcquire("acme")) admitted.incrementAndGet();
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers should finish");

        assertEquals(capacity, admitted.get(),
                "exactly `capacity` requests admitted with the clock frozen — no over-admission race");
    }
}
