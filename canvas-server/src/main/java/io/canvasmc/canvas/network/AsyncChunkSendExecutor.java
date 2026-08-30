package io.canvasmc.canvas.network;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded executor used by async chunk packet preparation. */
public final class AsyncChunkSendExecutor {

    private AsyncChunkSendExecutor() {
    }

    public static Executor executor() {
        return Holder.EXECUTOR;
    }

    private static final class Holder {
        private static final ThreadPoolExecutor EXECUTOR = createExecutor();

        private static ThreadPoolExecutor createExecutor() {
            final NetworkOptimizationsConfiguration.ChunkSending config =
                NetworkOptimizationsConfiguration.getInstance().chunkSending;
            final AtomicInteger threadId = new AtomicInteger();

            final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                config.workerThreads,
                config.workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.executorQueueCapacity),
                runnable -> Thread.ofPlatform()
                    .daemon(true)
                    .priority(Thread.NORM_PRIORITY - 1)
                    .name("Canvas Async Chunk Packet #" + threadId.incrementAndGet())
                    .uncaughtExceptionHandler((thread, throwable) ->
                        io.canvasmc.canvas.GlobalConfiguration.LOGGER.error(
                            "Uncaught exception in {}", thread.getName(), throwable
                        )
                    )
                    .unstarted(runnable),
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            executor.prestartAllCoreThreads();
            return executor;
        }
    }
}
