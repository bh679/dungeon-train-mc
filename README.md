# Dungeon Train (Minecraft Mod)

[![Build](https://github.com/bh679/dungeon-train-mc/actions/workflows/build.yml/badge.svg)](https://github.com/bh679/dungeon-train-mc/actions/workflows/build.yml)
[![Modrinth](https://img.shields.io/modrinth/dt/dungeon-train?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/dungeon-train)
[![CurseForge](https://cf.way2muchnoise.eu/title/dungeon-train.svg)](https://curseforge.com/minecraft/mc-mods/dungeon-train)

A Minecraft port of [Dungeon Train](https://brennanhatton.itch.io/dungeontrain) — a moving train that hosts procedurally generated dungeons. Powered by [Valkyrien Skies 2](https://valkyrienskies.org/).

| | |
|---|---|
| **Loader** | Forge 1.20.1 |
| **Forge** | 47.4.2 |
| **Mappings** | Official (mojmap) |
| **Java** | 17 |
| **Key dependency** | Valkyrien Skies 2 (`2.4.11`) |
| **Status** | Pre-alpha |

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
