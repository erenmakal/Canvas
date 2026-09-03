package io.canvasmc.canvas.performance;

import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global pressure controller for large Folia servers.
 *
 * <p>The sampler is safe to call from every region tick. An atomic deadline ensures that only
 * one region performs the expensive metric collection for each configured sample interval.</p>
 */
public final class AdaptivePerformanceController {

    private static final Logger LOGGER = LoggerFactory.getLogger("CanvasAdaptive");
    private static final AtomicLong NEXT_SAMPLE_NANOS = new AtomicLong();
    private static final Map<ResourceKey<Level>, DistanceSnapshot> BASE_DISTANCES = new ConcurrentHashMap<>();

    private static volatile PressureState pressureState = PressureState.NORMAL;
    private static volatile double heapPercent;
    private static volatile double cpuPercent;
    private static volatile double gcPercent;
    private static volatile double systemMemoryPercent;
    private static volatile double asyncQueuePercent;
    private static volatile double worstRegionTps = TickRegionScheduler.getTickRate();
    private static volatile long lastGcCollectionMillis = totalGcCollectionMillis();
    private static volatile long lastSampleNanos = System.nanoTime();
    private static volatile long lastThreadWarningNanos;

    private AdaptivePerformanceController() {
    }

    public enum PressureState {
        NORMAL,
        BUSY,
        HIGH,
        EMERGENCY
    }

    public static void sample() {
        final AdaptivePerformanceConfiguration configuration = AdaptivePerformanceConfiguration.getInstance();
        final long now = System.nanoTime();
        final long intervalNanos = Math.max(250L, configuration.sampleIntervalMillis) * 1_000_000L;

        final long next = NEXT_SAMPLE_NANOS.get();
        if (now < next || !NEXT_SAMPLE_NANOS.compareAndSet(next, now + intervalNanos)) {
            return;
        }

        if (!configuration.enabled) {
            if (pressureState != PressureState.NORMAL) {
                transitionTo(PressureState.NORMAL, configuration);
            }
            return;
        }

        final Runtime runtime = Runtime.getRuntime();
        final long maxHeap = Math.max(1L, runtime.maxMemory());
        final long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        heapPercent = (usedHeap * 100.0D) / maxHeap;

        final java.lang.management.OperatingSystemMXBean genericOsBean = ManagementFactory.getOperatingSystemMXBean();
        if (genericOsBean instanceof com.sun.management.OperatingSystemMXBean osBean) {
            final double rawCpu = osBean.getCpuLoad();
            cpuPercent = rawCpu < 0.0D ? 0.0D : rawCpu * 100.0D;

            final long totalMemory = osBean.getTotalMemorySize();
            final long freeMemory = osBean.getFreeMemorySize();
            systemMemoryPercent = totalMemory <= 0L
                ? 0.0D
                : Math.max(0.0D, Math.min(100.0D, ((totalMemory - freeMemory) * 100.0D) / totalMemory));
        } else {
            cpuPercent = 0.0D;
            systemMemoryPercent = 0.0D;
        }

        final long gcCollectionMillis = totalGcCollectionMillis();
        final long elapsedNanos = Math.max(1L, now - lastSampleNanos);
        final long gcDeltaMillis = Math.max(0L, gcCollectionMillis - lastGcCollectionMillis);
        gcPercent = Math.min(100.0D, (gcDeltaMillis * 1_000_000.0D * 100.0D) / elapsedNanos);
        lastGcCollectionMillis = gcCollectionMillis;
        lastSampleNanos = now;

        worstRegionTps = sampleWorstRegionTps();
        final int queueSize = AdaptiveAsyncExecutor.getQueueSize();
        asyncQueuePercent = queueSize < 0 || configuration.asyncScheduler.queueCapacity <= 0
            ? 0.0D
            : Math.min(100.0D, queueSize * 100.0D / configuration.asyncScheduler.queueCapacity);

        final PressureState desired = classify(configuration);
        final PressureState current = pressureState;
        final PressureState resolved = desired.ordinal() < current.ordinal() && !canRecoverFrom(current, configuration)
            ? current
            : desired;

        if (resolved != current) {
            transitionTo(resolved, configuration);
        }

        checkNativeThreadPressure(now, configuration);
    }

