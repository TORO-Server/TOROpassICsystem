package prj.salmon.toropassicsystem.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AuthThrottleTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private AuthThrottle throttle(int maxFailures, long windowMillis, long lockoutMillis) {
        return new AuthThrottle(maxFailures, windowMillis, lockoutMillis, 10000, now::get);
    }

    @Test
    void locksAfterMaxDistinctFailures() {
        AuthThrottle t = throttle(5, 600_000, 900_000);
        for (int i = 0; i < 4; i++) {
            assertFalse(t.recordFailure("alice", "pw" + i));
            assertEquals(0, t.remainingLockMillis("alice"));
        }
        assertTrue(t.recordFailure("alice", "pw4"));
        assertEquals(900_000, t.remainingLockMillis("alice"));
        // 他のキーには影響しない
        assertEquals(0, t.remainingLockMillis("bob"));
    }

    @Test
    void repeatedSameAttemptCountsOnce() {
        AuthThrottle t = throttle(5, 600_000, 900_000);
        for (int i = 0; i < 20; i++) {
            assertFalse(t.recordFailure("alice", "same-wrong-password"));
        }
        assertEquals(0, t.remainingLockMillis("alice"));
    }

    @Test
    void lockExpiresAfterLockoutPeriod() {
        AuthThrottle t = throttle(3, 600_000, 900_000);
        for (int i = 0; i < 3; i++) t.recordFailure("alice", "pw" + i);
        assertTrue(t.remainingLockMillis("alice") > 0);

        now.addAndGet(899_999);
        assertEquals(1, t.remainingLockMillis("alice"));
        now.addAndGet(1);
        assertEquals(0, t.remainingLockMillis("alice"));

        // ロック解除後はカウントが最初からになる
        assertFalse(t.recordFailure("alice", "pw0"));
        assertEquals(0, t.remainingLockMillis("alice"));
    }

    @Test
    void failuresOutsideWindowAreForgotten() {
        AuthThrottle t = throttle(3, 600_000, 900_000);
        t.recordFailure("alice", "pw0");
        t.recordFailure("alice", "pw1");
        now.addAndGet(600_001);
        assertFalse(t.recordFailure("alice", "pw2"));
        assertEquals(0, t.remainingLockMillis("alice"));
    }

    @Test
    void failuresDuringLockDoNotExtendIt() {
        AuthThrottle t = throttle(2, 600_000, 900_000);
        t.recordFailure("alice", "pw0");
        t.recordFailure("alice", "pw1");
        now.addAndGet(100_000);
        assertFalse(t.recordFailure("alice", "pw2"));
        assertEquals(800_000, t.remainingLockMillis("alice"));
    }

    @Test
    void resetClearsFailuresButNotActiveLock() {
        AuthThrottle t = throttle(3, 600_000, 900_000);
        t.recordFailure("alice", "pw0");
        t.recordFailure("alice", "pw1");
        t.reset("alice");
        assertFalse(t.recordFailure("alice", "pw2"));
        assertFalse(t.recordFailure("alice", "pw3"));
        assertEquals(0, t.remainingLockMillis("alice"));

        assertTrue(t.recordFailure("alice", "pw4"));
        t.reset("alice");
        assertTrue(t.remainingLockMillis("alice") > 0);
    }

    @Test
    void trackedKeysAreBounded() {
        AuthThrottle t = new AuthThrottle(5, 1_000, 1_000, 100, now::get);
        for (int i = 0; i < 100; i++) t.recordFailure("key" + i, "pw");
        assertEquals(100, t.trackedKeys());
        now.addAndGet(1_001);
        t.recordFailure("new-key", "pw");
        assertEquals(1, t.trackedKeys());
    }
}
