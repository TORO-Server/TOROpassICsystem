package prj.salmon.toropassicsystem;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * /toropassrank の集計。メインスレッドの外で実行し、結果を一定時間キャッシュする。
 * 長い問い合わせで DB のロックを握り続けるとメインスレッドが止まるので、ユーザーごとの短い問い合わせに分けて集計する。
 */
public class RankingService {
    private final DatabaseManager db;
    private final Executor executor;
    private final long cacheMillis;
    private final LongSupplier clock;

    private Snapshot cached;
    private long cachedAt;
    private CompletableFuture<Snapshot> inFlight;

    public RankingService(DatabaseManager db, Executor executor, long cacheMillis, LongSupplier clock) {
        this.db = db;
        this.executor = executor;
        this.cacheMillis = cacheMillis;
        this.clock = clock;
    }

    // キャッシュが新しければそれを返す。集計中なら同じ集計の結果を待つ
    public synchronized CompletableFuture<Snapshot> snapshot() {
        if (cached != null && clock.getAsLong() - cachedAt < cacheMillis) return CompletableFuture.completedFuture(cached);
        if (inFlight != null) return inFlight;
        CompletableFuture<Snapshot> future = CompletableFuture.supplyAsync(this::compute, executor);
        inFlight = future;
        return future.whenComplete((snapshot, error) -> {
            synchronized (this) {
                inFlight = null;
                if (snapshot != null) {
                    cached = snapshot;
                    cachedAt = clock.getAsLong();
                }
            }
        });
    }

    private Snapshot compute() {
        try {
            Map<UUID, String> names = db.getAllPlayerNames();
            List<Entry> entries = new ArrayList<>();
            for (Map.Entry<UUID, String> user : names.entrySet()) {
                long spending = db.getTravelSpending(user.getKey());
                if (spending > 0) entries.add(new Entry(user.getKey(), user.getValue(), spending));
            }
            return new Snapshot(entries);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    public record Entry(UUID uuid, String name, long consumption) {}

    public static final class Snapshot {
        private final List<Entry> sorted;
        private final Map<UUID, Long> byUser = new HashMap<>();
        private final long total;

        Snapshot(List<Entry> entries) {
            List<Entry> copy = new ArrayList<>(entries);
            copy.sort(Comparator.comparingLong(Entry::consumption).reversed().thenComparing(e -> e.uuid().toString()));
            this.sorted = List.copyOf(copy);
            long sum = 0;
            for (Entry entry : copy) {
                byUser.put(entry.uuid(), entry.consumption());
                sum += entry.consumption();
            }
            this.total = sum;
        }

        public List<Entry> top(int count) {
            return sorted.subList(0, Math.min(count, sorted.size()));
        }

        public long totalConsumption() {
            return total;
        }

        public long consumptionOf(UUID uuid) {
            return byUser.getOrDefault(uuid, 0L);
        }

        // 順位 (同じ金額は同じ順位にする)。金額が 0 のユーザーは最下位の次になる
        public int rankOf(long consumption) {
            int higher = 0;
            for (Entry entry : sorted) {
                if (entry.consumption() <= consumption) break;
                higher++;
            }
            return higher + 1;
        }
    }
}