    private static double sampleWorstRegionTps() {
        final MinecraftServer server = MinecraftServer.getServer();
        final double targetTps = TickRegionScheduler.getTickRate();
        if (server == null) {
            return targetTps;
        }

        final long tickInterval = TickRegionScheduler.getTimeBetweenTicks();
        final double[] worst = {targetTps};
        for (final ServerLevel level : server.getAllLevels()) {
            level.regioniser.computeForAllRegionsUnsynchronised(region -> {
                final Double average = region.getData().getRegionSchedulingHandle().tickTimes5s.getTPSAverage(null, tickInterval);
                if (average != null && Double.isFinite(average)) {
                    worst[0] = Math.min(worst[0], average);
                }
            });
        }
        return worst[0];
    }

    private static PressureState classify(final AdaptivePerformanceConfiguration configuration) {
        final AdaptivePerformanceConfiguration.PressureThresholds thresholds = configuration.pressure;

        if (heapPercent >= thresholds.heapEmergencyPercent
            || cpuPercent >= thresholds.cpuEmergencyPercent
            || gcPercent >= thresholds.gcEmergencyPercent
            || (thresholds.useRegionTps && worstRegionTps <= thresholds.regionEmergencyTps)
            || (thresholds.useAsyncQueuePressure && asyncQueuePercent >= thresholds.asyncQueueEmergencyPercent)
            || (thresholds.useSystemMemory && systemMemoryPercent >= thresholds.systemMemoryEmergencyPercent)) {
            return PressureState.EMERGENCY;
        }
        if (heapPercent >= thresholds.heapHighPercent
            || cpuPercent >= thresholds.cpuHighPercent
            || gcPercent >= thresholds.gcHighPercent
            || (thresholds.useRegionTps && worstRegionTps <= thresholds.regionHighTps)
            || (thresholds.useAsyncQueuePressure && asyncQueuePercent >= thresholds.asyncQueueHighPercent)
            || (thresholds.useSystemMemory && systemMemoryPercent >= thresholds.systemMemoryHighPercent)) {
            return PressureState.HIGH;
        }
        if (heapPercent >= thresholds.heapBusyPercent
            || cpuPercent >= thresholds.cpuBusyPercent
            || gcPercent >= thresholds.gcBusyPercent
            || (thresholds.useRegionTps && worstRegionTps <= thresholds.regionBusyTps)
            || (thresholds.useAsyncQueuePressure && asyncQueuePercent >= thresholds.asyncQueueBusyPercent)
            || (thresholds.useSystemMemory && systemMemoryPercent >= thresholds.systemMemoryBusyPercent)) {
            return PressureState.BUSY;
        }
        return PressureState.NORMAL;
    }

    private static boolean canRecoverFrom(
        final PressureState current,
        final AdaptivePerformanceConfiguration configuration
    ) {
        final AdaptivePerformanceConfiguration.PressureThresholds thresholds = configuration.pressure;
        final double hysteresis = configuration.recoveryHysteresisPercent;
        final double regionMargin = thresholds.regionRecoveryTpsMargin;

        return switch (current) {
            case NORMAL -> true;
            case BUSY -> heapPercent < thresholds.heapBusyPercent - hysteresis
                && cpuPercent < thresholds.cpuBusyPercent - hysteresis
                && gcPercent < Math.max(0.0D, thresholds.gcBusyPercent - hysteresis)
                && (!thresholds.useRegionTps || worstRegionTps > thresholds.regionBusyTps + regionMargin)
                && (!thresholds.useAsyncQueuePressure || asyncQueuePercent < Math.max(0.0D, thresholds.asyncQueueBusyPercent - hysteresis))
                && (!thresholds.useSystemMemory || systemMemoryPercent < thresholds.systemMemoryBusyPercent - hysteresis);
            case HIGH -> heapPercent < thresholds.heapHighPercent - hysteresis
                && cpuPercent < thresholds.cpuHighPercent - hysteresis
                && gcPercent < Math.max(0.0D, thresholds.gcHighPercent - hysteresis)
                && (!thresholds.useRegionTps || worstRegionTps > thresholds.regionHighTps + regionMargin)
                && (!thresholds.useAsyncQueuePressure || asyncQueuePercent < Math.max(0.0D, thresholds.asyncQueueHighPercent - hysteresis))
                && (!thresholds.useSystemMemory || systemMemoryPercent < thresholds.systemMemoryHighPercent - hysteresis);
            case EMERGENCY -> heapPercent < thresholds.heapEmergencyPercent - hysteresis
                && cpuPercent < thresholds.cpuEmergencyPercent - hysteresis
                && gcPercent < Math.max(0.0D, thresholds.gcEmergencyPercent - hysteresis)
                && (!thresholds.useRegionTps || worstRegionTps > thresholds.regionEmergencyTps + regionMargin)
                && (!thresholds.useAsyncQueuePressure || asyncQueuePercent < Math.max(0.0D, thresholds.asyncQueueEmergencyPercent - hysteresis))
                && (!thresholds.useSystemMemory || systemMemoryPercent < thresholds.systemMemoryEmergencyPercent - hysteresis);
        };
    }

