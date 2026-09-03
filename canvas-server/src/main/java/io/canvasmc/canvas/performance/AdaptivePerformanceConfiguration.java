package io.canvasmc.canvas.performance;

import io.canvasmc.canvas.configuration.ConfigurationProvider;
import io.canvasmc.canvas.configuration.Part;
import io.canvasmc.canvas.configuration.Resolver;
import io.canvasmc.canvas.configuration.Style;
import io.canvasmc.canvas.configuration.Validator;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for the adaptive performance controller used by this fork.
 *
 * <p>The controller is intentionally isolated from Canvas' normal configuration so upstream
 * updates do not have to carry a large {@code GlobalConfiguration} diff. The file is generated
 * as {@code config/canvas-adaptive.yml}.</p>
 */
@SuppressWarnings({"FieldMayBeFinal", "unused"})
public final class AdaptivePerformanceConfiguration extends Part {

    private static final Logger LOGGER = LoggerFactory.getLogger("CanvasAdaptive");
    private static final Path CONFIG_PATH = Path.of("config/canvas-adaptive.yml").toAbsolutePath().normalize();
    private static final int CHAR_LIMIT = 90;

    private static volatile AdaptivePerformanceConfiguration INSTANCE;

    static {
        reload();
    }

    public static AdaptivePerformanceConfiguration getInstance() {
        return INSTANCE;
    }

    public static void reload() {
        ConfigurationProvider.buildSolidConfiguration(
            CONFIG_PATH,
            AdaptivePerformanceConfiguration::new,
            CHAR_LIMIT,
            new Resolver<>() {
                @Override
                public void onDiffAdd(final String fullyQualifiedName) {
                    LOGGER.info("Added adaptive performance option: \"{}\"", fullyQualifiedName);
                }

                @Override
                public void onDiffRemove(final String fullyQualifiedName) {
                    LOGGER.warn("Adaptive performance option \"{}\" no longer exists and was removed", fullyQualifiedName);
                }

                @Override
                public void onFinishLoad(final AdaptivePerformanceConfiguration instance) {
                    Validator.validateObject(instance);
                    INSTANCE = instance;
                    LOGGER.info(
                        "Adaptive performance controller loaded (enabled={}, async-mode={})",
                        instance.enabled,
                        instance.asyncScheduler.mode
                    );
                }
            },
            Style.create()
                .literal("Adaptive Performance Configuration for CanvasMC").endLine()
                .blank()
                .wordWrap(
                    "This file controls pressure-aware CPU/RAM protection added by this fork.",
                    "The controller reacts to heap, CPU and GC pressure and can temporarily reduce expensive work.",
                    "All changes recover automatically when the server returns to a healthy state."
                ).endLine()
                .blank()
                .wordWrap(
                    "These features are designed for large Folia survival servers. Hopper/spawner backoff and",
                    "adaptive view/simulation distances can slightly alter timing while the server is under pressure."
                )
                .compile(60)
        );
    }

    {
        option("sampleIntervalMillis").docs("How often pressure metrics are sampled in milliseconds").greaterThanOrEqualTo(250.0F);
        option("recoveryHysteresisPercent").docs("Hysteresis used when recovering to a lower pressure state").between(0.0F, 25.0F);
        option("nativeThreadWarningThreshold").docs("Warn when the JVM has at least this many live platform threads").greaterThanOrEqualTo(32.0F);
    }

    public boolean enabled = true;
    public int sampleIntervalMillis = 1000;
    public double recoveryHysteresisPercent = 5.0D;
    public boolean logStateTransitions = true;
    public int nativeThreadWarningThreshold = 512;

    public PressureThresholds pressure = new PressureThresholds();
    public static final class PressureThresholds extends Part {
        {
            option("heapBusyPercent").between(1.0F, 100.0F);
            option("heapHighPercent").between(1.0F, 100.0F);
            option("heapEmergencyPercent").between(1.0F, 100.0F);
            option("cpuBusyPercent").between(1.0F, 100.0F);
            option("cpuHighPercent").between(1.0F, 100.0F);
            option("cpuEmergencyPercent").between(1.0F, 100.0F);
            option("gcBusyPercent").between(0.0F, 100.0F);
            option("gcHighPercent").between(0.0F, 100.0F);
            option("gcEmergencyPercent").between(0.0F, 100.0F);
        }

