package io.canvasmc.canvas.performance;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the executor backing Folia's async scheduler.
 *
 * <p>Upstream uses a {@code SynchronousQueue} with an effectively unlimited maximum thread
 * count. During plugin task storms that can create enough native threads to fail with
 * {@code OutOfMemoryError: unable to create native thread}. This executor either uses virtual
 * threads or a bounded platform-thread pool with backpressure.</p>
 */
public final class AdaptiveAsyncExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger("CanvasAdaptiveAsync");
    private static final AtomicReference<ThreadPoolExecutor> PLATFORM_EXECUTOR = new AtomicReference<>();

    private static volatile int baseCoreThreads;
    private static volatile int baseMaxThreads;

    private AdaptiveAsyncExecutor() {
    }

    public static Executor create() {
        final AdaptivePerformanceConfiguration.AsyncScheduler config =
            AdaptivePerformanceConfiguration.getInstance().asyncScheduler;

        if (config.mode == AdaptivePerformanceConfiguration.ExecutorMode.VIRTUAL_THREADS) {
            final ThreadFactory factory = Thread.ofVirtual()
                .name("Folia Async Scheduler Virtual #", 0L)
                .uncaughtExceptionHandler((thread, throwable) ->
                    LOGGER.error("Uncaught exception in virtual async scheduler thread: {}", thread.getName(), throwable)
                )
                .factory();

            LOGGER.info("Using virtual threads for the Folia async scheduler");
            return Executors.newThreadPerTaskExecutor(factory);
        }

        final int processors = Runtime.getRuntime().availableProcessors();
        final int coreThreads = config.coreThreads > 0
            ? config.coreThreads
            : Math.max(4, processors / 2);
        final int maxThreads = config.maxThreads > 0
            ? Math.max(coreThreads, config.maxThreads)
            : Math.max(coreThreads, Math.min(64, processors * 2));

        final ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger idGenerator = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable run) {
                final Thread thread = new Thread(run);
                thread.setName("Folia Async Scheduler Thread #" + this.idGenerator.getAndIncrement());
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                thread.setUncaughtExceptionHandler((t, throwable) ->
                    LOGGER.error("Uncaught exception in async scheduler thread: {}", t.getName(), throwable)
                );
                return thread;
            }
        };

        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            coreThreads,
            maxThreads,
            config.keepAliveSeconds,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(config.queueCapacity),
            factory,
            new BackpressurePolicy()
        );
        executor.allowCoreThreadTimeOut(config.allowCoreThreadTimeout);

        baseCoreThreads = coreThreads;
        baseMaxThreads = maxThreads;
        PLATFORM_EXECUTOR.set(executor);

        LOGGER.info(
            "Using bounded platform threads for the Folia async scheduler (core={}, max={}, queue={})",
            coreThreads,
            maxThreads,
            config.queueCapacity
        );
        return executor;
    }

    public static void onPressureStateChange(final AdaptivePerformanceController.PressureState state) {
        final ThreadPoolExecutor executor = PLATFORM_EXECUTOR.get();
        if (executor == null) {
            return;
        }

        final AdaptivePerformanceConfiguration.AsyncScheduler config =
            AdaptivePerformanceConfiguration.getInstance().asyncScheduler;
        if (!config.adaptPlatformThreadLimit) {
            return;
        }

        final int percent = switch (state) {
            case NORMAL -> 100;
            case BUSY -> config.busyMaxThreadsPercent;
            case HIGH -> config.highMaxThreadsPercent;
            case EMERGENCY -> config.emergencyMaxThreadsPercent;
        };

        final int targetMax = Math.max(
            config.minimumAdaptiveMaxThreads,
            Math.max(1, (baseMaxThreads * percent) / 100)
        );
        final int boundedTargetMax = Math.min(baseMaxThreads, targetMax);
        final int targetCore = Math.min(baseCoreThreads, boundedTargetMax);

        // ThreadPoolExecutor requires core <= max. Change values in a safe order depending on direction.
        if (boundedTargetMax >= executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(boundedTargetMax);
            executor.setCorePoolSize(targetCore);
        } else {
            if (executor.getCorePoolSize() > targetCore) {
                executor.setCorePoolSize(targetCore);
            }
            executor.setMaximumPoolSize(boundedTargetMax);
        }
    }

    public static int getActiveThreadCount() {
        final ThreadPoolExecutor executor = PLATFORM_EXECUTOR.get();
        return executor == null ? -1 : executor.getActiveCount();
    }

    public static int getPoolSize() {
        final ThreadPoolExecutor executor = PLATFORM_EXECUTOR.get();
        return executor == null ? -1 : executor.getPoolSize();
    }

    public static int getQueueSize() {
        final ThreadPoolExecutor executor = PLATFORM_EXECUTOR.get();
        return executor == null ? -1 : executor.getQueue().size();
    }

    private static final class BackpressurePolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(final Runnable task, final ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Folia async scheduler is shutting down");
            }

            // Avoid a deadlock if an async worker recursively schedules more async work while
            // every worker and queue slot is occupied. It is still executing on an async worker.
            if (Thread.currentThread().getName().startsWith("Folia Async Scheduler Thread #")) {
                task.run();
                return;
            }

            try {
                // Backpressure is preferable to allocating an unbounded number of native threads.
                executor.getQueue().put(task);
            } catch (final InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while applying async scheduler backpressure", interruptedException);
            }
        }
    }
}