    private static void transitionTo(
        final PressureState next,
        final AdaptivePerformanceConfiguration configuration
    ) {
        final PressureState previous = pressureState;
        pressureState = next;

        AdaptiveAsyncExecutor.onPressureStateChange(next);
        scheduleDistanceAdjustment(next, configuration);

        if (configuration.logStateTransitions) {
            final String message = String.format(
                "Pressure state %s -> %s (heap=%.1f%%, cpu=%.1f%%, gc=%.1f%%, system-ram=%.1f%%, worst-region=%.2f TPS, async-queue=%.1f%%, async-active=%d, async-pool=%d)",
                previous,
                next,
                heapPercent,
                cpuPercent,
                gcPercent,
                systemMemoryPercent,
                worstRegionTps,
                asyncQueuePercent,
                AdaptiveAsyncExecutor.getActiveThreadCount(),
                AdaptiveAsyncExecutor.getPoolSize()
            );
            if (next.ordinal() >= PressureState.HIGH.ordinal()) {
                LOGGER.warn(message);
            } else {
                LOGGER.info(message);
            }
        }
    }

    private static void scheduleDistanceAdjustment(
        final PressureState state,
        final AdaptivePerformanceConfiguration configuration
    ) {
        if (!configuration.adaptiveDistances.enabled || MinecraftServer.getServer() == null) {
            return;
        }

        RegionizedServer.getInstance().addTask(() -> {
            final AdaptivePerformanceConfiguration.AdaptiveDistances distances = configuration.adaptiveDistances;

            for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                final ResourceKey<Level> key = level.dimension();

                if (state == PressureState.NORMAL) {
                    final DistanceSnapshot snapshot = BASE_DISTANCES.remove(key);
                    if (snapshot != null) {
                        applyDistances(level, snapshot.viewDistance, snapshot.simulationDistance);
                    }
                    continue;
                }

                final DistanceSnapshot base = BASE_DISTANCES.computeIfAbsent(
                    key,
                    ignored -> new DistanceSnapshot(
                        level.serverLevelData.canvas$distanceConfig.viewDistanceOrDefault(),
                        level.serverLevelData.canvas$distanceConfig.simulationDistanceOrDefault()
                    )
                );

                final int viewReduction = switch (state) {
                    case NORMAL -> 0;
                    case BUSY -> distances.busyViewReduction;
                    case HIGH -> distances.highViewReduction;
                    case EMERGENCY -> distances.emergencyViewReduction;
                };
                final int simulationReduction = switch (state) {
                    case NORMAL -> 0;
                    case BUSY -> distances.busySimulationReduction;
                    case HIGH -> distances.highSimulationReduction;
                    case EMERGENCY -> distances.emergencySimulationReduction;
                };

                final int view = Math.max(distances.minimumViewDistance, base.viewDistance - viewReduction);
                final int simulation = Math.max(distances.minimumSimulationDistance, base.simulationDistance - simulationReduction);
                applyDistances(level, view, simulation);
            }
        });
    }

    private static void applyDistances(final ServerLevel level, final int viewDistance, final int simulationDistance) {
        level.getChunkSource().chunkMap.setServerViewDistance(viewDistance);
        level.getChunkSource().chunkMap.getDistanceManager().updateSimulationDistance(simulationDistance);
    }

    private static void checkNativeThreadPressure(
        final long now,
        final AdaptivePerformanceConfiguration configuration
    ) {
        final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        final int threadCount = threadBean.getThreadCount();
        if (threadCount < configuration.nativeThreadWarningThreshold) {
            return;
        }

        // Do not flood logs when a plugin leaks threads.
        if (now - lastThreadWarningNanos < 60_000_000_000L) {
            return;
        }
        lastThreadWarningNanos = now;
        LOGGER.warn(
            "High native/platform thread count detected: {} live threads. This can lead to 'unable to create native thread'. " +
                "Check plugins creating their own executors/threads.",
            threadCount
        );
    }

    public static boolean shouldSkipHopper(final long gameTime, final long positionSalt, final boolean idleOrBlocked) {
        if (!idleOrBlocked) {
            return false;
        }
        final AdaptivePerformanceConfiguration configuration = AdaptivePerformanceConfiguration.getInstance();
        if (!configuration.enabled || !configuration.hopperBackoff.enabled) {
            return false;
        }
        return shouldSkipByInterval(gameTime, positionSalt, intervalFor(
            configuration.hopperBackoff.busyInterval,
            configuration.hopperBackoff.highInterval,
            configuration.hopperBackoff.emergencyInterval
        ));
    }

    public static boolean shouldSkipSpawner(final long gameTime, final long positionSalt) {
        final AdaptivePerformanceConfiguration configuration = AdaptivePerformanceConfiguration.getInstance();
        if (!configuration.enabled || !configuration.spawnerBackoff.enabled) {
            return false;
        }
        return shouldSkipByInterval(gameTime, positionSalt, intervalFor(
            configuration.spawnerBackoff.busyInterval,
            configuration.spawnerBackoff.highInterval,
            configuration.spawnerBackoff.emergencyInterval
        ));
    }

    public static boolean shouldSkipNaturalSpawning(final long gameTime, final long chunkSalt) {
        final AdaptivePerformanceConfiguration configuration = AdaptivePerformanceConfiguration.getInstance();
        if (!configuration.enabled || !configuration.naturalSpawningBackoff.enabled) {
            return false;
        }
        return shouldSkipByInterval(gameTime, chunkSalt, intervalFor(
            configuration.naturalSpawningBackoff.busyInterval,
            configuration.naturalSpawningBackoff.highInterval,
            configuration.naturalSpawningBackoff.emergencyInterval
        ));
    }

    public static boolean shouldSkipChunkTick(final long gameTime, final long chunkSalt) {
        final AdaptivePerformanceConfiguration configuration = AdaptivePerformanceConfiguration.getInstance();
        if (!configuration.enabled || !configuration.chunkTickBackoff.enabled) {
            return false;
        }
        return shouldSkipByInterval(gameTime, chunkSalt, intervalFor(
            configuration.chunkTickBackoff.busyInterval,
            configuration.chunkTickBackoff.highInterval,
            configuration.chunkTickBackoff.emergencyInterval
        ));
    }

    private static int intervalFor(final int busy, final int high, final int emergency) {
        return switch (pressureState) {
            case NORMAL -> 1;
            case BUSY -> Math.max(1, busy);
            case HIGH -> Math.max(1, high);
            case EMERGENCY -> Math.max(1, emergency);
        };
    }

    private static boolean shouldSkipByInterval(final long gameTime, final long salt, final int interval) {
        if (interval <= 1) {
            return false;
        }
        return Math.floorMod(gameTime + salt, interval) != 0;
    }

    private static long totalGcCollectionMillis() {
        long total = 0L;
        for (final GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long time = bean.getCollectionTime();
            if (time > 0L) {
                total += time;
            }
        }
        return total;
    }

    public static PressureState getPressureState() {
        return pressureState;
    }

    public static double getHeapPercent() {
        return heapPercent;
    }

    public static double getCpuPercent() {
        return cpuPercent;
    }

    public static double getGcPercent() {
        return gcPercent;
    }

    public static double getSystemMemoryPercent() {
        return systemMemoryPercent;
    }

    public static double getAsyncQueuePercent() {
        return asyncQueuePercent;
    }

    public static double getWorstRegionTps() {
        return worstRegionTps;
    }

    private record DistanceSnapshot(int viewDistance, int simulationDistance) {
    }
}
