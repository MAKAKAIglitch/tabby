# Tabby

**Loader: Fabric (Minecraft Java Edition 1.21.4).** Tabby is a client-side Fabric mod, not a Forge or NeoForge mod. Install the exported `Tabby-1.21.4.jar` into a Minecraft profile that uses Fabric Loader 0.16.14+ and Fabric API for 1.21.4.

## Current status

**Stage 0 accepted.** The minimal client builds and loads in a Minecraft 1.21.4 startup smoke test.

**Stages 1–3 verified by CI.** One client connected to two local Minecraft 1.21.4 servers, verified separate world/player/connection objects, and kept both connections alive for at least 2400 ticks while switching tabs. CI exercises tab clicks and shortcuts, duplicate-connection protection, closing a live tab, and returning to a title screen after the final tab closes. Fabric API 0.119.4 does not support initializing a second particle factory registry, so the prototype shares the particle engine and suppresses particle mutations from inactive tabs. A pre-tick guard restores a missing active world while its network connection is live. An optional mixin blocks Meteor's `TickEvent.Pre`/`TickEvent.Post` dispatch while no active world is loaded. This protects multiple world-dependent modules (including the reported AutoBreed and Nametags handlers) without a compile-time Meteor dependency; validation inside the user's Meteor modpack is still required. Full chat/inventory/entity isolation still needs deeper tests.

