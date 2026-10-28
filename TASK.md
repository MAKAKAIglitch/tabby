# Tabby — implementation task

## Goal

Create a client-side Fabric mod for Minecraft 1.21.4 that provides browser-style tabs in a single Minecraft client and account. Each network tab should retain its own world, player, GUI, and server connection while another tab is active.

## Fixed constraints

- Minecraft 1.21.4; Java 21; Fabric Loader 0.16.x; Fabric API for 1.21.4.
- Gradle + Fabric Loom, using Mojang's official mappings via `loom.officialMojangMappings()`.
- Mod id: `tabby`; package: `dev.matvey.tabby`; client environment.
- Entry point: `TabbyClient implements ClientModInitializer`.
- No required third-party mods; Fabric API is the only mod dependency.
- Source code and code comments in English; user-facing reports in Russian.
- Before using Minecraft fields or method signatures, verify them against generated sources and Fabric API 1.21.4 documentation. Do not invent names or signatures.
- Work only in `MAKAKAIglitch/tabby`. Use one `stage-N-short-description` branch per stage, and merge only accepted stages into `main`.
- Each stage should have a build check, meaningful commit(s), a push, and a short report.
- Do not commit generated `run/` data, credentials, account data, or local configuration containing logins. The standard Gradle Wrapper JAR is an intentional source-build tool.
- MIT license.

## Progress

- [ ] Stage 0 — scaffold committed to `stage-0-scaffold`; build and client startup acceptance checks pending.
- [ ] Stage 1 — connection/context prototype without UI; must pass simultaneous-connection acceptance test before continuing.
- [ ] Stage 2 — tab bar and mouse/keyboard interaction.
- [ ] Stage 3 — tab lifecycle, labels, disconnect/cleanup, duplicate-connection warning.
- [ ] Stage 4 — input/audio/render cleanup, background-tab limit, exit handling, one single-player world maximum.
- [ ] Stage 5 — JSON config and tab polish.
- [ ] Stage 6 — compatibility checks and README report.

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

Render tabs using appropriate Fabric events for screens and in-game HUD. Show the tab title (server/world name or “Main Menu”), close control, plus control, active-tab highlight, and truncated long names. Intercept clicks only when they land in the tab bar.

Support Ctrl+T, Ctrl+W, Ctrl+Tab, and Ctrl+1 through Ctrl+9. Confirm Fabric API 1.21.4 event names and signatures before implementing them.

## Stage 3 — lifecycle

A new tab starts with a clean context and a `TitleScreen`. Update titles on successful connection. Closing a tab must disconnect and release its world/render resources; closing the last tab returns to the title screen. Detect an existing tab with the same server address and player name and show a clear message instead of starting a duplicate connection.

## Stage 4 — stability

Reset pressed inputs and mouse-grab state during a switch; stop audio from the tab being left; rebuild chunks when returning to a tab; limit background tabs (default 4, configurable); cleanly close all connections on client exit. Support at most one single-player world in the MVP; other tabs are networked.

## Stage 5 — configuration and polish

Provide a JSON config for tab limit, key bindings, and bar visibility. Add a ping indicator, tab dragging/pinning, and optional restoration of tab server addresses after restart. Never store account credentials.

## Stage 6 — compatibility

Test on Fabric API alone, then separately with Sodium, Iris, Lithium, and Mod Menu. Record actual results and any conflicts in the README.

## Known risks

1. Background packet tasks may mutate the active tab unless they run in the owning context.
2. Some renderer and third-party mod state is global and may not be context-switchable.
3. Each live world retains chunks and consumes memory.
4. The same server may reject a duplicate connection using the same player name.
