package prj.salmon.toropassicsystem.api;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 認証失敗回数をキー (プレイヤー名やクライアントIP) ごとに数え、
 * 一定時間内に失敗が多すぎる場合はそのキーを一時的にロックする。
 * 同じ内容の試行 (同じパスワードの再送信など) は 1 回の失敗として数える。
 */
public class AuthThrottle {
    private final int maxFailures;
    private final long windowMillis;
    private final long lockoutMillis;
    private final int maxTrackedKeys;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public AuthThrottle(int maxFailures, long windowMillis, long lockoutMillis) {
        this(maxFailures, windowMillis, lockoutMillis, 10000, System::currentTimeMillis);
    }

    public AuthThrottle(int maxFailures, long windowMillis, long lockoutMillis, int maxTrackedKeys, LongSupplier clock) {
        this.maxFailures = Math.max(1, maxFailures);
        this.windowMillis = Math.max(1, windowMillis);
        this.lockoutMillis = Math.max(1, lockoutMillis);
        this.maxTrackedKeys = Math.max(1, maxTrackedKeys);
        this.clock = clock;
    }

    // ロック中なら残り時間 (ミリ秒)、ロックされていなければ 0 を返す
    public long remainingLockMillis(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return 0;
        synchronized (entry) {
            return Math.max(0, entry.lockedUntil - clock.getAsLong());
        }
    }

    // 認証失敗を記録する。この失敗でロックされた場合は true を返す
    public boolean recordFailure(String key, String attemptFingerprint) {
        long now = clock.getAsLong();
        if (entries.size() >= maxTrackedKeys && !entries.containsKey(key)) cleanup(now);
        Entry entry = entries.computeIfAbsent(key, k -> new Entry());
        synchronized (entry) {
            if (entry.lockedUntil > now) return false;
            entry.prune(now - windowMillis);
            entry.failures.put(attemptFingerprint, now);
            if (entry.failures.size() >= maxFailures) {
                entry.lockedUntil = now + lockoutMillis;
                entry.failures.clear();
                return true;
            }
            return false;
        }
    }

    // 認証成功時に失敗回数をリセットする (ロック中のキーは解除しない)
    public void reset(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return;
        synchronized (entry) {
            if (entry.lockedUntil <= clock.getAsLong()) entries.remove(key, entry);
        }
    }

    int trackedKeys() {
        return entries.size();
    }

    // 期限切れのエントリを削除してメモリ使用量を抑える
    private void cleanup(long now) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry entry = it.next().getValue();
            synchronized (entry) {
                entry.prune(now - windowMillis);
                if (entry.lockedUntil <= now && entry.failures.isEmpty()) it.remove();
            }
        }
    }

    private static class Entry {
        final Map<String, Long> failures = new HashMap<>();
        long lockedUntil = 0;

        void prune(long threshold) {
            failures.values().removeIf(time -> time <= threshold);
        }
    }
}
