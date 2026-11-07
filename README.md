# Tabby

**Platform:** Fabric, Minecraft Java Edition **1.21.1**.

This branch is the dedicated Minecraft 1.21.1 Fabric port. It is kept separate from the 1.21.4 baseline and is built against the exact Minecraft/Fabric API versions listed below.

## Requirements

- Minecraft Java Edition 1.21.1
- Java 21
- Fabric Loader 0.16.14 or newer
- Fabric API 0.116.17+1.21.1

## Build

Linux/macOS:

```sh
./gradlew build
./gradlew runClient
```

Windows:

Double-click `build-tabby.bat`, or run:

```powershell
.\gradlew.bat build exportModJar
```

The exported mod is `dist/Tabby-1.21.1.jar`.

## Port status

This branch was created from the Tabby 1.21.4 Fabric implementation and is being adapted to 1.21.1 APIs. The first compile attempt identified version-specific changes in the player input type, particle setting enum package, and the client tick-end packet. Those compile errors have been addressed in this branch; the latest CI run is pending. Do not treat this branch as a verified release until its build and client smoke tests pass.

## Features

The port aims to retain the existing Tabby feature set: browser-style client tabs, isolated client contexts, tab switching and reordering, server reconnect, unread chat indicators, background damage alerts, memory diagnostics, and optional mod compatibility adapters.

Compatibility with third-party mods must be tested against versions built for Minecraft 1.21.1. Compatibility results from the 1.21.4 branch do not automatically apply to this branch.
