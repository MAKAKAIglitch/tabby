# Tabby

Tabby is a client-side Fabric mod project for Minecraft Java Edition 1.21.4. Its goal is to provide browser-style tabs inside one Minecraft client.

## Current status

**Stage 0 accepted.** The minimal client builds and loads in a Minecraft 1.21.4 startup smoke test.

**Stages 1–2 verified by CI.** One client connected to two local Minecraft 1.21.4 servers, verified separate world/player/connection objects, and kept both connections alive for 2400 ticks while switching tabs. CI also checks tab clicks, plus/close controls, and Ctrl+T, Ctrl+W, Ctrl+Tab, and Ctrl+1 through the real input path. Stage 3 now adds duplicate-connection interception before vanilla disconnects the active tab, plus disconnect cleanup and final-tab lifecycle tests. Fabric API 0.119.4 does not support initializing a second particle factory registry, so the prototype shares the particle engine and suppresses particle mutations from inactive tabs. Full chat/inventory/entity isolation still needs deeper tests.

## Requirements

- Minecraft Java Edition 1.21.4
- Java 21
- Fabric Loader 0.16.x
- Fabric API for Minecraft 1.21.4

The project uses Fabric Loom and Mojang's official mappings. No third-party mod is required to build it.

## Build and run

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

The first Gradle run downloads the Gradle distribution and required Minecraft/Fabric dependencies. Build output is written to `build/libs/`; generated game files are kept under the ignored `run/` directory.

Use **Ctrl+T** to create a tab, **Ctrl+W** to close the active tab, **Ctrl+Tab** to cycle tabs, and **Ctrl+1** through **Ctrl+9** to select a tab by position. The tab strip includes a title, close control, and plus control.

## Development workflow

All project changes are committed directly to `main`. See [TASK.md](TASK.md) for the implementation plan and progress.

A GitHub Actions workflow generates Minecraft sources, builds the mod, starts two verified Minecraft 1.21.4 offline-mode servers, and runs an automated client test that switches between both live connections.
