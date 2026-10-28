# Tabby

Tabby is a client-side Fabric mod project for Minecraft Java Edition 1.21.4. Its goal is to provide browser-style tabs inside one Minecraft client.

## Current status

**Stage 0 accepted.** The minimal client builds and loads in a Minecraft 1.21.4 startup smoke test.

**Stage 1 prototype (unverified).** The `stage-1-prototype` branch adds temporary F6/F7 test controls, per-tab world/player/GUI state, separate world renderers and particle engines, listener ownership, packet-task context switching, and background listener/connection ticks. This is experimental networking code; two-server isolation has not passed acceptance, and no tab bar/UI has been implemented.

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

On the Stage 1 prototype branch, press **F6** to create a temporary test tab and **F7** to switch to the next test tab. These are development-only controls, not the final browser-style interface.

## Development workflow

Work is staged on branches named `stage-N-short-description`. Only a stage that has passed its acceptance criteria should be merged into `main`. See [TASK.md](TASK.md) for the full plan and progress.

A GitHub Actions workflow builds the project and performs a brief client startup smoke test on a virtual display.
