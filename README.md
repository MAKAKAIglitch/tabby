# Tabby

**Loader: Fabric (Minecraft Java Edition 26.2).** This branch targets Minecraft 26.2 only. It is a client-side mod and is not a Forge or NeoForge build.

## Build configuration

- Minecraft: `26.2`
- Java: `25`
- Fabric Loader: `0.20.1`
- Fabric API: `0.161.0+26.2`
- Fabric Loom: `1.17.21`

The Windows launcher reads the Minecraft version from `gradle.properties` and exports `dist/Tabby-26.2.jar`. The Gradle wrapper selects a matching Java runtime and verifies downloaded Temurin archives using SHA-256.

## Porting status

The branch-specific build configuration and metadata have been updated. **The Minecraft source/API port and a successful CI build are not yet confirmed.** Treat this branch as work in progress until the GitHub Actions build and client startup test pass.

The 1.21.4 implementation is the source baseline. Version-specific changes to Minecraft rendering, networking, mixins, Fabric API, and optional-mod compatibility must be checked on this target before release.
