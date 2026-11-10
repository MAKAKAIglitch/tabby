# Tabby architecture

## Design goal

Tabby keeps one Minecraft client process while maintaining multiple logical client sessions. Minecraft still exposes many client-global fields, so switching tabs is a controlled install/restore operation rather than independent Minecraft instances.

The architecture separates the state boundaries that must remain correct during packet processing, connection teardown, tab ordering, rendering, and background-tab notifications. `TabManager` remains the compatibility façade used by mixins, screens, and event callbacks.

## Components

| Component | Owns | Does not own |
|---|---|---|
| `TabManager` | World-transition coordination, tick scheduling, stable API façade for mixins/UI | Tab registry, tab CRUD, connection policy, execution ThreadLocal, connection identity maps, optional renderer reflection |
| `TabRegistry` | Tab IDs, active ID, ordered membership, cached immutable context/ID snapshots | Network events or context installation |
| `TabLifecycleManager` | Tab creation, ordering, closing, resource disposal, integrated-server ownership and shutdown | Packet routing or UI rendering |
| `TabConnectionManager` | Connect attempts, disconnect policy, connection callbacks, background connection ticks, protocol-phase movement guard | Tab identity storage or tab ordering |
| `TabDamageNotificationManager` | Per-listener inactive-player damage filtering, event coalescing, chat and sound alerts | Packet ownership or active-tab lifecycle |
| `TabUnreadMessageManager` | Per-tab unread chat and local-player mention counts, clear-on-select and badge state | Chat packet routing or global chat history |
| `TabReconnectManager` | Bounded remote-session retry state, retry timers and automatic reconnect attempts per tab | Connection ownership or manual disconnect policy |
| `TabCrashGuard` | Logs and isolates recoverable runtime failures from hidden-tab packet/tick work | Active-tab failures or JVM-level Errors |
| `SimpleVoiceChatCompatibility` | Optional single-session SVC lifecycle rebinding to the selected tab; hidden-tab callback suppression is implemented by an optional mixin | Independent simultaneous voice sessions per tab; compile-time SVC dependency |
| `TabContext` | Snapshot of client fields belonging to one logical tab | Scheduling, connection routing, or tab ordering |
| `TabContextExecutor` | Scoped global-state installation, nested execution marker, restoration after packet tasks | Ownership discovery or tab lifecycle |
| `ConnectionOwnershipRegistry` | Identity mapping from `Connection` / `PacketListener` to tab IDs | Disconnect policy or Minecraft field mutation |
| `TabRendererCompatibility` | Active-renderer gate and optional Voxy reflection hooks | Tab selection or world lifecycle |
| `TabMemoryUsage` | Cached per-tab diagnostic estimates and registry-backed stale-entry cleanup | Actual JVM heap accounting or world unloading |
| `Baritone` optional integration | Runs the primary Baritone PRE/POST process callbacks inside the original main-tab context, keeps packet/world events owned by that tab, and blocks forced input from leaking to other tabs | Independent Baritone instances or hidden LocalPlayer physics simulation |

## Invariants

1. Structural changes to the tab registry and mutations to `TabContext` happen on Minecraft's client thread. Ordered snapshots are rebuilt only on structural changes and reused by tick/render paths.
2. Connection/listener ownership maps are identity-based and synchronized because connection callbacks may be observed from Netty threads.
3. The owner of a packet task is resolved when the task is queued, not when it is later executed.
4. Every outermost `runIn` scope marks its execution context. Nested `runIn` scopes restore the exact transient client state and do not publish half-completed login state into a persistent snapshot.
5. Background packet handlers never replace the visible screen.
6. Client-global voice-chat state belongs to the selected tab only. Background SVC JOIN/DISCONNECT callbacks are ignored, and selecting a different world rebinds the one SVC session.
7. Context restoration happens in a `finally` path, including when a packet task throws.
8. Only the selected tab's renderer enters the world-rendering pipeline. Optional-mod integrations remain reflection- or mixin-based so they do not add required runtime dependencies.

## Extension rules

