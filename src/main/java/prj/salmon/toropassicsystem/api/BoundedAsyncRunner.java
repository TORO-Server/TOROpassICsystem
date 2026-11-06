package prj.salmon.toropassicsystem.api;

import fi.iki.elonen.NanoHTTPD;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NanoHTTPD の接続をスレッド数と待ち行列に上限のあるプールで処理する。
 * (既定のランナーは接続ごとにスレッドを作るので、接続数に応じて Minecraft の JVM のスレッドが増える)
 * 待ち行列も一杯のときは、新しい接続をすぐに閉じる。
 */
public class BoundedAsyncRunner implements NanoHTTPD.AsyncRunner {
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private final ThreadPoolExecutor pool;
    private final Set<NanoHTTPD.ClientHandler> handlers = ConcurrentHashMap.newKeySet();

    public BoundedAsyncRunner(int maxWorkers, int maxQueued) {
        int workers = Math.max(1, maxWorkers);
        this.pool = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(Math.max(1, maxQueued)), runnable -> {
            Thread thread = new Thread(runnable, "TOROpass-API-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.pool.allowCoreThreadTimeOut(true);
    }

    @Override
    public void exec(NanoHTTPD.ClientHandler handler) {
        handlers.add(handler);
        try {
            pool.execute(handler);
        } catch (RejectedExecutionException e) {
            handlers.remove(handler);
            handler.close();
        }
    }

    @Override
    public void closed(NanoHTTPD.ClientHandler handler) {
        handlers.remove(handler);
    }

    @Override
    public void closeAll() {
        pool.shutdownNow();
        for (NanoHTTPD.ClientHandler handler : new ArrayList<>(handlers)) {
            handler.close();
        }
        handlers.clear();
    }
}
