package prj.salmon.toropassicsystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import prj.salmon.toropassicsystem.types.PaymentHistory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RankingServiceTest {
    private static final long CACHE_MILLIS = 60_000;

    @TempDir
    Path tempDir;

    private DatabaseManager db;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    // 集計を明示的に実行できる Executor (メインスレッドの外で実行される状況を再現する)
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private RankingService service;

    @BeforeEach
    void setUp() throws SQLException {
        db = new DatabaseManager(tempDir.resolve("toropass.db") + "?synchronous=OFF");
        db.connect(0);
        service = new RankingService(db, tasks::add, CACHE_MILLIS, now::get);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private UUID player(String name, int spending) throws SQLException {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
        db.ensureUser(uuid, name);
        if (spending > 0) db.addHistory(uuid, PaymentHistory.build("StationA[00]", "StationB[00]", -spending, 0, 1, ""));
        return uuid;
    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) task.run();
    }

    @Test
    void computesOffTheCallingThread() throws Exception {
        player("Steve", 100);
        CompletableFuture<RankingService.Snapshot> future = service.snapshot();
        // Executor が実行するまで集計は始まらない
        assertFalse(future.isDone());
        runTasks();
        assertEquals(100, future.get().totalConsumption());
    }

    @Test
    void ranksUseCompetitionNumbering() throws Exception {
        UUID a = player("A", 300);
        UUID b = player("B", 200);
        UUID c = player("C", 200);
        UUID d = player("D", 50);
        UUID e = player("E", 0);

        CompletableFuture<RankingService.Snapshot> future = service.snapshot();
        runTasks();
        RankingService.Snapshot snapshot = future.get();

        assertEquals(750, snapshot.totalConsumption());
        assertEquals(1, snapshot.rankOf(snapshot.consumptionOf(a)));
        assertEquals(2, snapshot.rankOf(snapshot.consumptionOf(b)));
        assertEquals(2, snapshot.rankOf(snapshot.consumptionOf(c)));
        assertEquals(4, snapshot.rankOf(snapshot.consumptionOf(d)));
        // 履歴のないプレイヤーは最下位の次
        assertEquals(5, snapshot.rankOf(snapshot.consumptionOf(e)));
        assertEquals(0, snapshot.consumptionOf(e));

        assertEquals(2, snapshot.top(2).size());
        assertEquals("A", snapshot.top(2).get(0).name());
        assertEquals(4, snapshot.top(10).size());
    }

    @Test
    void resultIsCachedUntilItExpires() throws Exception {
        player("Steve", 100);
        CompletableFuture<RankingService.Snapshot> first = service.snapshot();
        runTasks();
        first.get();

        player("Alex", 50);
        now.addAndGet(CACHE_MILLIS - 1);
        CompletableFuture<RankingService.Snapshot> cached = service.snapshot();
        assertTrue(cached.isDone());
        assertEquals(100, cached.get().totalConsumption());
        assertTrue(tasks.isEmpty());

        now.addAndGet(1);
        CompletableFuture<RankingService.Snapshot> refreshed = service.snapshot();
        assertEquals(1, tasks.size());
        runTasks();
        assertEquals(150, refreshed.get().totalConsumption());
    }

    @Test
    void concurrentRequestsShareOneComputation() throws Exception {
        player("Steve", 100);
        CompletableFuture<RankingService.Snapshot> first = service.snapshot();
        CompletableFuture<RankingService.Snapshot> second = service.snapshot();
        assertEquals(1, tasks.size());
        runTasks();
        assertEquals(100, first.get().totalConsumption());
        assertEquals(100, second.get().totalConsumption());
    }

    @Test
    void failureIsReportedAndNextRequestRetries() throws Exception {
        player("Steve", 100);
        db.close();
        CompletableFuture<RankingService.Snapshot> failed = service.snapshot();
        runTasks();
        assertTrue(failed.isCompletedExceptionally());

        db = new DatabaseManager(tempDir.resolve("toropass.db") + "?synchronous=OFF");
        db.connect(0);
        RankingService reopened = new RankingService(db, tasks::add, CACHE_MILLIS, now::get);
        CompletableFuture<RankingService.Snapshot> ok = reopened.snapshot();
        runTasks();
        assertEquals(100, ok.get().totalConsumption());

        // 同じインスタンスも失敗を覚えず、次の要求で集計し直す
        CompletableFuture<RankingService.Snapshot> retried = service.snapshot();
        assertFalse(retried.isDone());
        assertEquals(1, tasks.size());
        tasks.clear();
    }
}
