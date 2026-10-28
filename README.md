# Tabby

Tabby is a client-side Fabric mod project for Minecraft Java Edition 1.21.4. Its goal is to provide browser-style tabs inside one Minecraft client.

## Current status

**Stage 0 accepted.** The minimal client builds and loads in a Minecraft 1.21.4 startup smoke test. Stage 1 prototype work is isolated on `stage-1-prototype`; simultaneous connections have not passed acceptance yet, and the UI is intentionally not implemented.

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

## Development workflow

Work is staged on branches named `stage-N-short-description`. Only a stage that has passed its acceptance criteria should be merged into `main`. See [TASK.md](TASK.md) for the full plan and progress.

A GitHub Actions workflow builds the project and performs a brief client startup smoke test on a virtual display.
