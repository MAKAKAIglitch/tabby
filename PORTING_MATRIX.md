# Tabby platform and Minecraft version matrix

This file tracks the per-loader/per-version ports. A branch existing does **not** mean that its port is complete or tested.

## Branch naming

- `fabric/<minecraft-version>`
- `neoforge/<minecraft-version>`

Each branch is independent and must build against the exact Minecraft and loader API for its target.

## Targets

| Minecraft | Fabric branch | NeoForge branch | Java | Notes |
|---|---|---|---:|---|
| 1.21.1 | `fabric/1.21.1` | `neoforge/1.21.1` | 21 | |
| 1.21.2 | `fabric/1.21.2` | `neoforge/1.21.2` | 21 | NeoForge artifacts are beta-only |
| 1.21.3 | `fabric/1.21.3` | `neoforge/1.21.3` | 21 | NeoForge artifacts are beta-only |
| 1.21.4 | `fabric/1.21.4` | `neoforge/1.21.4` | 21 | Existing main baseline is Fabric 1.21.4 |
| 1.21.5 | `fabric/1.21.5` | `neoforge/1.21.5` | 21 | |
| 1.21.6 | `fabric/1.21.6` | `neoforge/1.21.6` | 21 | |
| 1.21.7 | `fabric/1.21.7` | `neoforge/1.21.7` | 21 | |
| 1.21.8 | `fabric/1.21.8` | `neoforge/1.21.8` | 21 | |
| 1.21.9 | `fabric/1.21.9` | `neoforge/1.21.9` | 21 | |
| 1.21.10 | `fabric/1.21.10` | `neoforge/1.21.10` | 21 | |
| 1.21.11 | `fabric/1.21.11` | `neoforge/1.21.11` | 21 | |
| 26.1 | `fabric/26.1` | `neoforge/26.1` | 25 | NeoForge line is beta-only |
| 26.1.1 | `fabric/26.1.1` | `neoforge/26.1.1` | 25 | NeoForge line is beta-only |
| 26.1.2 | `fabric/26.1.2` | `neoforge/26.1.2` | 25 | |
| 26.2 | `fabric/26.2` | `neoforge/26.2` | 25 | |
| 26.3 | `fabric/26.3` | `neoforge/26.3` | 25 | |

## Port status

- Branches were created from the current `main` baseline.
- `fabric/1.21.1`: build metadata and compile CI are being adapted first. The result is pending; this is not yet a verified port.
- All other `fabric/*` branches: branch scaffolds only; source/API compatibility has not yet been verified.
- All `neoforge/*` branches: branch scaffolds only; the Fabric entrypoint, Fabric API integrations, access widener and build setup still need a NeoForge platform implementation.
- `main` remains the existing Fabric 1.21.4 line until the other ports are independently built and tested.

## Required acceptance checks per branch

1. Exact Java toolchain and Gradle/loader plugin for that target.
2. Successful clean Gradle build and remapped/production JAR.
3. Correct loader metadata and Minecraft version constraints.
4. Client launch smoke test.
5. Tab creation/switch/close, two remote connections, integrated-world lifecycle and reconnect tests.
6. Optional integration tests only for mods available on that loader/version.
7. No claims of support until CI passes for that exact branch.

## Important toolchain split

- Minecraft 1.21.x targets use Java 21.
- Minecraft 26.x targets use Java 25. Fabric's 26.1+ Loom plugin ID and NeoForge ModDevGradle setup differ from the 1.21.x build.