- New mixin/UI entry points should call the existing `TabManager` façade rather than depend directly on registry internals.
- Connection identity mapping belongs in `ConnectionOwnershipRegistry`; connection and disconnect policy belongs in `TabConnectionManager`.
- Code that temporarily installs one tab's Minecraft globals belongs in `TabContextExecutor`.
- Renderer/vendor-specific behavior belongs in `TabRendererCompatibility`.
- When moving more responsibilities out of `TabManager`, preserve public static methods until all mixin and UI callers have been migrated and CI proves the behavior unchanged.

## Current limitations

This remains an incremental refactor: world-transition recovery and top-level tick orchestration remain in `TabManager`, while tab lifecycle and network policy are delegated to dedicated coordinators. Chat, inventory, audio, entity and arbitrary third-party-mod state are not yet fully isolated between tabs.


## User settings and memory policy

The settings screen is split into General, Appearance, and Performance pages. UI text is supplied through Minecraft's standard `en_us` and `ru_ru` resource bundles. Server addresses are the default display label; the server-provided name remains available as a setting. New configurations use a three-background-tab limit to reduce retained world/chunk/render resources. Existing saved limits are not overwritten during migration. No background `ClientLevel`, connection, or renderer is unloaded as a memory shortcut, because those objects are part of the live-tab invariant.


## Movement input across tab switches

A tab switch releases Minecraft's global key mappings. Each previous/target `LocalPlayer` then refreshes its own cached `ClientInput` once, so stale movement booleans from the last game tick cannot be replayed on the first tick after switching. This is input sampling only: it does not call player/world ticks or overwrite `deltaMovement`, so server-authoritative knockback remains intact.


Background damage feedback is owned by `TabDamageNotificationManager`: it stores a short per-tab visual timer when a hidden local player receives a damage event. The tab strip reads this timer and that context's own `LocalPlayer` health/food values. Both features default on and have independent settings. No player HUD, health, hunger, or world state is modified.


## Background chat, reconnect and failure containment

Chat lines are counted at the ChatComponent.addMessage(Component, MessageSignature, GuiMessageTag) boundary while the owning context is installed. The unread count and local-player mentions are kept by tab ID and cleared when the user selects that tab. A dedicated option can disable these badges.
Unexpected disconnects for remote tabs that have successfully joined are eligible for a bounded retry schedule (five attempts, five-second delay, 20-second attempt timeout). The retry state retains the original server data separately from the cleared world context; every attempt is started inside its owning tab scope. Manual disconnects, tab closure and integrated single-player connections are excluded.
A RuntimeException escaping a scoped background packet/tick task is recorded with its stack trace. The task's partial context is not captured; the previously selected context is restored, and client maintenance then disconnects the failing tab. Exceptions in the selected tab and JVM-level Error conditions continue to propagate normally. This reduces cross-tab failure propagation but cannot prevent every crash caused by arbitrary third-party code, native libraries, memory exhaustion or failures outside Tabby's scoped tasks.


## Deferred movement impulses on background tabs

Minecraft sends the local player velocity separately from ordinary player-position updates. Tabby observes own-player velocity and explosion-knockback packets while a tab is hidden. The packet updates that tab's retained LocalPlayer, and Tabby preserves its delta movement when the tab is selected again; it clears only the bookkeeping marker, so the next normal active player tick applies the server-authoritative knockback. Hidden player physics is not simulated while another tab is selected. Packets and movement in the selected tab remain untouched. The network smoke test exercises both velocity-packet and explosion-knockback paths.

## Baritone process ticks in a background main tab

Baritone's Fabric 1.21.4 integration uses one process-global `Minecraft` reference. Tabby's optional Baritone bridge therefore wraps Baritone's `GameEventHandler.onTick` and `onPostTick` calls in the original main-tab context. When another connected tab is selected, these process callbacks still see the main tab's player, world, renderer and connection state. They are skipped during world transitions or when the visible tab has no stable player/world, so a menu tab cannot generate an `OUT` event that cancels the main tab's path. Other Baritone events are accepted only when their owning context is main. When main is hidden behind a menu, Baritone's incoming tick is normalized to `IN` only while the retained main world is stable; the event's PRE/POST state and tick counter are preserved. The hidden LocalPlayer's ordinary Minecraft physics is not simulated; movement input is retained on that player and is consumed when the main tab is selected again. CI runs the pinned Baritone Fabric 1.13.1 release for Minecraft 1.21.4 with two live server tabs and a third menu tab, and observes actual background `onTick` callbacks.
