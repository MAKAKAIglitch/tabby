# Tabby

Tabby is a client-side Fabric mod project for Minecraft Java Edition 1.21.4. Its goal is to provide browser-style tabs inside one Minecraft client.

## Current status

**Stage 0 accepted.** The minimal client builds and loads in a Minecraft 1.21.4 startup smoke test.

**Stage 1 networking prototype verified.** All changes are on `main`. The automated dual-server test connected to two local Minecraft 1.21.4 servers, verified separate world/player/connection objects, switched contexts repeatedly, and observed both connections remain live beyond the 2400-tick acceptance threshold (the successful CI run continued to 5200 ticks). The client still has temporary F6/F7 test controls; there is no tab bar/UI yet. Fabric API 0.119.4 does not support initializing a second particle factory registry, so this prototype shares the particle engine and suppresses particle mutations from inactive tabs. Chat/inventory/entity-content isolation and lifecycle cleanup still need deeper tests.

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

On the current prototype in `main`, press **F6** to create a temporary test tab and **F7** to switch to the next test tab. These are development-only controls, not the final browser-style interface.

## Development workflow

All project changes are committed directly to `main`. See [TASK.md](TASK.md) for the implementation plan and progress.

A GitHub Actions workflow generates Minecraft sources, builds the mod, starts two verified Minecraft 1.21.4 offline-mode servers, and runs an automated client test that switches between both live connections.
