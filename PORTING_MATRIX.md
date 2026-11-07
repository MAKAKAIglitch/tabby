# Tabby platform and Minecraft version matrix

A branch existing does **not** mean that its port is complete or tested. This file tracks only branches currently present in the repository.

## Current branches

| Branch | Target | Java | Current state |
|---|---|---:|---|
| `main` | Fabric 1.21.4 | 21 | Existing baseline; keep as the stable reference |
| `fabric/1.21.4` | Fabric 1.21.4 | 21 | Existing 1.21.4 line |
| `fabric/1.21.1` | Fabric 1.21.1 | 21 | Build/API port has begun; final CI result not confirmed |
| `fabric/1.21.11` | Fabric 1.21.11 | 21 | Loom, loader, metadata and version-aware Windows build updated; source build not confirmed |
| `fabric/26.2` | Fabric 26.2 | 25 | Loom/Gradle/loader/API/Mod Menu metadata and Java 25 selector updated; source/API port not confirmed |
| `fabric/26.3` | Fabric 26.3 | 25 | Loom/Gradle/loader/API/Mod Menu metadata and Java 25 selector updated; source/API port not confirmed |
| `neoforge/1.21.1` | NeoForge 1.21.1 | 21 | Scaffold only; native NeoForge port not yet implemented |
| `neoforge/1.21.11` | NeoForge 1.21.11 | 21 | Scaffold only; native NeoForge port not yet implemented |

NeoForge 26.2 and 26.3 branches are not present and have not been recreated.

## Fabric toolchains

| Minecraft | Fabric Loader | Fabric API | Loom | Gradle wrapper | Java |
|---|---:|---:|---:|---:|---:|
| 1.21.1 | 0.16.14 | 0.116.17+1.21.1 | 1.9.2 | 8.12.1 | 21 |
| 1.21.11 | 0.20.1 | 0.141.4+1.21.11 | 1.14.10 | 8.12.1 | 21 |
| 26.2 | 0.20.1 | 0.161.0+26.2 | 1.17.21 | 9.5.1 | 25 |
| 26.3 | 0.20.1 | 0.165.2+26.3 | 1.17.21 | 9.6.1 | 25 |

The 26.x branches use the non-remapping Loom plugin and official namespace access widener. Their build wrappers select Java 25 and use SHA-256-pinned Gradle distributions.

## Port acceptance checks

A branch is not release-ready until all applicable checks pass on that exact branch:

1. Clean Gradle build using the target Java and loader toolchain.
2. Correct loader metadata and exact Minecraft version constraint.
3. Production JAR exists, is non-empty, and contains the expected classes/resources.
4. Client starts and initializes Tabby without a crash.
5. Tab creation, switching, closing and cleanup work.
6. Two remote connections remain isolated while switching tabs.
7. Integrated-world lifecycle and disconnect handling are verified.
8. Optional-mod integrations are tested only against builds available for that exact Minecraft version and loader.

## Current limitations

- The source/API migration for Fabric 26.2 and 26.3 is not yet verified. The 1.21.4 source baseline may contain incompatible Minecraft and Fabric API calls.
- Fabric 1.21.11 build configuration is updated, but its final clean build and client launch have not been confirmed.
- NeoForge branches still contain Fabric-specific code and metadata. A real NeoForge port needs ModDevGradle, a native `@Mod` entrypoint, NeoForge event/config integration, and access-transformer/mixin migration.
- The GitHub connector currently reports no CI status checks for the latest port commits. No successful build is being claimed for those commits.
- `main` remains the Fabric 1.21.4 reference branch and should not be replaced by an unverified port.
