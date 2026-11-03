# Tabby architecture

## Design goal

Tabby keeps one Minecraft client process while maintaining multiple logical client sessions. Minecraft still exposes many client-global fields, so switching tabs is a controlled install/restore operation rather than independent Minecraft instances.

The architecture separates the state boundaries that must remain correct during packet processing, connection teardown, tab ordering, and rendering. `TabManager` remains the compatibility façade used by mixins, screens, and event callbacks.

## Components

| Component | Owns | Does not own |
|---|---|---|
| `TabManager` | World-transition coordination, tick scheduling, stable API façade for mixins/UI | Tab registry, tab CRUD, connection policy, execution ThreadLocal, connection identity maps, optional renderer reflection |
| `TabRegistry` | Tab IDs, active ID, ordered membership, read-only lookup view | Network events or context installation |
| `TabLifecycleManager` | Tab creation, ordering, closing, resource disposal, integrated-server ownership and shutdown | Packet routing or UI rendering |
| `TabConnectionManager` | Connect attempts, disconnect policy, connection callbacks, background connection ticks, protocol-phase movement guard | Tab identity storage or tab ordering |
| `TabContext` | Snapshot of client fields belonging to one logical tab | Scheduling, connection routing, or tab ordering |
| `TabContextExecutor` | Scoped global-state installation, nested execution marker, restoration after packet tasks | Ownership discovery or tab lifecycle |
| `ConnectionOwnershipRegistry` | Identity mapping from `Connection` / `PacketListener` to tab IDs | Disconnect policy or Minecraft field mutation |
| `TabRendererCompatibility` | Active-renderer gate and optional Voxy reflection hooks | Tab selection or world lifecycle |
| `TabMemoryUsage` | Cached per-tab diagnostic estimates | Actual JVM heap accounting |

## Invariants

1. Structural changes to the tab registry and mutations to `TabContext` happen on Minecraft's client thread.
2. Connection/listener ownership maps are identity-based and synchronized because connection callbacks may be observed from Netty threads.
3. The owner of a packet task is resolved when the task is queued, not when it is later executed.
4. Every outermost `runIn` scope marks its execution context. Nested `runIn` scopes restore the exact transient client state and do not publish half-completed login state into a persistent snapshot.
5. Background packet handlers never replace the visible screen.
6. Context restoration happens in a `finally` path, including when a packet task throws.
7. Only the selected tab's renderer enters the world-rendering pipeline. Optional-mod integrations remain reflection- or mixin-based so they do not add required runtime dependencies.

## Extension rules

- New mixin/UI entry points should call the existing `TabManager` façade rather than depend directly on registry internals.
- Connection identity mapping belongs in `ConnectionOwnershipRegistry`; connection and disconnect policy belongs in `TabConnectionManager`.
- Code that temporarily installs one tab's Minecraft globals belongs in `TabContextExecutor`.
- Renderer/vendor-specific behavior belongs in `TabRendererCompatibility`.
- When moving more responsibilities out of `TabManager`, preserve public static methods until all mixin and UI callers have been migrated and CI proves the behavior unchanged.

## Current limitations

This remains an incremental refactor: world-transition recovery and top-level tick orchestration remain in `TabManager`, while tab lifecycle and network policy are delegated to dedicated coordinators. Chat, inventory, audio, entity and arbitrary third-party-mod state are not yet fully isolated between tabs.
