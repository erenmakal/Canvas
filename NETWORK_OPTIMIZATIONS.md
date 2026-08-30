# Canvas Network Optimizations

This fork contains an opt-in network tuning layer for high-player-count Folia/Canvas servers.
The implementation is based on current Leaf 26.2 ideas where they fit Canvas' region-threaded
architecture, with additional backpressure and compatibility guards.

Configuration is generated at `config/canvas-network.yml`. All behavior-changing options default
to `false` so updating the fork does not silently change packet ordering or plugin compatibility.
A full restart is recommended after changing these options.

## Packet scheduling

### `packetSending.optimizeNonFlushPacketSending`

Uses Netty `SingleThreadEventLoop.lazyExecute` for writes where Minecraft intentionally does not
flush the channel yet. Entity tracking can queue a very large number of these writes, and a normal
`execute` can cause an unnecessary event-loop wakeup for each scheduling operation.

Canvas keeps a normal `execute` fallback when the active event loop is not a
`SingleThreadEventLoop`. This makes the port more defensive around custom transports.

**Expected benefit:** lower scheduling/syscall overhead in packet-heavy entity tracking workloads.

**Compatibility:** leave disabled when using ProtocolLib or another plugin that relies on unusual
Netty pipeline/scheduling behavior until the complete plugin stack has been tested.

## Connection state switching

### `protocolSwitch.asyncSwitchConnectionState`

Moves LOGIN -> CONFIGURATION -> GAME pipeline transitions away from synchronous waits. The Netty
pipeline change is written first and the continuation runs from the `ChannelFuture` completion.
This is intended to reduce region/tick-thread blocking during large join spikes, reconnect waves,
or proxy transfers.

**Expected benefit:** smoother join bursts and less time spent waiting for Netty from a tick/region
thread.

**Compatibility:** protocol translation, packet interception, custom login and transfer plugins
should be tested carefully because this changes the timing of protocol state transitions.

## Chunk packet preparation

### `chunkSending.asyncPacketPreparation`

Prepares `ClientboundLevelChunkWithLightPacket` data on dedicated low-priority workers. Mutable
block-entity and heightmap inputs are snapshotted before the task is submitted, while player chunk
registration and packet sending are returned to the owning player/region flow.

Unlike a simple unbounded executor, Canvas uses two levels of backpressure:

- `chunkSending.maxPendingPerPlayer` caps work in flight for a single player.
- `chunkSending.executorQueueCapacity` caps the global waiting queue.
- When the executor is saturated, `CallerRunsPolicy` makes the producer perform the work rather
  than allocating an unlimited backlog.

This is important for high concurrency. A teleport event or join spike involving hundreds of
players can otherwise create a large temporary queue of expensive chunk serialization jobs.

### `chunkSending.workerThreads`

Start with `1`. More workers are not automatically faster because chunk serialization competes
with region scheduler, chunk workers, GC and Netty for CPU/cache bandwidth. Increase to `2`, then
`3-4`, only when profiling shows the async queue is the bottleneck and the CPU has headroom.

### `chunkSending.maxPendingPerPlayer`

Default: `128`. This is intentionally lower than Leaf's fixed per-player capacity so one player
moving quickly cannot occupy most of the global work queue. For a 500-player survival server,
`64-128` is a sensible initial test range.

### `chunkSending.executorQueueCapacity`

Default: `4096`. This is a safety bound, not a performance target. A queue that stays near this
limit means the workers cannot keep up; adding a huge queue only delays the problem and increases
memory/latency.

## Existing Canvas packet reductions

Canvas already contains packet filtering options such as `filterVelocityPacket` and
`filterMovePackets`. They are intentionally not duplicated here. The advanced network layer is
focused on missing scheduling, connection-transition and chunk-serialization costs.

## Suggested rollout for a high-player-count survival server

1. Baseline with Spark/region profiling and OS network/CPU metrics.
2. Enable only `optimizeNonFlushPacketSending`, restart and compare entity tracker + Netty CPU.
3. Test login/proxy/plugin compatibility, then optionally enable `asyncSwitchConnectionState`.
4. Enable async chunk packet preparation with one worker, `maxPendingPerPlayer: 64-128`, and the
   default bounded executor queue.
5. Only raise worker count after profiling. Do not tune by player count alone.
6. Stress test RTP/teleports, mass joins, Elytra movement, anti-xray, Geyser/ViaVersion and any
   packet-intercepting plugins before production rollout.

## Why io_uring is not included in this change

Leaf 26.2 also has an io_uring transport patch. This change deliberately does not copy it yet:
Canvas currently follows a different Netty dependency setup and adding native transport support
would couple a network optimization change to dependency/classifier and Linux-native packaging
changes. It is better evaluated as a separate patch with its own Linux CI and fallback testing.
