# Tabby

**Loader: Fabric (Minecraft Java Edition 1.21.11).** This branch targets Minecraft 1.21.11 only. It is a client-side mod and is not a Forge or NeoForge build.

## Build configuration

- Minecraft: `1.21.11`
- Java: `21`
- Fabric Loader: `0.19.3`
- Fabric API: `0.141.4+1.21.11`
- Fabric Loom: `1.14.10`

The Windows launcher reads the Minecraft version from `gradle.properties` and exports `dist/Tabby-1.21.11.jar`. The Gradle wrapper selects a matching Java runtime and verifies downloaded Temurin archives using SHA-256.

## Porting status

The branch-specific build configuration and metadata have been updated. **The Minecraft source/API port and a successful CI build are not yet confirmed.** Treat this branch as work in progress until the GitHub Actions build and client startup test pass.

The 1.21.4 implementation is the source baseline. Version-specific changes to Minecraft rendering, networking, mixins, Fabric API, and optional-mod compatibility must be checked on this target before release.
