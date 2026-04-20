# Dungeon Train (Minecraft Mod)

A Minecraft port of [Dungeon Train](https://brennanhatton.itch.io/dungeontrain) — a moving train that hosts procedurally generated dungeons. Powered by [Valkyrien Skies 2](https://valkyrienskies.org/).

| | |
|---|---|
| **Loader** | Forge 1.20.1 |
| **Forge** | 47.4.2 |
| **Mappings** | Official (mojmap) |
| **Java** | 17 |
| **Planned dependency** | Valkyrien Skies 2 (`2.4.11`) — currently disabled, see Known Issues |
| **Status** | Pre-alpha |

## Known issues

- **Valkyrien Skies dev env**: VS 2.4.11 (and 2.4.10) mixin `@WrapOperation` targets on `net.minecraft.client.Minecraft.startUseItem` fail to apply under ForgeGradle userdev — `m_91277_` SRG doesn't get translated back to mojmap at mixin validation time. VS is temporarily commented out in `build.gradle` and `mods.toml`. To be resolved when the moving-train feature is implemented; possible paths include adding the SpongePowered Mixin gradle plugin, or testing via a real Minecraft launcher install instead of `./gradlew runClient`.

## Build

```bash
./gradlew build
```

Output: `build/libs/dungeontrain-<version>.jar`

## Run dev client

```bash
./gradlew runClient
```

## Run dev dedicated server

```bash
./gradlew runServer
```

## Project conventions

This repo uses the `bh679/claude-templates` Engineering Product workflow with three approval gates (plan → test → merge). See [`CLAUDE.md`](CLAUDE.md) for the full workflow.

## Original game

The browser version of Dungeon Train is at https://brennanhatton.itch.io/dungeontrain.
