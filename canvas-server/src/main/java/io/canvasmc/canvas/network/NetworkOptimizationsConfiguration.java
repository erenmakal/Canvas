package io.canvasmc.canvas.network;

import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.configuration.ConfigurationProvider;
import io.canvasmc.canvas.configuration.Part;
import io.canvasmc.canvas.configuration.Resolver;
import io.canvasmc.canvas.configuration.Style;
import io.canvasmc.canvas.configuration.Validator;
import java.nio.file.Path;
import org.jetbrains.annotations.UnknownNullability;
import org.jspecify.annotations.NullMarked;

/**
 * Advanced network optimizations for Canvas.
 *
 * <p>This configuration is intentionally isolated from the regular Canvas configuration because
 * several options alter Netty scheduling or move packet preparation away from region threads and
 * therefore require a full restart to be applied predictably.</p>
 */
@SuppressWarnings({"FieldMayBeFinal", "unused"})
@NullMarked
public final class NetworkOptimizationsConfiguration extends Part {

    private static final Path CONFIG_PATH = Path.of("config/canvas-network.yml").toAbsolutePath().normalize();

    @UnknownNullability("nonnull after reload is called")
    private static NetworkOptimizationsConfiguration INSTANCE;

    static {
        reload();
    }

    private NetworkOptimizationsConfiguration() {
    }

    /** Forces class initialization during startup when desired. */
    public static void init() {
        // no-op
    }

    public static NetworkOptimizationsConfiguration getInstance() {
        return INSTANCE;
    }

    public static void reload() {
        GlobalConfiguration.LOGGER.info("Loading Canvas advanced network configuration");
        ConfigurationProvider.buildSolidConfiguration(
            CONFIG_PATH,
            NetworkOptimizationsConfiguration::new,
            90,
            new Resolver<>() {
                @Override
                public void onDiffAdd(final String fullyQualifiedName) {
                    GlobalConfiguration.LOGGER.info("Added new advanced network option: \"{}\"", fullyQualifiedName);
                }

                @Override
                public void onDiffRemove(final String fullyQualifiedName) {
                    GlobalConfiguration.LOGGER.warn("Advanced network option \"{}\" no longer exists and was removed.", fullyQualifiedName);
                }

                @Override
                public void onFinishLoad(final NetworkOptimizationsConfiguration instance) {
                    Validator.validateObject(instance);
                    INSTANCE = instance;

                    if (instance.packetSending.optimizeNonFlushPacketSending) {
                        GlobalConfiguration.LOGGER.warn(
                            "Canvas network optimization optimizeNonFlushPacketSending is enabled. " +
                                "Test packet-intercepting plugins carefully; ProtocolLib-style pipeline modifications can be incompatible."
                        );
                    }
                    if (instance.chunkSending.asyncPacketPreparation) {
                        GlobalConfiguration.LOGGER.warn(
                            "Canvas async chunk packet preparation is enabled (workers={}, per-player pending={}, executor queue={}). " +
                                "This is an experimental high-player-count optimization; profile and test before production rollout.",
                            instance.chunkSending.workerThreads,
                            instance.chunkSending.maxPendingPerPlayer,
                            instance.chunkSending.executorQueueCapacity
                        );
                    }
                }
            },
            Style.create()
                .literal("Advanced Network Configuration for CanvasMC").endLine()
                .blank()
                .wordWrap(
                    "These options target high-player-count servers where Netty wakeups, login protocol transitions,",
                    "and chunk packet preparation can consume noticeable CPU time. Defaults prioritize compatibility."
                ).endLine()
                .blank()
                .wordWrap(
                    "Most options in this file are derived from or inspired by production optimizations in Leaf 26.2,",
                    "adapted for Canvas/Folia's region-threaded architecture. Change one feature at a time and profile it."
                ).endLine()
                .blank()
                .wordWrap(
                    "A full restart is recommended after changing this file. Do not use /reload to evaluate networking changes."
                )
                .compile(72)
        );
    }

    public PacketSending packetSending = new PacketSending();

    public static final class PacketSending extends Part {
        {
            option("optimizeNonFlushPacketSending")
                .docs(
                    Style.wrap(
                        "Uses Netty SingleThreadEventLoop.lazyExecute for packet writes that deliberately do not flush.",
                        "Entity tracking and other high-volume paths send many non-flush packets; avoiding an event-loop wakeup",
                        "for every one of those writes can reduce scheduler/syscall overhead substantially at high player counts.",
                        "Leaf reports this optimization can materially reduce entity-tracker cost in packet-heavy workloads."
                    ).blank()
                        .wordWrap(
                            "Compatibility warning: packet interception plugins that rely on unusual Netty scheduling assumptions",
                            "may not behave correctly. Keep this disabled if ProtocolLib or similar low-level pipeline plugins misbehave."
                        )
                );
        }

        public boolean optimizeNonFlushPacketSending = false;
    }

    public ProtocolSwitch protocolSwitch = new ProtocolSwitch();

    public static final class ProtocolSwitch extends Part {
        {
            option("asyncSwitchConnectionState")
                .docs(
                    Style.wrap(
                        "Avoids synchronously waiting on Netty while switching LOGIN -> CONFIGURATION -> GAME protocols.",
                        "The protocol pipeline change is completed through a ChannelFuture listener and processing continues",
                        "after Netty has applied the new handlers. This primarily targets join spikes and proxy-heavy servers."
                    ).blank()
                        .wordWrap(
                            "This changes ordering around connection-state transitions. Keep disabled if a protocol/plugin stack",
                            "depends on synchronous state switching, and enable only after testing reconnects, transfers and joins."
                        )
                );
        }

        public boolean asyncSwitchConnectionState = false;
    }

    public ChunkSending chunkSending = new ChunkSending();

    public static final class ChunkSending extends Part {
        {
            option("asyncPacketPreparation")
                .docs(
                    Style.wrap(
                        "Prepares ClientboundLevelChunkWithLightPacket data on a dedicated executor instead of the player's region thread.",
                        "This can reduce region-thread stalls when many players rapidly load chunks, especially after teleports or joins.",
                        "The send/registration bookkeeping still returns to the owning region thread."
                    ).blank()
                        .wordWrap(
                            "Experimental: chunk packet creation reads chunk section data off-thread. Leaf uses the same basic approach,",
                            "but Folia/Canvas servers should validate plugin compatibility and world mutation workloads before production use."
                        )
                );

            option("workerThreads")
                .docs(
                    "Number of dedicated chunk packet preparation workers. Start with 1. For large many-core servers, test 2-4 only if profiling shows a queue backlog."
                )
                .between(1, 16);

            option("maxPendingPerPlayer")
                .docs(
                    "Maximum number of chunk packet preparation jobs that one player may have in flight. Lower values provide stronger backpressure and reduce memory spikes."
                )
                .between(16, 1024);

            option("executorQueueCapacity")
                .docs(
                    "Global bounded executor queue for chunk packet preparation. When saturated, CallerRunsPolicy applies backpressure to the owning region thread instead of allocating without limit."
                )
                .between(64, 65536);
        }

        public boolean asyncPacketPreparation = false;
        public int workerThreads = 1;
        public int maxPendingPerPlayer = 128;
        public int executorQueueCapacity = 4096;
    }
}
