package prj.salmon.toropassicsystem.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * キー (クライアントIP) ごとに、一定時間あたりのリクエスト数を制限する (固定ウィンドウ方式)。
 * 追跡するキーの数には上限があり、上限に達している間の新しいキーは 1 つの共有ウィンドウで数える。
 */
public class RateLimiter {
    // 実際のキーと衝突しない共有ウィンドウのキー
    private static final String OVERFLOW_KEY = "\u0000overflow";

    private final int maxRequests;
    private final long windowMillis;
    private final int maxTrackedKeys;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowMillis) {
        this(maxRequests, windowMillis, 10000, System::currentTimeMillis);
    }

    public RateLimiter(int maxRequests, long windowMillis, int maxTrackedKeys, LongSupplier clock) {
        this.maxRequests = maxRequests;
        this.windowMillis = Math.max(1, windowMillis);
        this.maxTrackedKeys = Math.max(1, maxTrackedKeys);
        this.clock = clock;
    }

    // 許可された場合は 0、制限された場合は次に許可されるまでの時間 (ミリ秒) を返す
    // maxRequests が 0 以下の場合は制限しない
    public long tryAcquire(String key) {
        if (maxRequests <= 0) return 0;
        long now = clock.getAsLong();
        String trackedKey = key;
        if (windows.size() >= maxTrackedKeys && !windows.containsKey(key)) {
            windows.values().removeIf(w -> w.isExpired(now, windowMillis));
            if (windows.size() >= maxTrackedKeys) trackedKey = OVERFLOW_KEY;
        }
        Window window = windows.computeIfAbsent(trackedKey, k -> new Window(now));
        synchronized (window) {
            if (window.isExpired(now, windowMillis)) {
                window.start = now;
                window.count = 0;
            }
            if (window.count >= maxRequests) {
                return Math.max(1, window.start + windowMillis - now);
            }
            window.count++;
            return 0;
        }
    }

    int trackedKeys() {
        return windows.size();
    }

    private static class Window {
        long start;
        int count = 0;

        Window(long start) {
            this.start = start;
        }

        boolean isExpired(long now, long windowMillis) {
            return now - start >= windowMillis;
        }
    }
}
