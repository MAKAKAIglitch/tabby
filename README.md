# Tabby

Tabby is a client-side Fabric mod project for Minecraft Java Edition 1.21.4. Its goal is to provide browser-style tabs inside one Minecraft client.

## Current status

**Stage 0 accepted.** The minimal client builds and loads in a Minecraft 1.21.4 startup smoke test.

**Stage 1 prototype (unverified).** Development continues directly on `main`. The prototype adds temporary F6/F7 test controls, per-tab world/player/GUI state, separate world renderers and particle engines, listener ownership, packet-task context switching, and background connection ticks. CI also runs a short automatic context-switch test. Two-server isolation has not passed acceptance, and no tab bar/UI has been implemented.

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

A GitHub Actions workflow builds the project and performs a brief client startup smoke test on a virtual display.
