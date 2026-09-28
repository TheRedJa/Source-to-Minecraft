package dev.theredja.src2mc.client.render;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The threads map surfaces and prop batches are tessellated and lit on. Only the finished vertex
 * lists come back to the render thread, which fills and uploads the buffers: Iris stamps its
 * captured entity ids into every vertex a {@code BufferBuilder} takes, from state the render
 * thread owns, so the builder stays there.
 *
 * A third of the cores, at most six: Sodium's chunk builders and the integrated server want the
 * rest, and a build is short enough that more threads would only compete with them. Slightly
 * below normal priority for the same reason.
 */
final class MeshBuildPool {
    static final int THREADS = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 3));
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, task -> {
        Thread thread = new Thread(task, "src2mc-mesh-builder-" + NEXT_ID.getAndIncrement());
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    /** Worker time spent building, for the status commands. */
    static final AtomicLong WORKER_NANOS = new AtomicLong();
    static final AtomicLong JOBS = new AtomicLong();

    private MeshBuildPool() {}

    static <T> CompletableFuture<T> submit(Supplier<T> job) {
        return CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            try {
                return job.get();
            } finally {
                WORKER_NANOS.addAndGet(System.nanoTime() - started);
                JOBS.incrementAndGet();
            }
        }, POOL);
    }
}
