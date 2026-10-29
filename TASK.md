# Tabby — implementation task

## Goal

Create a client-side Fabric mod for Minecraft 1.21.4 that provides browser-style tabs in a single Minecraft client and account. Each network tab should retain its own world, player, GUI, and server connection while another tab is active. The current prototype shares the particle engine because Fabric API's particle factory registry cannot be initialized a second time; background particle effects are suppressed.

## Fixed constraints

- Minecraft 1.21.4; Java 21; Fabric Loader 0.16.x; Fabric API for 1.21.4.
- Gradle + Fabric Loom, using Mojang's official mappings via `loom.officialMojangMappings()`.
- Mod id: `tabby`; package: `dev.tabby`; client environment.
- Entry point: `TabbyClient implements ClientModInitializer`.
- No required third-party mods; Fabric API is the only mod dependency.
- Source code and code comments in English; user-facing reports in Russian.
- Before using Minecraft fields or method signatures, verify them against generated sources and Fabric API 1.21.4 documentation. Do not invent names or signatures.
- Work only in `MAKAKAIglitch/tabby`; make every implementation and maintenance commit directly on `main`. Do not create stage branches or modify other repositories.
- Keep meaningful English commits on `main`, push the changes, and report the build/test result after each milestone.
- Do not commit generated `run/` data, credentials, account data, or local configuration containing logins. The standard Gradle Wrapper JAR is an intentional source-build tool.
- MIT license.

## Progress

