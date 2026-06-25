[Home](Home) > [Blog](Blog) > 2026-06-25 — Nether Polish: Dark Carriages, Spawn Gates & Live Mirroring

# 2026-06-25 — Nether Polish: Dark Carriages, Spawn Gates & Live Mirroring

**Latest build:** [v0.363.11 on GitHub](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.363.11) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/dungeon-train/files) · [Modrinth](https://modrinth.com/mod/dungeon-train/versions)

Following yesterday's Nether arrival, today's work is all polish. The train now wears blackstone track and tunnel sections through the Nether band, a new dark carriage part palette gives Nether carriages their own look, the fire carriage is Nether-gated, and every weighted template in the editor can now be restricted to a difficulty-level range and a set of world phases. The in-game editor also gains live three-axis mirroring across all template types. Here is everything that shipped in the last 24 hours.

---

## Dark Nether Carriage Parts — v0.363.0

### New Dark Part Set

A brand-new carriage part palette arrives for the Nether: dark walls, floors, roofs, and doors built from polished blackstone, nether bricks, and basalt. These parts give Nether carriages a look that fits the world they ride through. The existing stone-brick palette stays for the Overworld stretch; the dark set takes over automatically inside the Nether band.

### Fire Carriage is Now Nether-Only

The fire carriage template has been rebuilt on top of the new dark parts and is now phase-gated: it only spawns when the train is in the Nether. You will not find a fire carriage rolling through the Overworld or the End.

---

## Nether Track & Tunnel Set + Spawn Gating — v0.362.0

### Blackstone Nether Track & Tunnels

The train's infrastructure through the Nether now has its own look. Blackstone-themed track tiles, tunnel sections, and tunnel portals replace the stone-brick set as soon as you cross into the Nether band. Each piece is styled to feel at home in the heat and darkness of netherrack terrain.

### Template Spawn Rules: Level Range and Phase

Any weighted train template — carriages, interiors, track tiles, tunnel sections — can now carry spawn restrictions: a minimum and maximum difficulty level, and a set of world phases (Overworld, Nether, Void, End). Templates that do not match the current level and phase are quietly skipped when the generator rolls the train's composition.

Authors set these limits directly in the in-game editor. Both the menu panel and the floating world-space panel now show min/max level steppers and a phase selector on every weighted template. There is no JSON to edit by hand.

---

## Live Editor Mirroring — v0.362.0

### Three-Axis Live Mirroring on All Templates

The template editor now mirrors your edits as you build. Place or break a block and its reflections update instantly across all enabled axes — no save or exit required. The new **Y axis** mirrors vertically, flipping slabs, stairs, doors, and trapdoors top-to-bottom automatically. X (left/right) and Z (front/back) mirroring were already available for tunnels; both now extend to every editor template — carriages, parts, tracks, pillars, and tunnels.

Mirroring is **off by default** on all templates except tunnels, which keep X + Z on as before. Toggle Mirror X, Mirror Y, and Mirror Z from the editor's X-menu. The mirroring is a purely an editing aid — generated structures in-world are unaffected.

### Per-Axis Tunnel Mirror Toggles

Previously the tunnel editor's mirroring was all-or-nothing. It is now split into independent X and Z toggles, each controllable from the X-menu's single Mirror row. Existing tunnels are unchanged: both axes remain on by default.

---

## Bug Fix

### Remote Echo Journal Always Opens — v0.361.0

When a remote echo (a traveller from another player's world) spawned on a distant carriage and the player walked up later, the encounter story was silently skipped — the Discord journal never opened because the echo's spawn was out of range. The journal now opens retroactively on first close approach, regardless of when the echo spawned, so every remote echo meeting gets its story.

---

## Auto-Balance Cascade

Throughout the day, the automated loot-balance cascade made a series of micro-adjustments to food item weights in the sandbox loot tables — apple, bread, carrot, potato, and wheat each nudged up or down by 1 across twelve ticks (v0.363.1 through v0.363.11). These are background maintenance ticks with no action needed on your part.

---

*Releases shipped today: [v0.361.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.361.0) · [v0.362.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.362.0) · [v0.363.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.363.0)*
