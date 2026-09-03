package io.canvasmc.canvas.performance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Low-overhead per-plugin event CPU accounting.
 *
 * <p>By default this is telemetry-only. Optional emergency enforcement can skip listeners after
 * a plugin exceeds its configured event CPU budget, but that mode is deliberately opt-in because
 * arbitrary listener skipping can change protection/economy/gameplay behaviour.</p>
 */
public final class PluginPerformanceTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("CanvasPluginBudget");
    private static final ConcurrentHashMap<String, Window> WINDOWS = new ConcurrentHashMap<>();

    private PluginPerformanceTracker() {
    }

    public static long begin() {
        final AdaptivePerformanceConfiguration.PluginBudget config =
            AdaptivePerformanceConfiguration.getInstance().pluginBudget;
        return config.enabled ? System.nanoTime() : 0L;
    }

    public static boolean shouldSkip(final Plugin plugin) {
        final AdaptivePerformanceConfiguration.PluginBudget config =
            AdaptivePerformanceConfiguration.getInstance().pluginBudget;
        if (!config.enabled || !config.enforceInEmergency
            || AdaptivePerformanceController.getPressureState() != AdaptivePerformanceController.PressureState.EMERGENCY) {
            return false;
        }

        final Window window = WINDOWS.get(plugin.getName());
        if (window == null) {
            return false;
        }
        window.rotateIfNeeded(config);
        return window.cpuNanos.get() >= budgetNanos(config);
    }

    public static void end(final Plugin plugin, final Event event, final long startedNanos) {
        if (startedNanos == 0L) {
            return;
        }

        final AdaptivePerformanceConfiguration.PluginBudget config =
            AdaptivePerformanceConfiguration.getInstance().pluginBudget;
        if (!config.enabled) {
            return;
        }

        final long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
        final Window window = WINDOWS.computeIfAbsent(plugin.getName(), ignored -> new Window());
        window.rotateIfNeeded(config);
        final long used = window.cpuNanos.addAndGet(elapsed);
        window.calls.incrementAndGet();

        final boolean slowCall = elapsed >= config.slowListenerMicros * 1_000L;
        final boolean overBudget = used >= budgetNanos(config);
        if ((slowCall || overBudget) && window.tryAcquireWarning(config)) {
            LOGGER.warn(
                "Plugin {} event CPU pressure: event={}, last={} us, window={} ms / budget={} ms, calls={}, pressure={}",
                plugin.getName(),
                event.getEventName(),
                elapsed / 1_000L,
                used / 1_000_000L,
                config.budgetMillisPerWindow,
                window.calls.get(),
                AdaptivePerformanceController.getPressureState()
            );
        }
    }

    private static long budgetNanos(final AdaptivePerformanceConfiguration.PluginBudget config) {
        return Math.max(1L, (long)(config.budgetMillisPerWindow * 1_000_000.0D));
    }

    private static final class Window {
        private final AtomicLong startedMillis = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong cpuNanos = new AtomicLong();
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong lastWarningMillis = new AtomicLong();

        private void rotateIfNeeded(final AdaptivePerformanceConfiguration.PluginBudget config) {
            final long now = System.currentTimeMillis();
            final long started = this.startedMillis.get();
            if (now - started < config.windowMillis) {
                return;
            }
            if (this.startedMillis.compareAndSet(started, now)) {
                this.cpuNanos.set(0L);
                this.calls.set(0L);
            }
        }

        private boolean tryAcquireWarning(final AdaptivePerformanceConfiguration.PluginBudget config) {
            final long now = System.currentTimeMillis();
            final long cooldown = Math.max(1L, config.warningCooldownSeconds) * 1_000L;
            final long previous = this.lastWarningMillis.get();
            return now - previous >= cooldown && this.lastWarningMillis.compareAndSet(previous, now);
        }
    }
}
