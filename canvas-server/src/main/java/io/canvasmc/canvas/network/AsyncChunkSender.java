package io.canvasmc.canvas.network;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Per-player state for asynchronous chunk packet preparation. */
@NullMarked
public final class AsyncChunkSender {

    public record PreparedChunk(
        long chunkKey,
        @Nullable ClientboundLevelChunkWithLightPacket packet,
        @Nullable Throwable error
    ) {
    }

    private final ConcurrentLinkedQueue<PreparedChunk> ready = new ConcurrentLinkedQueue<>();
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final int capacity = NetworkOptimizationsConfiguration.getInstance().chunkSending.maxPendingPerPlayer;
    private int inFlight;

    public boolean add(final long chunkKey) {
        return this.inFlight < this.capacity && this.pending.size() < this.capacity && this.pending.add(chunkKey);
    }

    public boolean remove(final long chunkKey) {
        return this.pending.remove(chunkKey);
    }

    public boolean contains(final long chunkKey) {
        return this.pending.contains(chunkKey);
    }

    public void clear() {
        this.pending.clear();
        while (this.recv() != null) {
            // Drain completed work. In-flight tasks may still finish later, but their pending key has
            // already been removed so they will be ignored when the owning player state is discarded.
        }
    }

    public void submit(final long chunkKey, final Supplier<ClientboundLevelChunkWithLightPacket> task) {
        this.inFlight++;
        AsyncChunkSendExecutor.executor().execute(() -> {
            try {
                this.ready.add(new PreparedChunk(chunkKey, task.get(), null));
            } catch (final Throwable throwable) {
                this.ready.add(new PreparedChunk(chunkKey, null, throwable));
            }
        });
    }

    public @Nullable PreparedChunk recv() {
        final PreparedChunk prepared = this.ready.poll();
        if (prepared != null) {
            this.inFlight--;
        }
        return prepared;
    }
}
