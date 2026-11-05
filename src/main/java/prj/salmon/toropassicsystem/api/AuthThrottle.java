package prj.salmon.toropassicsystem.api;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 認証失敗回数をキー (プレイヤー名とクライアントIPの組など) ごとに数え、
 * 一定時間内に失敗が多すぎる場合はそのキーを一時的にロックする。
 * 同じ内容の試行 (同じパスワードの再送信など) は 1 回の失敗として数える。
 *
 * 追跡するキーの数には上限があり、上限に達している間の新しいキーは 1 つの共有エントリで数える。
 */
public class AuthThrottle {
    // 実際のキーと衝突しない共有エントリのキー
    private static final String OVERFLOW_KEY = "\u0000overflow";
    // 1 つのキーで覚える試行の数の上限 (ロックしない使い方でもメモリが増え続けないようにする)
    private static final int MAX_ATTEMPTS_PER_KEY = 256;

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
        // 上限に達している間は、追跡されていないキーを共有エントリの状態で判定する
        if (entry == null && entries.size() >= maxTrackedKeys) entry = entries.get(OVERFLOW_KEY);
        if (entry == null) return 0;
        synchronized (entry) {
            return Math.max(0, entry.lockedUntil - clock.getAsLong());
        }
    }

    // 認証失敗を記録する。この失敗でロックされた場合は true を返す
    public boolean recordFailure(String key, String attemptFingerprint) {
        long now = clock.getAsLong();
        String trackedKey = key;
        if (entries.size() >= maxTrackedKeys && !entries.containsKey(key)) {
            cleanup(now);
            if (entries.size() >= maxTrackedKeys) trackedKey = OVERFLOW_KEY;
        }
        Entry entry = entries.computeIfAbsent(trackedKey, k -> new Entry());
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

    // 期間内に記録された異なる試行の数
    public int failureCount(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return 0;
        synchronized (entry) {
            entry.prune(clock.getAsLong() - windowMillis);
            return entry.failures.size();
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

    // 条件に合うキーの失敗回数とロックを取り消す
    public void clearMatching(Predicate<String> keyFilter) {
        entries.keySet().removeIf(key -> !OVERFLOW_KEY.equals(key) && keyFilter.test(key));
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
        final Map<String, Long> failures = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_ATTEMPTS_PER_KEY;
            }
        };
        long lockedUntil = 0;

        void prune(long threshold) {
            failures.values().removeIf(time -> time <= threshold);
        }
    }
}