**Stage 4 verified by CI (2026-10-30).** [Workflow run 41019826362 passed](https://github.com/MAKAKAIglitch/tabby/actions/runs/41019826362). The test pressed the real Disconnect button in `PauseScreen` on the active network tab while an integrated single-player world remained open in a background tab. The disconnect call returned, the selected network connection closed, and the hidden IntegratedServer stayed alive. Closing the last single-player tab now joins the IntegratedServer thread before Minecraft shuts down shared executors; CI asserts `isShutdown()` before exit. The full run had no `RejectedExecutionException`, rejected chunk scheduling, or failed chunk-save errors. The same run also passed Java 21 selection checks, Linux and Windows builds, the dual-server isolation test, and separate Sodium, Iris, Lithium, and Mod Menu compatibility smoke tests. CI also verifies key release, the configured background-tab limit (default 4), duplicate-connection protection, and clean tab cleanup.

**Stage 5 verified by CI.** `config/tabby.json` stores the background-tab limit, Ctrl shortcut key codes, menu visibility, bar display mode, optional address-only server restoration, bar size (75–125%), five color presets, opacity (20–100%), and a toggle for the RAM/chunks/entities HUD plaque. With Mod Menu installed, **Mods → Tabby → Settings** opens the native Minecraft settings screen for these options. The diagnostic HUD plaque is hidden by default; enable it to show the estimated RAM, loaded chunks, and tracked entities. The strip supports right-click pin/unpin and drag reordering, and labels show cached ping when available. Saved server addresses reconnect when restoration is enabled. CI checks config defaults, tab order/pinning, and deduplicated address persistence.

**Stage 6 and the performance update verified by CI (2026-10-29).** [Workflow run 40838779781](https://github.com/MAKAKAIglitch/tabby/actions/runs/40838779781) passed the source generation/build, dual-server and single-player integration suite, and four isolated compatibility tests after the render guard and RAM estimate were added. The earlier [run 40836537553](https://github.com/MAKAKAIglitch/tabby/actions/runs/40836537553) verified that the tab strip is visible on `TitleScreen` and `PauseScreen`, hidden on other screens, and that the actual Mod Menu entrypoint initializes all settings controls. The test downloaded SHA-512-pinned Fabric builds for Minecraft 1.21.4 and verified that each optional mod could load a real local server world, keep separate tab renderers, and survive six context switches. Results:

| Configuration | Result |
|---|---|
| Fabric API baseline | Passed — dual-server and single-player lifecycle suite |
| Sodium 0.6.13 | Passed — client launch, world load, six context switches |
| Iris 1.8.8 + Sodium 0.6.13 | Passed — client launch, world load, six context switches |
| Lithium 0.15.3 | Passed — client launch, world load, six context switches |
| Mod Menu 13.0.2 | Passed — settings screen and controls initialized, client launch, world load, six context switches |

Inactive tab renderers are explicitly blocked at `LevelRenderer.renderLevel`; the two-server CI test verifies the active renderer is allowed and the inactive renderer is rejected. Switching back to a world no longer calls `LevelRenderer.setLevel()` when that renderer already owns the same world, avoiding a full chunk-render-state rebuild on every switch. The active tab shows `~NNMB` in the strip and `RAM ~NN MB` plus loaded chunks/tracked entities in the in-game HUD. This RAM figure is a cached **estimate** based on loaded chunks and tracked entities, not a precise per-object JVM heap measurement. These are **headless smoke tests**, not a claim of full gameplay compatibility. CI uses software OpenGL (llvmpipe); hardware-GPU rendering, enabled shaderpacks, longer sessions, and the four mods loaded together were not tested. Full content-level isolation of chat, inventory, and entities also remains a follow-up.

## Requirements

- Minecraft Java Edition 1.21.4
- Java 21
- Fabric Loader 0.16.x
- Fabric API for Minecraft 1.21.4

The project uses Fabric Loom and Mojang's official mappings. The Mod Menu API is compile-only: Mod Menu is optional at runtime.

## Build and run

### Automatic Java selection

The wrapper selects Java 21 automatically before starting Gradle, and the Gradle Java toolchain pins compilation to Java 21 as a second guard. It checks `TABBY_JAVA_HOME`, `JAVA_HOME`, `JDK_HOME`, the active `PATH` Java, and common JDK installation locations. If no Java 21 installation is found, it downloads the latest Eclipse Temurin JDK 21 from Adoptium into the Gradle user cache and verifies the archive's SHA-256 checksum before using it. This also avoids accidentally launching this Gradle 8.12.1 build on an unsupported newer JVM.

Set `TABBY_AUTO_JAVA=0` to disable Tabby's selection/bootstrap and use your normal Java environment. The build targets Java 21 in all cases.

### Build speed

Gradle reuses its daemon, configuration cache, and local build cache for repeat builds. Independent tasks are scheduled in parallel, and up to four configuration-cache entries are retained so source generation, build, and client launch can reuse configuration. The Gradle daemon is capped at 2 GB with a 768 MB metaspace limit because `runClient` starts a separate Minecraft JVM; this lowers the risk of the operating system killing Gradle on memory-constrained PCs. Parallel configuration-cache serialization is left disabled to avoid a needless memory spike. Normal CI builds skip source generation because it is not required to compile the mod. Run `./gradlew genSources` manually only when inspecting Minecraft internals. Keep using the wrapper commands below so automatic Java selection and the project-specific Gradle settings are applied.

On Linux/macOS:

```sh
./gradlew build
./gradlew runClient
```

### Build locally on Windows

**Recommended: double-click `build-tabby.bat` in the project folder.** This builds the Fabric version for Minecraft 1.21.4 (not Forge/NeoForge). It automatically selects an installed Java 21 JDK (or downloads Eclipse Temurin 21 after verifying its SHA-256 checksum), runs Gradle, and checks that a non-empty remapped mod JAR really exists. The final file is copied to:

```text
dist\\Tabby-1.21.4.jar
```

The launcher opens the `dist` folder only after verifying the JAR. The console stays open after both success and failure, and the full build output is saved to `build-tabby.log` for troubleshooting. CI skips the interactive pause. To suppress the pause when launching from your own terminal, set `TABBY_NO_PAUSE=1`.

To build from a terminal instead, use `./gradlew build exportModJar` on Linux/macOS or ` .\\gradlew.bat build exportModJar` on Windows. These commands also leave the installable JAR in `dist/`. Use `gradlew.bat runClient` when you want to launch a development client rather than just create the mod.

The first build downloads the Gradle distribution, required dependencies, and (only if needed) Java 21. Later builds reuse the Gradle daemon, configuration cache and task-output cache. The normal Gradle outputs remain under `build/libs/`; the stable installable copy is `dist/Tabby-1.21.4.jar`. Generated game files are kept under the ignored `run/` directory.

Use **Ctrl+T** to create a tab, **Ctrl+W** to close the active tab, **Ctrl+Tab** to cycle tabs, and **Ctrl+1** through **Ctrl+9** to select a tab by position. The strip is visible only on the **main title screen** and the **pause menu**; it never renders over the in-game HUD or other screens. With Mod Menu installed, open **Mods → Tabby → Settings** to change the background-tab limit, server-address restoration, Ctrl shortcut keys, tab-bar size/color/opacity, and the diagnostic HUD plaque.

## Development workflow

All project changes are committed directly to `main`. See [TASK.md](TASK.md) for the implementation plan and progress.

A GitHub Actions workflow generates Minecraft sources, builds the mod, starts two verified Minecraft 1.21.4 offline-mode servers, and runs an automated client test that switches between both live connections.