- [x] Stage 0 — scaffold merged into `main`; build and client startup smoke test passed in GitHub Actions.
- [x] Stage 1 — networking prototype validated on `main`: CI connected one client to two local offline-mode 1.21.4 servers, verified distinct world/player/connection instances, and switched contexts repeatedly while both connections stayed alive for at least 2400 ticks (the successful run reached 5200 ticks). Background-screen isolation also passes. Full content-level chat/inventory/entity isolation and tab lifecycle cleanup remain follow-up validation; no UI existed during this stage.
- [x] Stage 2 — tab strip, active-tab highlight, titles, close/plus controls, and Ctrl+T/Ctrl+W/Ctrl+Tab/Ctrl+1..9 verified by CI. The strip is visible only on `TitleScreen` and `PauseScreen`, not over the gameplay HUD; CI checks both allowed screens and a hidden Options screen.
- [x] Stage 3 — duplicate attempts are intercepted before vanilla disconnects the active tab; disconnect callbacks release tab-owned references; CI covers duplicate blocking, closing a live tab, and closing the final tab.
- [x] Stage 4 — CI verifies key release on switch, the JSON-configured background-tab limit, 2400-tick dual-connection stability, real integrated single-player retention while connecting to a network tab, blocking a second single-player world, connection cleanup, and clean client shutdown. Sound stop, cursor reset, renderer refresh, and integrated-server preservation hooks are implemented.
- [x] Stage 5 — JSON settings, configurable Ctrl shortcuts, native Mod Menu settings screen, ping labels, drag reordering, pin/unpin, optional deduplicated address-only restoration, adjustable tab-bar size/color/opacity, and a hidden-by-default RAM/chunks/entities HUD plaque are implemented. CI now asserts that the appearance controls are present in the Mod Menu screen; a manual visual check of each preset remains open.
- [x] Drag interaction regression — automated CI drives a real tab press, pointer move, and release; verifies that a drag reorders the tab without changing the active connection, while a click selects only on release.
- [x] Stage 6 — CI passed on 2026-10-29; the baseline Fabric API networking/single-player test plus separate Sodium, Iris + Sodium, Lithium, and Mod Menu smoke tests passed in [run 40838779781](https://github.com/MAKAKAIglitch/tabby/actions/runs/40838779781). CI uses software-rendered llvmpipe; hardware-GPU and enabled-shaderpack testing remains open. Full content-level chat/inventory/entity isolation also remains a follow-up. Crash hardening restores an unexpectedly missing active level while its connection is live and skips Tabby background-maintenance during nested client ticks triggered by packet handlers. The optional no-dependency Meteor EventBus guard suppresses `TickEvent.Pre/Post` while no world is loaded to prevent world-dependent modules such as AutoBreed and Nametags from dereferencing a null level; in-modpack validation with Meteor remains open.
- [x] Performance update — CI run [40838779781](https://github.com/MAKAKAIglitch/tabby/actions/runs/40838779781) verified that only the active tab renderer is allowed to render, both worlds retain distinct renderers, the switch-back path preserves an already-bound renderer instead of rebuilding its chunk state, and a cached per-tab RAM estimate/HUD label is available for both live network tabs. The displayed RAM is an estimate based on loaded chunks/entities, not exact retained heap.

## Stage 0 — scaffold

- Add a minimal Fabric client mod and reproducible build configuration.
- Verify `./gradlew build` and that `./gradlew runClient` launches the client and loads the Tabby entry point.
- Do not start implementing tab logic until the scaffold is known to build.

## Stage 1 — prototype without UI (highest risk; do this first)

Implement `TabContext.capture()/apply()`, `TabManager.switchTo()`, `runIn()`, and test hotkeys for creating a tab and switching to the next tab.

The initial context snapshot should account for client state such as `level`, `player`, `gameMode`, connection / `ClientPacketListener`, `ServerData`, level renderer, particle engine, GUI state, hit result, crosshair entity, and screen. Use an access widener for fields that genuinely need it; use the `mutable` modifier only for final fields that require mutation.

Intercept `PacketUtils.ensureRunningOnSameThread` so packet tasks execute under the owning tab's context, including play and configuration packets. Tick background `ClientPacketListener` instances from `ClientTickEvents.END_CLIENT_TICK` inside the matching `runIn` context to preserve keep-alives. Background tabs must not render.

Acceptance test: connect to server A, create a second tab, connect to server B (two local offline-mode dedicated servers on different ports are acceptable), and switch between the tabs repeatedly for several minutes. Neither connection may time out; world state, chat, inventory, and entities must not leak between tabs.

**Stop condition:** if this test fails, do not begin the UI stage. Report the exact failure and propose a simpler reconnect-on-switch design.

## Stage 2 — tab bar

Render tabs through screen events only on `TitleScreen` and `PauseScreen`; do not render a tab bar in the in-game HUD or other screens. Show the tab title (server/world name or “Main Menu”), close control, plus control, active-tab highlight, and truncated long names. Intercept clicks only when they land in the tab bar.

Support Ctrl+T, Ctrl+W, Ctrl+Tab, and Ctrl+1 through Ctrl+9. Confirm Fabric API 1.21.4 event names and signatures before implementing them.

## Stage 3 — lifecycle

A new tab starts with a clean context and a `TitleScreen`. Update titles on successful connection. Closing a tab must disconnect and release its world/render resources; closing the last tab returns to the title screen. Detect an existing tab with the same server address and player name and show a clear message instead of starting a duplicate connection.

## Stage 4 — stability

Reset pressed inputs and mouse-grab state during a switch; stop audio from the tab being left; rebuild chunks when returning to a tab; limit background tabs (default 4, configurable); cleanly close all connections on client exit. Support at most one single-player world in the MVP; other tabs are networked.

## Stage 5 — configuration and polish

Provide a JSON config and optional Mod Menu settings page for the tab limit, key bindings, and address restoration. Tab-bar visibility is fixed to the title screen and pause menu. Add a ping indicator, tab dragging/pinning, and optional restoration of tab server addresses after restart. Never store account credentials.

## Stage 6 — compatibility

Test on Fabric API alone, then separately with Sodium, Iris, Lithium, and Mod Menu. Record actual results and any conflicts in the README.

## Known risks

1. Background packet tasks may mutate the active tab unless they run in the owning context.
2. Some renderer and third-party mod state is global and may not be context-switchable.
3. Each live world retains chunks and consumes memory.
4. The same server may reject a duplicate connection using the same player name.


## Build performance and Java selection

- [x] Enable the Gradle daemon, parallel task execution, parallel configuration-cache storage, VFS watching, local task-output caching, and multiple cached task graphs (configuration-cache problems warn rather than fail the build).
- [x] Make `gradlew` and `gradlew.bat` select an installed Java 21 automatically and, if needed, bootstrap an Eclipse Temurin 21 JDK into Gradle's user cache with SHA-256 verification.
- [x] Keep the same Gradle daemon available across CI build/test invocations by removing `--no-daemon`.


## Local Windows build launcher

- [x] Add a double-clickable `build-tabby.bat` launcher that remains open after success/failure.
- [x] Use the existing Java 21 selector/bootstrap, export the final remapped JAR to `dist/Tabby-1.21.4.jar`, verify the file is non-empty, and write the full console output to `build-tabby.log`.
- [ ] Verify the Windows build launcher and exported JAR in GitHub Actions.
