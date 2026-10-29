# Tabby

Tabby is a client-side Fabric mod project for Minecraft Java Edition 1.21.4. Its goal is to provide browser-style tabs inside one Minecraft client.

## Current status

**Stage 0 accepted.** The minimal client builds and loads in a Minecraft 1.21.4 startup smoke test.

**Stages 1–3 verified by CI.** One client connected to two local Minecraft 1.21.4 servers, verified separate world/player/connection objects, and kept both connections alive for at least 2400 ticks while switching tabs. CI exercises tab clicks and shortcuts, duplicate-connection protection, closing a live tab, and returning to a title screen after the final tab closes. Fabric API 0.119.4 does not support initializing a second particle factory registry, so the prototype shares the particle engine and suppresses particle mutations from inactive tabs. Full chat/inventory/entity isolation still needs deeper tests.

**Stage 4 verified by CI.** The test connected one client to two offline-mode servers, switched between them for 2400 ticks, then opened an actual integrated single-player world, connected a different tab to a network server, switched back to verify the same world/player/memory connection/IntegratedServer remained live, and confirmed that opening a second single-player save was blocked. It then closed the single-player and network tabs and returned to the title screen. CI also verifies key release, the configured background-tab limit (default 4), duplicate-connection protection, and clean shutdown.

**Stage 5 verified by CI.** `config/tabby.json` exposes the background-tab limit, Ctrl shortcut key codes, menu visibility, in-game bar mode (`always`, `hover_or_alt`, `hidden`), and optional address-only server restoration (`restoreServerAddresses`). The tab strip shows pin state and cached ping when available, supports right-click pin/unpin and drag reordering, and reconnects saved server addresses when restoration is enabled. CI checks default config values, tab order/pinning, and deduplicated address persistence.

**Stage 6 verified by CI (2026-10-29).** [Workflow run 40816763509 passed](https://github.com/MAKAKAIglitch/tabby/actions/runs/40816763509): the baseline Fabric API network/single-player regression suite and four isolated compatibility runs all passed. The test downloaded SHA-512-pinned Fabric builds for Minecraft 1.21.4 and verified that each optional mod could load a real local server world, keep separate tab renderers, and survive six context switches. Results:

| Configuration | Result |
|---|---|
| Fabric API baseline | Passed — dual-server and single-player lifecycle suite |
| Sodium 0.6.13 | Passed — client launch, world load, six context switches |
| Iris 1.8.8 + Sodium 0.6.13 | Passed — client launch, world load, six context switches |
| Lithium 0.15.3 | Passed — client launch, world load, six context switches |
| Mod Menu 13.0.2 | Passed — client launch, world load, six context switches |

These are **headless smoke tests**, not a claim of full gameplay compatibility. CI uses software OpenGL (llvmpipe); hardware-GPU rendering, enabled shaderpacks, longer sessions, and the four mods loaded together were not tested. Full content-level isolation of chat, inventory, and entities also remains a follow-up.

## Requirements

- Minecraft Java Edition 1.21.4
- Java 21
- Fabric Loader 0.16.x
- Fabric API for Minecraft 1.21.4

The project uses Fabric Loom and Mojang's official mappings. No third-party mod is required to build it.

## Build and run

### Automatic Java selection

The wrapper selects Java 21 automatically before starting Gradle. It checks `TABBY_JAVA_HOME`, `JAVA_HOME`, `JDK_HOME`, the active `PATH` Java, and common JDK installation locations. If no Java 21 installation is found, it downloads the latest Eclipse Temurin JDK 21 from Adoptium into the Gradle user cache and verifies the archive's SHA-256 checksum before using it. This also avoids accidentally launching this Gradle 8.12.1 build on an unsupported newer JVM.

Set `TABBY_AUTO_JAVA=0` to disable Tabby's selection/bootstrap and use your normal Java environment. The build targets Java 21 in all cases.

### Build speed

Gradle reuses its daemon, configuration cache, and local build cache for repeat builds. Independent tasks are scheduled in parallel, and up to four configuration-cache entries are retained so source generation, build, and client launch can reuse configuration. The Gradle daemon is capped at 2 GB with a 768 MB metaspace limit because `runClient` starts a separate Minecraft JVM; this lowers the risk of the operating system killing Gradle on memory-constrained PCs. Parallel configuration-cache serialization is left disabled to avoid a needless memory spike. CI runs source generation and the build in one Gradle invocation. Keep using the wrapper commands below so automatic Java selection and the project-specific Gradle settings are applied.

On Linux/macOS:

```sh
./gradlew build
./gradlew runClient
```

On Windows:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

The first build downloads the Gradle distribution, required dependencies, and (only if needed) Java 21. Later builds reuse the local caches. Build output is written to `build/libs/`; generated game files are kept under the ignored `run/` directory.

On Windows, a successful `build` automatically opens File Explorer to `build\\libs`, where the compiled mod JAR is stored. Set `TABBY_OPEN_MOD_FOLDER=0` to turn this off. This is disabled automatically in CI and on non-Windows systems.

Use **Ctrl+T** to create a tab, **Ctrl+W** to close the active tab, **Ctrl+Tab** to cycle tabs, and **Ctrl+1** through **Ctrl+9** to select a tab by position. The tab strip includes a title, close control, and plus control.

## Development workflow

All project changes are committed directly to `main`. See [TASK.md](TASK.md) for the implementation plan and progress.

A GitHub Actions workflow generates Minecraft sources, builds the mod, starts two verified Minecraft 1.21.4 offline-mode servers, and runs an automated client test that switches between both live connections.
