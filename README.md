# Tabby

Tabby is a client-side Fabric mod project for Minecraft Java Edition 1.21.4. Its goal is to provide browser-style tabs inside one Minecraft client.

## Current status

**Stage 0 accepted.** The minimal client builds and loads in a Minecraft 1.21.4 startup smoke test.

**Stages 1–3 verified by CI.** One client connected to two local Minecraft 1.21.4 servers, verified separate world/player/connection objects, and kept both connections alive for at least 2400 ticks while switching tabs. CI exercises tab clicks and shortcuts, duplicate-connection protection, closing a live tab, and returning to a title screen after the final tab closes. Fabric API 0.119.4 does not support initializing a second particle factory registry, so the prototype shares the particle engine and suppresses particle mutations from inactive tabs. Full chat/inventory/entity isolation still needs deeper tests.

**Stage 4 stability checks partly verified.** CI now checks key release after a switch, the JSON-configured background-tab limit (default 4), the single-player guard predicate, clean connection cleanup, and client shutdown. Input/audio/render cleanup and the integrated-server preservation hooks are implemented. A live single-player world has not yet been exercised end-to-end while switching to and connecting from a network tab, so Stage 4 remains open.

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
