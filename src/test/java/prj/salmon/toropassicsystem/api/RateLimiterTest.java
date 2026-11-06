package prj.salmon.toropassicsystem.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Test
    void allowsUpToLimitThenRejects() {
        RateLimiter limiter = new RateLimiter(3, 60_000, 10000, now::get);
        assertEquals(0, limiter.tryAcquire("client-1"));
        assertEquals(0, limiter.tryAcquire("client-1"));
        assertEquals(0, limiter.tryAcquire("client-1"));
        assertEquals(60_000, limiter.tryAcquire("client-1"));
        // 別のIPは独立して数える
        assertEquals(0, limiter.tryAcquire("client-2"));
    }

    @Test
    void windowResets() {
        RateLimiter limiter = new RateLimiter(1, 60_000, 10000, now::get);
        assertEquals(0, limiter.tryAcquire("ip"));
        now.addAndGet(59_000);
        assertEquals(1_000, limiter.tryAcquire("ip"));
        now.addAndGet(1_000);
        assertEquals(0, limiter.tryAcquire("ip"));
    }

    @Test
    void zeroMeansUnlimited() {
        RateLimiter limiter = new RateLimiter(0, 60_000, 10000, now::get);
        for (int i = 0; i < 1000; i++) assertEquals(0, limiter.tryAcquire("ip"));
        assertEquals(0, limiter.trackedKeys());
    }

    @Test
    void trackedKeysAreBounded() {
        RateLimiter limiter = new RateLimiter(10, 1_000, 50, now::get);
        for (int i = 0; i < 50; i++) limiter.tryAcquire("ip" + i);
        assertEquals(50, limiter.trackedKeys());
        now.addAndGet(1_000);
        limiter.tryAcquire("another");
        assertEquals(1, limiter.trackedKeys());
    }

    @Test
    void keysAboveTheLimitShareOneWindow() {
        RateLimiter limiter = new RateLimiter(3, 60_000, 50, now::get);
        for (int i = 0; i < 50; i++) limiter.tryAcquire("ip" + i);
        // 上限に達している間は、新しいキーをいくつ使っても追跡数は増えず、共有の制限を受ける
        assertEquals(0, limiter.tryAcquire("new-1"));
        assertEquals(0, limiter.tryAcquire("new-2"));
        assertEquals(0, limiter.tryAcquire("new-3"));
        assertTrue(limiter.tryAcquire("new-4") > 0);
        assertTrue(limiter.trackedKeys() <= 51);
        // 追跡中のキーは自分のウィンドウで数える
        assertEquals(0, limiter.tryAcquire("ip0"));
    }
}
