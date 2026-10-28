# Tabby

Tabby is a client-side Fabric mod project for Minecraft Java Edition 1.21.4. Its goal is to provide browser-style tabs inside one Minecraft client.

## Current status

**Stage 0 accepted.** The minimal client builds and loads in a Minecraft 1.21.4 startup smoke test.

**Stage 1 prototype (unverified).** Development continues directly on `main`. The prototype has temporary F6/F7 controls, per-tab worlds/players/GUI and world renderers, listener ownership, packet-task context switching, and background connection ticks. Because Fabric API 0.119.4 cannot safely initialize a second particle factory registry, particle effects use the shared engine and are suppressed for inactive tabs. CI now starts two local offline-mode servers and tests connection isolation with repeated tab switching. The two-server test has not yet passed; no tab bar/UI has been implemented.

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
