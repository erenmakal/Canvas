# Adaptive Folia Performance Layer

This branch is based on the latest upstream `CraftCanvasMC/Canvas` main branch at commit `e63c4aa73635f15fadc65f543d1ada770e5aa79c`.

## Why the old custom network branch is not included

The previous `network-optimizations-leaf-26.2` branch is deliberately not layered on top of this branch. Current Canvas already contains network-side optimizations such as velocity packet filtering, move packet filtering, alternative player-list ticking, alternative keepalive behavior, region-aware packet handling and the Leaf-derived knockback location flush. Duplicating the previous custom async connection/chunk packet implementation would increase race-condition and patch-conflict risk without a clear benefit.

The adaptive work in this branch therefore focuses on CPU, heap/native-memory pressure, Folia task scheduling and expensive gameplay subsystems instead of reimplementing Canvas networking.

## Implemented adaptive systems

### Global pressure controller

`AdaptivePerformanceController` samples heap occupancy, system CPU utilization and GC collection pressure. It uses four states:

- `NORMAL`
- `BUSY`
- `HIGH`
- `EMERGENCY`

Recovery uses hysteresis so the server does not rapidly switch between states.

### Native-thread protection / async scheduler backpressure

Upstream Folia's async scheduler uses a `SynchronousQueue` and an effectively unlimited maximum platform-thread count. Under an async task storm this can result in:

`java.lang.OutOfMemoryError: unable to create native thread`

This branch replaces that executor with a configurable bounded executor by default. The queue applies backpressure instead of continuously allocating native threads. Java virtual threads can also be selected for blocking plugin I/O workloads.

When pressure increases, the maximum platform-thread count can be reduced automatically to leave CPU and native memory for region ticks, Netty and ZGC/G1.

### Native thread leak warning

The controller monitors the JVM's live platform-thread count and emits a rate-limited warning when the configured threshold is exceeded. This helps identify plugins that create their own unbounded executors outside the Folia scheduler.

### Adaptive view/simulation distance

When the server enters a pressure state, Canvas can temporarily reduce effective view/simulation distance without overwriting the stored world configuration. This reduces loaded/ticking chunks and therefore RAM, entity ticking and chunk-system CPU use. Original values are restored on recovery.

### Empty hopper backoff

Canvas already has substantial hopper optimizations. This branch adds a pressure-only polling backoff for empty hoppers. Active hoppers containing items keep their normal transfer cadence; empty hoppers can poll less frequently during load spikes.

### Spawner polling backoff

Spawner player-range checks are distributed across ticks during pressure states. This reduces repeated nearby-player/spawner work in large survival farms. The behavior is configurable and is disabled automatically in `NORMAL` state.

### Natural spawning backoff

Natural-spawn chunk processing can be spread across ticks in `HIGH` and `EMERGENCY` states. This reduces spawn scans and prevents new entity creation from amplifying an already overloaded server.

## Existing Canvas optimizations intentionally reused

This work does not duplicate systems Canvas already owns. Existing Canvas/Folia mechanisms continue to handle region scheduling, affinity/work distribution, region ownership, optimized natural-spawn collection, hopper state caching, block-entity sleeping, entity/network filtering and other upstream performance work.

## Configuration

A new file is generated automatically:

`config/canvas-adaptive.yml`

The defaults are aimed at a large Folia survival server and are intentionally conservative in `BUSY`, becoming more aggressive only in `HIGH`/`EMERGENCY`.

Important defaults:

```yaml
enabled: true
sample-interval-millis: 1000
recovery-hysteresis-percent: 5.0
native-thread-warning-threshold: 512

pressure:
  heap-busy-percent: 72.0
  heap-high-percent: 82.0
  heap-emergency-percent: 90.0
  cpu-busy-percent: 80.0
  cpu-high-percent: 90.0
  cpu-emergency-percent: 97.0

async-scheduler:
  mode: BOUNDED_PLATFORM
  queue-capacity: 16384

adaptive-distances:
  enabled: true
  minimum-view-distance: 2
  minimum-simulation-distance: 2
```

## Design rules

- Never touch region/entity state from an unowned thread.
- Prefer backpressure over unbounded thread creation.
- Do not persist emergency distance reductions.
- Avoid duplicate network rewrites already present in current Canvas.
- All gameplay-affecting throttles are configurable and pressure-gated.
- Keep the normal state as close to upstream Canvas behavior as possible.

## Future invasive work

Some ideas discussed for a deeper experimental fork are deliberately not mixed into this first production-safe layer because they require large behavioral or architectural rewrites: data-oriented entity storage, flow-field pathfinding, async navigation snapshots, predictive region split/merge, NUMA topology scheduling, plugin CPU quotas and full per-region memory accounting. Those should be benchmarked and introduced as isolated patches rather than bundled into one untestable change.
