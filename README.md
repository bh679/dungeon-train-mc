# Dungeon Train (Minecraft Mod)

[![Build](https://github.com/bh679/dungeon-train-mc/actions/workflows/build.yml/badge.svg)](https://github.com/bh679/dungeon-train-mc/actions/workflows/build.yml)
[![Modrinth](https://img.shields.io/modrinth/dt/dungeon-train?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/dungeon-train)
[![CurseForge](https://cf.way2muchnoise.eu/title/dungeon-train.svg)](https://curseforge.com/minecraft/mc-mods/dungeon-train)

Dungeon Train MC is a Minecraft Mod adding an infinitely procedural train.

Inspired by an episode of Adventure Time, where Finn and Jake find themselves on an infinite train battling enemies and collecting loot from carriage to carriage.

## Download

- [Modrinth (Recommended)](https://modrinth.com/mod/dungeon-train)
- [Download Page](https://github.com/bh679/dungeon-train-mc/wiki/Downloads)

New to the mod? See [Installation](https://github.com/bh679/dungeon-train-mc/wiki/Installation) for the full Forge + Valkyrien Skies setup.

## Links

- [Wiki](https://github.com/bh679/dungeon-train-mc/wiki)
- [Blog](https://github.com/bh679/dungeon-train-mc/wiki/Blog)
- [Original game (browser)](https://brennanhatton.itch.io/dungeontrain)

## Specs

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