        public double heapBusyPercent = 72.0D;
        public double heapHighPercent = 82.0D;
        public double heapEmergencyPercent = 90.0D;

        public double cpuBusyPercent = 80.0D;
        public double cpuHighPercent = 90.0D;
        public double cpuEmergencyPercent = 97.0D;

        public double gcBusyPercent = 8.0D;
        public double gcHighPercent = 15.0D;
        public double gcEmergencyPercent = 30.0D;
    }

    public HopperBackoff hopperBackoff = new HopperBackoff();
    public static final class HopperBackoff extends Part {
        {
            option("busyInterval").greaterThanOrEqualTo(1.0F);
            option("highInterval").greaterThanOrEqualTo(1.0F);
            option("emergencyInterval").greaterThanOrEqualTo(1.0F);
        }

        public boolean enabled = true;
        public int busyInterval = 2;
        public int highInterval = 4;
        public int emergencyInterval = 8;
    }

    public SpawnerBackoff spawnerBackoff = new SpawnerBackoff();
    public static final class SpawnerBackoff extends Part {
        {
            option("busyInterval").greaterThanOrEqualTo(1.0F);
            option("highInterval").greaterThanOrEqualTo(1.0F);
            option("emergencyInterval").greaterThanOrEqualTo(1.0F);
        }

        public boolean enabled = true;
        public int busyInterval = 2;
        public int highInterval = 3;
        public int emergencyInterval = 5;
    }

    public NaturalSpawningBackoff naturalSpawningBackoff = new NaturalSpawningBackoff();
    public static final class NaturalSpawningBackoff extends Part {
        {
            option("busyInterval").greaterThanOrEqualTo(1.0F);
            option("highInterval").greaterThanOrEqualTo(1.0F);
            option("emergencyInterval").greaterThanOrEqualTo(1.0F);
        }

        public boolean enabled = true;
        public int busyInterval = 1;
        public int highInterval = 2;
        public int emergencyInterval = 4;
    }

    public AdaptiveDistances adaptiveDistances = new AdaptiveDistances();
    public static final class AdaptiveDistances extends Part {
        {
            option("minimumViewDistance").between(2, 32);
            option("minimumSimulationDistance").between(2, 32);
            option("busyViewReduction").greaterThanOrEqualTo(0.0F);
            option("busySimulationReduction").greaterThanOrEqualTo(0.0F);
            option("highViewReduction").greaterThanOrEqualTo(0.0F);
            option("highSimulationReduction").greaterThanOrEqualTo(0.0F);
            option("emergencyViewReduction").greaterThanOrEqualTo(0.0F);
            option("emergencySimulationReduction").greaterThanOrEqualTo(0.0F);
        }

        public boolean enabled = true;
        public int minimumViewDistance = 2;
        public int minimumSimulationDistance = 2;

        public int busyViewReduction = 0;
        public int busySimulationReduction = 1;
        public int highViewReduction = 1;
        public int highSimulationReduction = 1;
        public int emergencyViewReduction = 2;
        public int emergencySimulationReduction = 2;
    }

    public AsyncScheduler asyncScheduler = new AsyncScheduler();
    public static final class AsyncScheduler extends Part {
        {
            option("queueCapacity").greaterThanOrEqualTo(256.0F);
            option("keepAliveSeconds").greaterThanOrEqualTo(1.0F);
            option("minimumAdaptiveMaxThreads").greaterThanOrEqualTo(1.0F);
            option("busyMaxThreadsPercent").between(10.0F, 100.0F);
            option("highMaxThreadsPercent").between(10.0F, 100.0F);
            option("emergencyMaxThreadsPercent").between(10.0F, 100.0F);
        }

        public ExecutorMode mode = ExecutorMode.BOUNDED_PLATFORM;
        public int coreThreads = 0;
        public int maxThreads = 0;
        public int queueCapacity = 16384;
        public int keepAliveSeconds = 30;
        public boolean allowCoreThreadTimeout = true;
        public boolean adaptPlatformThreadLimit = true;
        public int minimumAdaptiveMaxThreads = 4;
        public int busyMaxThreadsPercent = 75;
        public int highMaxThreadsPercent = 50;
        public int emergencyMaxThreadsPercent = 25;
    }

    public enum ExecutorMode {
        BOUNDED_PLATFORM,
        VIRTUAL_THREADS
    }
}
