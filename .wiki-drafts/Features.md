# Features

Every feature Dungeon Train ships with. Click through for full details — commands, implementation notes, and known limitations.

## [Train](Feature-Train)

**Since:** [v0.3.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.3.0) · latest: [v0.95.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.95.0) ([PR #132](https://github.com/bh679/dungeon-train-mc/pull/132))

The core feature — `/dungeontrain spawn [count]` builds an N-carriage train moving at 2 m/s along +X, with three built-in carriage types (standard, windowed, flatbed) plus `open` and (since v0.95.0) `pen` customs in the random pool, laid end-to-end so the interior is one continuous corridor. As of v0.83.0 each carriage is its own [Sable](https://github.com/ryanhcode/sable) sub-level (kinematically driven, ride-along-safe), and groups sharing a `trainId` are extended ahead of the player by an append-only spawner ([`TrainCarriageAppender`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/train/TrainCarriageAppender.java)). v0.88.0 retired `solid_roof` and the four legacy customs (`oneside`, `otherside`, `opensides`, `sunroof`) so authors could re-author from scratch on top of the new walls/roof part library; the LOOPING cycle is now STANDARD → WINDOWED → FLATBED. v0.89.0 replaces the fixed-tick spawn throttle with a per-carriage **placement state machine** — each spawn runs a 1×3×5 collision check every tick, nudges itself ±0.5 X out of any overlap, and only goes "placed" after 60 clean ticks; the next group spawn waits on the previous carriage's placement-success flag, and auto-spawn extends backward off the tail when a player walks behind the train. v0.94.0 splits the spawn pipeline into two **independent direction lanes** — forward and backward each have their own placement-success gate, so a still-settling carriage at one end never blocks the other end's growth, and both ends can spawn in the same tick when both need extension. v0.95.0 ships the first **author-content pack** — `pen` carriage + `cows`/`pigs`/`sheep` contents + `dirt` floor part, paired through bundled `<id>.contents-allow.json` sidecars so farm interiors only spawn inside `pen` and the workshop carriages stay loot-themed.

→ [Read more](Feature-Train)

## [Login spawn placement](Feature-Login-Spawn)

**Since:** [v0.83.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.83.0) ([PR #120](https://github.com/bh679/dungeon-train-mc/pull/120)) · latest: [v0.181.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.181.0) ([PR #227](https://github.com/bh679/dungeon-train-mc/pull/227))

The first frame the player sees on a fresh world is already at the cached spawn placement next to the train — no vanilla `(0, ~64, 0)` flash, no visible jump. A `PlayerFudgeSpawnMixin` overrides `ServerPlayer.adjustSpawnLocation` to short-circuit to a `SpawnPlacement` cached at server-start. Multi-stage fallback: buffered-X scan with 40-block clear window → random retry → perpendicular walk capped at 60 blocks → **X-slide along the track ±300 blocks** → on-train-roof last resort. Never lands on the rails, never in water, never inside a leaf canopy. v0.181.0 adds a **per-candidate raycast LOS check** (`Level.clip` to `trainCenter.y + 8`) so every accepted spawn has an unobstructed sight line to the train; when no candidate at the original anchor passes LOS, the X-slide phase steps the anchor along the track (skipping tunnel-buried columns) until one does.

→ [Read more](Feature-Login-Spawn)

## [Spawn cinematic + spawn-on-train](Feature-Spawn-Cinematic)

**Since:** v0.293.0 ([PR #365](https://github.com/bh679/dungeon-train-mc/pull/365))

On first entry to a world the player now spawns **standing on a flatbed of the moving train**, and a locked fly-up **intro camera** reveals the train before handing over control (Space to skip; no HUD during the shot). The [login-spawn](Feature-Login-Spawn) ground pose is repurposed as the camera's start (now allowed over water), and the [welcome-book lightning](Feature-Starting-Books) is held until the cinematic ends. Plays once per player per world; toggle via `introCinematicEnabled`.

→ [Read more](Feature-Spawn-Cinematic)

## ~~[Successor-train chain](Feature-Train-Chain)~~ — *retired*

**Active:** [v0.40.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.40.0) – [v0.82.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.82.0). The Sable migration (per-carriage sub-levels, [PR #119](https://github.com/bh679/dungeon-train-mc/pull/119)) eliminated the 128-chunk shipyard wall this feature worked around, so the entire mechanism — `TrainChainManager`, `spawnSuccessor`, `forwardLimit`/`backwardLimit` — was removed. Page kept as historical context only.

→ [Historical context](Feature-Train-Chain)

## [Settings page and `/dungeontrain speed`](Feature-Settings-and-Speed)

**Since:** [v0.11.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.11.0)

Carriage count, target speed, and spawn Y are editable from an in-game settings screen (Esc → Mods → Dungeon Train → Config) and persisted per-world in `dungeontrain-server.toml`. The `/dungeontrain speed <value>` OP command offers the same tweak from a command source, and live trains accelerate/decelerate immediately.

→ [Read more](Feature-Settings-and-Speed)

## [Starter train on login + configurable spawn Y](Feature-Starter-Train)

**Since:** [v0.15.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.15.0) · latest: [v0.83.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.83.0)

The mod starts itself — no `/dungeontrain spawn` needed. On server start, if no Dungeon Train is present, a 10-carriage train is auto-assembled at `(0, trainY, 0)` *before any player joins* — so the world's first frame already contains the train. Spawn altitude is configurable via `trainY` (`[-64, 320]`, default 78). The login teleport itself is its own feature now — see [Login spawn placement](Feature-Login-Spawn) for the cached-placement / mixin / fallback pipeline.

→ [Read more](Feature-Starter-Train)

## [Per-world train toggle + Y at world creation](Feature-World-Creation-Options)

**Since:** [v0.17.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.17.0)

Promotes the starter-train toggle and `trainY` from server-wide TOML to **per-world choices made on the Create World screen**. A "Dungeon Train Options…" button on the World tab opens a sub-screen with a "starts with train" checkbox and a Train Y editor; choices are baked into `dungeontrain_world.dat` and isolated per world.

→ [Read more](Feature-World-Creation-Options)

## ["Dungeon Train" world type (raised floor + dimension picker)](Feature-World-Type)

**Since:** [v0.16.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.16.0) · latest: post-[v0.74.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.74.0) ([PR #105](https://github.com/bh679/dungeon-train-mc/pull/105))

A custom world preset whose overworld floor sits at y=32 instead of vanilla y=-64, removing the unused deep-basement layers (~24% fewer noise samples per chunk-gen). An 11-option Floor Y dropdown on the World tab lets the player pick between −64 and 96 at world creation, and the preset is default-selected when the Create World screen opens. PR #105 adds two more World Type entries — **Dungeon Train (Nether)** and **Dungeon Train (End)** — that start the player and the auto-spawned train in the chosen dimension on first login (worldgen for those dimensions stays vanilla; only the starting dimension changes).

→ [Read more](Feature-World-Type)

## [In-world carriage template editor](Feature-Carriage-Editor)

**Since:** [v0.13.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.13.0) · latest: [v0.362.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.362.0) ([PR #580](https://github.com/bh679/dungeon-train-mc/pull/580), [PR #581](https://github.com/bh679/dungeon-train-mc/pull/581))

v0.362.0 adds **live three-axis mirroring** across all editor templates: place or break a block and reflections update instantly across enabled X (left/right), Y (vertical — flips slabs/stairs/doors), and Z (front/back) axes, with toggles in the editor X-menu. Tunnels keep X + Z on by default; all other template types start with mirroring off. Per-axis tunnel mirror toggles (X and Z independently) also ship in the same release.

Build your own carriage variants directly in Minecraft and save them as structure NBT. `/dungeontrain editor enter <variant>` teleports you to a dedicated plot wrapped in a bedrock cage; whatever you build inside is captured on `save` and stamped whenever that variant spawns. v0.25.0 adds a three-tier loader (config override → bundled jar resource → hardcoded), plus `/editor devmode` and `/editor promote` to ship authored templates with the mod. v0.26.0 adds unlimited custom named variants via `/editor new <name> [source]` (seeded from an existing variant) and rename-on-save via `/editor save [new_name]` (`standard`/`flatbed` protected), with all variants joining the rolling-cycle so long trains show every registered one. v0.31.0 adds **variant blocks** — hold the variant-place hotkey (default Z, see v0.50.0) and right-click a block in the plot with another block in hand to mark that position as random across the held candidates; every spawned carriage rolls a different one deterministically. Particle outlines on flagged blocks, client-side item-icon HUD on hover. v0.35.0 adds an **empty-space placeholder** — a command block added as a variant acts as a sentinel meaning "render as air at spawn time," so positions can randomly be gaps without authoring a second template. v0.36.0 unifies the carriage / pillar / tunnel / track editors under **Carriages** / **Tracks** / **Architecture** categories with `/dt editor <category>`, adds a top-centre status HUD (with a yellow `[DEV]` badge while devmode is on), a short `/dt` alias for every `/dungeontrain` subcommand, and context-aware `/dt save [default]` / `/dt save all [default]` / `/dt reset [default]` that dispatch to whichever plot the player is standing in. v0.40.0 swaps the invisible barrier-block cage for **bedrock** so bounds are visible in every game mode. v0.41.0 adds **carriage parts** — four reusable kinds (FLOOR / WALLS / ROOF / DOORS) authored as their own NBT templates and overlaid on top of the monolithic carriage NBT at spawn time; a per-variant `<id>.parts.json` sidecar declares per-kind candidate names with deterministic per-carriage random pick, so a single variant can render different floors / walls / roofs / doors from car to car. Parts get their own 2D plot grid in the Carriages category (kind on Z, name on X), per-`(kind, name)` variant authoring (Hold Z + right-click), and a `Save / All` + `New / Remove` editor menu split. Variants without a `parts.json` sidecar render exactly as before — fully opt-in. v0.42.0 adds a **`Clear` button** to the editor menu (carriages / contents / parts) that wipes every interior block of the current plot to air, gated behind a `ConfirmScreen` — distinct from `Reset` (which deletes the on-disk template), Clear only mutates the world so the author can rebuild from a clean slab without re-registering. Barrier cage survives, contents-plot Clear preserves the carriage shell stamped as visual context. v0.43.0 adds a **New source picker** — clicking `New` drills into a Blank / Current / Standard chooser before naming, so authors can start an empty plot, copy the model they're standing in, or copy the canonical baseline. Player is automatically teleported into the new plot after creation. Applies to carriages, contents, and parts. v0.50.0 moves the variant-place modifier from sneak to a rebindable Forge `KeyMapping` (default **Z**) under the *Dungeon Train* Controls category — sneak no longer hijacks placement, so authors can move-while-clicking inside the editor.

→ [Read more](Feature-Carriage-Editor)

## [Carriage contents variants (editable interiors)](Feature-Carriage-Contents)

**Since:** [v0.36.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.36.0) · latest: v0.300.0 ([PR #377](https://github.com/bh679/dungeon-train-mc/pull/377))

A second catalogue of NBT templates — this time *inside* the carriage shell. `/dungeontrain editor contents enter <name> [shell]` teleports you to a plot at y=250 z=80 where the chosen shell is stamped as non-editable context and the contents fill the interior. Every spawned carriage rolls a contents variant deterministically from `(world seed, carriage index)` — same math as the shell pick, independent catalogue — so walking back over the same stretch always shows the same mix. The `default` built-in fallback places a single `stone_pressure_plate` at the floor centre, so every pre-existing world gets a minimal default without authoring. Flatbed carriages are skipped (no walls → any interior would float visibly). Decorative entities (armor stands, paintings, item frames) save and load in the editor plot; ship-side render is blocked by a VS limitation documented in [Compatibility](Compatibility). v0.92.0 adds a [per-carriage allow-list](Feature-Carriage-Contents#per-carriage-allow-list-v0920) — a **Contents** drilldown in the X-menu (carriage editor only) with one red/green Toggle per registered content, persisted as a `<id>.contents-allow.json` sidecar; `OFF` excludes that content from this carriage's spawn pool. Author-side knob for shaping which contents pair with which shells. v0.97.0 ships three more bundled contents (`library`, `seating`, `shop`), wires up dev-mode write-through for the allow-list sidecar so X-menu Contents toggles ship in the next build alongside parts and weights edits, and adds a `window2` wall to the `windowed` pool. v0.300.0 turns `shop` into a group of themed merchant carriages — a **toolsmith** plus a new **leatherworker** stall (group weight 3) whose chest rolls leather, the full leather-armour set, leather horse armour, a complete dye set, emeralds, a cauldron and a potion via the bundled `lethershopchest` loot table.

→ [Read more](Feature-Carriage-Contents)

## [Procedural item naming](Feature-Item-Naming)

**Since:** [v0.191.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.191.0) ([PR #237](https://github.com/bh679/dungeon-train-mc/pull/237))

Naturally-spawned weapons, tools, shields, and armor roll a procedurally-composed display name at chest/pot generation time — *"Doombringer of Dreams, Wraith of Iron Blade"*, *"Pope of Cherry Grove Hoofs"*, *"Supreme World Overlord of TechnoBlade"*. Ports the original Unity DungeonTrain `SharedLines` weighted-text system: ~1,400 entries across 33 themed pools (Title Prefix, Title Post-Prefix, Food, Places, Animals, Names, Music, Discord People, Colors, Feelings, Materials/Elements, plus 11 MC-themed pools — Dimensions, Biomes, Hostile Mobs, Lore, TechnoBlade epithets, Minigames, Enchantments, etc.). 30% naming gate for plain items, 50% for enchanted (enchanted items roll the longer 4-segment chain). Material slot reads the item's actual material (Iron, Diamond, Netherite, ...) 50% of the time, or rolls a fantasy element (Mithril, The Night, Crystal) the other 50%. Item-type synonym suffix (Blade / Tunic / Hoofs / Wood-Wrecker / Stocking-Things) attaches with weighted placement — 65% none, 30% end, 4% start, 1% "X of …". Deterministic per chest position — same world seed always rolls the same names. Crafted items stay vanilla-named; the hook is wired to `ContainerContentsRoller.rollItemStack` only.

→ [Read more](Feature-Item-Naming)

## [Procedural armor appearance — dye & trim](Feature-Armor-Appearance)

**Since:** [v0.193.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.193.0) ([PR #241](https://github.com/bh679/dungeon-train-mc/pull/241))

Naturally-spawned armor now rolls two independent cosmetic effects alongside the existing naming gate. **Leather** pieces have a 30 % chance to be dyed in one of the 16 vanilla `DyeColor`s (white, orange, magenta, … black) via the standard `DYED_COLOR` data component. **Any `ArmorItem`** (leather + chainmail + iron + gold + diamond + netherite + turtle) has an independent 15 % chance to receive a random armor trim — pattern picked uniformly across the registered patterns, material weighted toward common metals (iron / copper / redstone / quartz at weight 3, gold / lapis / amethyst at 2, diamond / emerald / netherite at 1). A leather chestplate can roll both a dye AND a trim on the same spawn. Rolls share the existing `(world seed, carriage index, slot)` determinism mix and use five independent salts (`SALT_DYE_*`, `SALT_TRIM_*`), so adding the effect leaves prior durability/enchantment/name outcomes untouched on old worlds. Crafted armor is unaffected — the hook lives in `ContainerContentsRoller.rollItemStack`, the same single entry point used by procedural item naming.

→ [Read more](Feature-Armor-Appearance)

## [Weighted carriage selection](Feature-Carriage-Weighting)

**Since:** [v0.37.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.37.0) · latest: [v0.362.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.362.0) ([PR #582](https://github.com/bh679/dungeon-train-mc/pull/582))

Per-variant integer weights (0..100) bias the seeded random carriage pick in `RANDOM` + `RANDOM_GROUPED` modes — set `standard=5, windowed=0` to make one variant dominate and another never appear, without changing `LOOPING` or the fixed-flatbed separator rhythm. `/dungeontrain editor weight <variant> <n>` edits live from in-game (writes `config/dungeontrain/weights.json`, reloads immediately); the editor status HUD grows a second centred line `weight = N` for carriage plots, orange when zero. Default weight is `1`, so worlds with no `weights.json` produce the pre-feature uniform distribution byte-for-byte.

v0.362.0 extends weighted templates with **per-template spawn gating**: any weighted template (carriage, interior, track tile, tunnel section) can now carry a min/max difficulty-level range and a set of world phases (Overworld, Nether, Void, End). Templates that do not match the train's current level and phase are skipped at spawn time. Authors set the level steppers and the phase selector directly in the in-game editor — both the menu panel and the floating world-space panel expose these controls on every weighted template.

→ [Read more](Feature-Carriage-Weighting)

## [Auto-generating static tracks beneath the train](Feature-Track-Generation)

**Since:** [v0.20.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.20.0) · latest: [v0.46.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.46.0)

A 5-wide stone-brick bridge deck with two east-west rails auto-generates in world space below every Dungeon Train, with stone-brick pillars descending to solid terrain wherever the bed floats over air, water, or forest canopy. Tall pillars get wider spacing and thickness so deep terrain reads as a viaduct instead of toothpicks. v0.30.0 splits pillars into three editable `StructureTemplate` sections — top cap, repeating middle, ground base — each designable across the full track width via `/dungeontrain editor pillar enter <section>`. v0.33.0 makes the **bed + rail rows** editable too — a 4×2×W tile authored via `/dungeontrain editor track enter` that tiles every 4 blocks along the train axis, plus a `clearBlocksAhead` floor clamp so the authored rail layer can't be destroyed by VS-AABB float precision. v0.38.0 adds **stair adjuncts** — a fourth editable template (`PillarAdjunct.STAIRS`, 3×8×3) that spawns alongside roughly every 40 blocks of track, alternating +Z / −Z sides via `Mirror.LEFT_RIGHT`; authored under the same `/dungeontrain editor pillar` subtree (`enter stairs`). v0.46.0 brings every track-side template — track tile, all 3 pillar sections, stairs, and both tunnel kinds — full **variant parity with carriages**: multiple named NBTs per kind, weighted random pick per instance, per-block randomization sidecars, and a unified 5-block-gap editor grid. Placed blocks are plain world state, so the bridge stays behind the train forever.

→ [Read more](Feature-Track-Generation)

## [Track-side template variants](Feature-Track-Variants)

**Since:** [v0.46.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.46.0) · latest: [v0.362.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.362.0) ([PR #582](https://github.com/bh679/dungeon-train-mc/pull/582))

Every track-side template (track tile, pillar TOP/MID/BOT, stairs adjunct, tunnel SECTION/PORTAL) supports the same two layers of authorable variation that carriages do: **multiple named NBTs per kind** with weighted random pick per instance, and **per-block randomization sidecars** (`*.variants.json`) for shift-right-click cell-level randomization. Editor plots laid out on a unified 5-block-gap grid — track-side row at `Z=74`, variants along `+Z` per kind, pillar parts and tunnel kinds stacked on `+Y`. New `/dungeontrain editor tracks new <kind> <name>` clones the variant the player is currently on; `/tracks reset <kind>` deletes it. Legacy single-file paths (`pillars/pillar_*.nbt`, `tunnels/section.nbt`, etc.) auto-migrate to `<kind>/default.nbt` on first server start.

v0.362.0 ships a **blackstone Nether track set** — Nether-phase-gated track tiles, tunnel sections, and tunnel portals built from blackstone replace the stone-brick set inside the Nether band. The dark carriage **part set** (v0.363.0) adds polished-blackstone/nether-brick/basalt walls, floors, roofs, and doors as a Nether-only carriage part palette.

→ [Read more](Feature-Track-Variants)

## [World-space block-variant menu](Feature-Block-Variant-Menu)

**Since:** [v0.56.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.56.0) · latest: [v0.282.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.282.0) ([PR #348](https://github.com/bh679/dungeon-train-mc/pull/348))

A billboarded worldspace UI for managing per-cell block variants in any editor plot (carriage / contents / part / track). Tap **Z** while looking at a flagged block to open a panel anchored to the targeted face listing every candidate's name + weight, with a six-cell toolbar `Copy | Add | Lock | Remove | Clear | X`. Hold-Z + right-click still appends a held block — the legacy flow from v0.31.0 is preserved; tap-vs-hold detection runs client-side (≤ 8-tick press without an interaction = tap). `Add` captures the player's main hand; empty hand → the air-sentinel (rendered as `nothing`). `Copy` produces a `dungeontrain:variant_clipboard` ItemStack visually mimicking a vanilla command block (its model parents `minecraft:block/command_block`); placing it elsewhere creates a new flagged cell with the same list. **Lock groups**: clicking `Lock` cycles the cell's lock-id `0 → nextFreeLockId() → 0` (template-local positive integer). Cells with the same non-zero lock-id share their state list (auto-propagated on edit) AND their random pick at spawn — `CarriageVariantBlocks.pickIndexFromLockGroup` hashes the lock-id where the unlocked path hashes the local position, so every cell in the group lands on the same index. The only way two cells join a group is via Copy/Paste of the clipboard. Schema bumped v3 → v4 (cells with `lockId>0` now serialise as `{ "lockId": K, "states": [...] }`; lockId=0 cells stay as bare arrays so v3 sidecars round-trip diff-clean). Protocol bumped 4 → 5 with three new packets. First `DeferredRegister<Item>` in the project. **v0.174.0** adds **mob entries** (schema v6 → v7): hold a vanilla `SpawnEggItem` instead of a block on either authoring path (Z + right-click or C-menu **Add**) and the candidate becomes a mob spawn — when it rolls at carriage spawn, the cell becomes air and the mob spawns at that position via the existing 48-block deferred entity pass. The `VariantState` record gains an optional `entityId` field; mob entries auto-stamp the COMMAND_BLOCK sentinel as their `state` so every existing applier branch routes through the AIR-on-empty path. The C-menu and HUD overlay render the spawn-egg item icon and a light-purple `(mob)` label. Track/tunnel sidecars accept mob entries but currently log+air at runtime (carriage-side queueing infra only) — non-carriage mob spawning is a follow-up. **v0.239.1** adds a **T/R/B half-mode pill** (Top / Random / Bottom) to rows whose block has `SLAB_TYPE` or `HALF` — slabs, stairs, and trapdoors. Cycle on tap; `T` and `B` lock the half explicitly, `R` rolls TOP/BOTTOM at spawn via the existing flip seed. Stairs and trapdoors keep their L/R/O facing pill and gain the new T/R/B pill alongside (independent control over facing and half). The `"half"` JSON field is additive on v7 entries — no schema bump; legacy entries derive their value at read time (LOCK rotation → halfMode from captured `SLAB_TYPE`/`HALF`, RANDOM → halfMode=RANDOM), so existing prefabs spawn bit-for-bit identically. **v0.282.0** ([PR #348](https://github.com/bh679/dungeon-train-mc/pull/348)) makes **slimes and magma cubes** spawned from mob entries roll a random size (1–4) per spawn — the variant spawn path skips vanilla's `finalizeSpawn` size roll, so previously every variant slime appeared at the smallest size; a spawn egg tagged with an explicit `Size` keeps it.

→ [Read more](Feature-Block-Variant-Menu)

## [World-space container contents menu](Feature-Container-Contents-Menu)

**Since:** [v0.74.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.74.0)

A second world-space menu mirroring the [block-variant menu](Feature-Block-Variant-Menu) — this one for authoring **what items spawn inside a container**. Tap **C** while looking at any block with an inventory BlockEntity (chest, barrel, hopper, dispenser, dropper, shulker, furnace, modded containers — anything implementing `Container`) and a panel anchored against the targeted face appears with a 5-cell toolbar `Add | min | max | Clear | X` and per-row `<item-name> | xN (count max) | wN (weight) | ×`. **Add** captures the held item; **min** / **max** cycle the slot-count range that gets filled at spawn; per-entry `count` is a *maximum* (actual stack size rolls uniformly in `[1, count]` per slot). At carriage spawn, three independent deterministic rolls keyed on `(worldSeed, carriageIndex, localPos, …)` produce the inventory: K = uniform(min, effectiveMax), Fisher–Yates picks K slots, each slot weight-rolls an entry and rolls a count. Add `minecraft:air` with high weight for sparse fills. Pool sidecar at `config/dungeontrain/containers/<plotKey>.contents.json` with bundled-resource fallback at `/data/dungeontrain/containers/`. Hooked into 3 carriage placement callsites (shell, contents, parts); track / tunnel placements are follow-up. Protocol bumped 6 → 9 with four new packets.

→ [Read more](Feature-Container-Contents-Menu)

## [Prefab library (block-variant + loot)](Feature-Prefab-Library)

**Since:** post-[v0.76.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.76.0) ([PR #108](https://github.com/bh679/dungeon-train-mc/pull/108))

Two named prefab libraries authors curate by hand and browse via two new **left-side tabs** in the creative inventory. **Block-variant prefabs** are saved per-cell `List<VariantState>` snippets (the same shape the [block-variant menu](Feature-Block-Variant-Menu)'s Copy button captures); **loot prefabs** are saved per-container `ContainerContentsPool`s captured from the [container-contents menu](Feature-Container-Contents-Menu). Save buttons added to both world-space menus open a modal name screen; on submit the server writes a JSON sidecar and broadcasts a registry sync. Tab entries are vanilla `BlockItem` stacks (oak_planks, chest, barrel, etc.) with discriminator NBT, so the icon matches the source block exactly and tooltips are decorated with the prefab id. Right-clicking a block-variant prefab pastes the snippet to a target cell (cancels vanilla placement); right-clicking a loot prefab places the actual container block normally and a post-place handler rolls the pool to fill the new BlockEntity. Loot prefabs work anywhere; block-variant prefabs require an editor plot. Protocol bumped 9 → 11.

→ [Read more](Feature-Prefab-Library)

## [Editor floating panels + template-type menus + on-top teleport](Feature-Editor-Floating-Panels)

**Since:** [v0.115.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.115.0) ([PR #156](https://github.com/bh679/dungeon-train-mc/pull/156))

Three new in-world UI families that float above editor templates plus a landing-on-top default teleport. Per-plot label panels (name + `×N` weight + `[-]`/`[+]` arrows + Save/Reset/Clear + Contents + Enter buttons) hover above each cage with a green frame for the player's current plot. Template-type menus at the start of every variant row list every variant — name click teleports, weight cell click bumps `+1` (shift `-1`), header click jumps to the first variant; the active row is tinted green. When the player is in a plot, a companion duplicate of the type menu floats next to the per-plot panel sharing its billboard frame. Every editor `enter()` (slash commands + every panel/menu name click) now lands the player **on top** of the cage instead of inside; the per-plot panel's new `[Enter]` button explicitly teleports to the floor. Both panel families scale via the Options menu's worldspace slider. Three new packets, `PROTOCOL_VERSION` 16 → 19. v0.135.0 replaces the per-row type menu with a full [template navigation menu](Feature-Template-Navigation-Menu) — see that page for the layout and dispatch changes.

→ [Read more](Feature-Editor-Floating-Panels)

## [Template navigation menu](Feature-Template-Navigation-Menu)

**Since:** [v0.135.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.135.0) ([PR #176](https://github.com/bh679/dungeon-train-mc/pull/176))

Replaces every per-row type panel with one wider nav panel at every row start: a category bar across the top (`Carriages | Contents | Tracks`, click to run `/dt editor <id>`), a type-tab strip with the row's own type expanded leftmost and every other type collapsed to a clickable header that teleports to its first variant, the variant list inside the expanded column unchanged, and inline sub-variants drawn as horizontal cells next to **every** variant that has children — not just the active one. Server populates the per-variant `subVariants` list from `CarriageContentsGroupStore` for CONTENTS variants. Panel grows up to `2.25×` its base width to fit wide sub-variant rows; past that, the row wraps once below the variant (hard cap at 2 lines, overflow truncated). The standalone sub-variants companion next to the per-plot panel — including the sub-variant weight editor from [v0.134.0 / PR #175](https://github.com/bh679/dungeon-train-mc/pull/175) — is untouched. Wire format: `Menu` gains `activeCategoryId`/`categoryBar`/`typeStrip`; `Variant` gains a recursive `subVariants` list. ARCHITECTURE is filtered out until it has stamped plots.

→ [Read more](Feature-Template-Navigation-Menu)

## [Editor help panel](Feature-Editor-Help-Panel)

**Since:** [v0.146.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.146.0) ([PR #187](https://github.com/bh679/dungeon-train-mc/pull/187))

A worldspace onboarding panel that appears beside the first [template navigation menu](Feature-Template-Navigation-Menu) every time the editor is active. Teaches the three keybinds (`X` Editor Menu, `C` Container Menu, `Z` Block Variant Menu) and frames user-content authoring. A clickable "Read the Wiki to learn more" button opens the [Editor](Editor) wiki page via vanilla `ConfirmLinkScreen` — same UX as the title-screen Discord button. The panel has its own cylindrical-billboard anchor (offset 5 blocks perpendicular to the row direction) so it stays at a stable world position and rotates independently to face the camera, instead of orbiting the nav menu's anchor. Pure client-side: one renderer, one raycast, one input handler, eight new lang keys — no new packets, no server-side touches.

→ [Read more](Feature-Editor-Help-Panel)

## [In-world command menu](Feature-Command-Menu)

**Since:** [v0.39.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.39.0) · latest: [v0.116.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.116.0) ([PR #157](https://github.com/bh679/dungeon-train-mc/pull/157))

A worldspace point-and-click menu toggled by the `X` key that mirrors a curated slice of `/dungeontrain` commands. A flat, billboarded panel floats 2.5 blocks in front of the player; crosshair-raycast hover, click to select, world keeps ticking. Automatically switches between a main menu (Editor / Train ▸ / Options ▸ / Debug ▸) and an editor menu (DevMode toggle, Enter category picker, Save ‖ All, Reset, Clear, New ‖ Remove with confirmation screens) based on whether the player is standing in an editor plot. Also promotes Contents to a first-class editor category so `/dt editor contents` loads every content plot at once and `/dt save` / `/dt reset` dispatch correctly inside them. v0.42.0 adds the `Clear` button — wipes every interior block of the current plot to air without touching the on-disk template; barrier cage survives and contents-plot Clear preserves the carriage shell stamped as visual context. v0.43.0 swaps `New`'s straight-to-typing flow for a Blank / Current / Standard source picker (plus auto-teleport into the new plot after naming), and changes menu activation to fire on **mouse release** rather than press — slide off a button before releasing to cancel a click. v0.45.0 pairs `Reset | Clear` on a single 50:50 row, adds a `[-] Weight (N) [+]` nudge row for carriage variants, introduces reusable `Stay` and `Triple` menu primitives, adds a **`Rename` button** (parts / carriages / contents) with the typing field pre-filled to the current id and hidden for protected built-ins, and moves the typing field **inline at the clicked button row** instead of at the top of the panel. v0.57.0 replaces the editor menu's bottom `< Back` row with an **`Exit`** button that runs `/dt editor exit` — unwinds the editor session and teleports the player back to where they entered, instead of merely popping to the main menu while leaving them stranded in the plot. v0.90.0 promotes the main menu's flat `Debug Scan` row to a `Debug ▸` drill-in containing a new **Wireframes ▸** sub-menu — five individually toggleable debug overlays (gap cubes, gap line, next-spawn preview, post-spawn collision box, HUD Δx line) plus an `All On / All Off` Split master at the top. The post-spawn collision wireframe — always-on between v0.89.0 and v0.90.0 — now defaults off like the others. v0.107.0 adds an **Options ▸** submenu with three independent display-scale steppers — All Displays / Worldspace / HUD, range `[0.2, 2.0]` snapped to multiples of `0.1`. Worldspace scales the X menu and every editor menu uniformly (renderer scales `PoseStack`; raycast divides hit point by the same factor); HUD scales the version overlay and editor status bar via a new `HudText` helper. Defaults to a more compact UI (Worldspace `0.7`, HUD `0.4`); persists to a new client-scoped config at `<minecraft>/config/dungeontrain-client.toml`. v0.112.0 gates **cross-category clicks in `Enter ▸`** behind an unsaved-changes confirmation screen — comparing live block state to a post-stamp snapshot baseline (kept stable against parts overlay drift and the variant-cell preview ticker), surfacing per-template name/Save/View rows with a Continue button that flips between `Don't save - continue` and `Continue` based on outstanding edits. Carriages, contents, track tile, pillar sections, adjuncts, and tunnels are all detected. Same-category clicks keep the [v0.109.0](#enter-template-list-v01090) templates picker. New packets `EditorUnsavedRequest/List` and `EditorChangesRequest/List` carry the lists; protocol bumped to 16. v0.116.0 adds a **`+ New` footer row** to every floating template-type menu (the v0.115.0 worldspace panels at the start of each variant row), one-click authoring a new variant of the type the player is looking at — clicking opens the keyboard `X` menu rooted directly at the [source picker](Feature-Command-Menu#new-source-picker-v0430) for that menu's category via a new `CommandMenuState.openAt(MenuScreen)` (single-screen stack, so `< Back` closes the menu cleanly instead of falling through to MainMenu / EditorMenu). Carriages / contents / parts route to the existing Blank / Current / Standard picker; tracks collapse to a single name TypeArg via a new `NewSourcePickerScreen.Category.TRACKS`, mirroring `EditorMenuScreen.newEntryFor` for tracks. Pure additive client-side wiring on top of existing slash commands.

→ [Read more](Feature-Command-Menu)

## [Narrative lectern + book progression](Feature-Narrative-Lectern)

**Since:** [v0.177.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.177.0) ([PR #223](https://github.com/bh679/dungeon-train-mc/pull/223)) · latest: [v0.184.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.184.0) ([PR #230](https://github.com/bh679/dungeon-train-mc/pull/230))

The first **custom block** in the codebase and the first **light-emitting block** — a vanilla-lectern look-alike that loads ~50 KB of long-form narrative from the [original itch.io game](https://brennanhatton.itch.io/dungeontrain) as signed-book + lectern primitives. Six stories parse from `data/dungeontrain/narratives/stories/` on `ServerStartingEvent`. As of v0.184.0, the **first** right-click on a `narrative_lectern` resolves the world's next-unread letter via `BookFactory.buildOrRandomForLectern`, places it on the vanilla `LecternBlockEntity` (reused via `BlockEntityTypeAddBlocksEvent` — no custom BE), and **locks that book to the lectern**. Subsequent clicks see the BE's non-empty book and skip resolution — the locked content opens unchanged, surviving save/load via vanilla BE serialization. Progression is **world-scoped** (one cursor per save, shared by all players); each new world starts fresh. A `narrative` content cell ships in `black` / `cracked` / `pen` / `windowed` carriage templates so the lecterns drop into worlds without authoring. Commands under `/dungeontrain narrative …`: `list`, `book`, `lectern`, `spawnlectern`, `progress`, `reset`, `reload`. v0.180.0 added `.lightLevel(s -> 12)` so the lectern always glows — between glowstone (15) and a soul torch (10) — making it visible from a distance in dark carriages and reading as interactive at a glance.

→ [Read more](Feature-Narrative-Lectern)

## [Random books — chest-loot flavour with world-scoped read tracking](Feature-Random-Books)

**Since:** [v0.182.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.182.0) ([PR #228](https://github.com/bh679/dungeon-train-mc/pull/228)) · world-scoped: [v0.184.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.184.0) ([PR #230](https://github.com/bh679/dungeon-train-mc/pull/230))

A parallel content track to the [narrative lectern](Feature-Narrative-Lectern): standalone flavour books that scatter through carriage loot as vanilla written books. Each `data/dungeontrain/narratives/random_books/<id>.json` declares a book with `title`, `author`, `generation`, `weight`, and a `variants[]` array of body strings. A new editor-only placeholder item `dungeontrain:random_book` is the only thing authors place — at carriage-spawn time, `ContainerContentsRoller.rollItemStack` substitutes it with a stamped vanilla `WRITTEN_BOOK` rolled deterministically per `(worldSeed, carriageIndex, localPos, slot)`. Every rolled book carries a `RandomBookTag` identity component `(basename, variantIndex)`; a `LivingEquipmentChangeEvent` handler marks `(book, variant)` tuples as seen the first time *any* player holds them, and on re-hold of an already-world-seen tuple swaps the stack's `WRITTEN_BOOK_CONTENT` to an unseen pick via `RandomBookFactory.pickUnseenForWorld` (cycle resets silently once everything's been seen world-wide). Pre-mutation happens on equip rather than on right-click because `WrittenBookItem.use` opens the book screen client-side from the local stack before the server's response arrives. New `Dungeon Train — Narrative` creative tab groups the random book + narrative lectern; `dungeontrain:random_book` is also seeded into 16 prefab loot tables (pot=9, mining/villager/horse/loot_irongold=2, others=1). Ships with one bundled book: `musings_of_faulthurst` (16 short musings). Commands under `/dungeontrain narrative randombook …`: `list`, `give`, `progress`, `reset`. v0.184.0 moves the seen-tracking from per-player to world-scoped (matching the narrative lectern); legacy per-player data in `dungeontrain_narratives.dat` is silently dropped on first load. v0.265.1 ([PR #328](https://github.com/bh679/dungeon-train-mc/pull/328)) adds a second, organic source — breaking a vanilla bookshelf has a ~1/100 chance (config `narrative.randomBookFromBookshelfOneIn`) to drop a Random Book in place of a plain book (Silk Touch + chiseled bookshelves excluded; new item entities inherit the drop's velocity so they ride a moving Sable carriage), and rebalances the brick/fish/mining loot pools to make random books rarer.

→ [Read more](Feature-Random-Books)

## [Starting books — welcome lightning strike + read-and-burn](Feature-Starting-Books)

**Since:** [v0.185.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.185.0) ([PR #231](https://github.com/bh679/dungeon-train-mc/pull/231))

A third narrative-pool track, sibling to [Random Books](Feature-Random-Books) and the [Narrative Lectern](Feature-Narrative-Lectern). On first login and every respawn after death, a cosmetic **colored lightning strike** lands 2 blocks in front of the player — vanilla `LightningBolt` with `setVisualOnly(true)` for the flash + thunder, overlaid by a 25-block vertical column + ground burst of tinted `DustParticleOptions` particles (HSV random hue, full saturation/value — distinct vibrant color every strike). A vanilla `WRITTEN_BOOK` `ItemEntity` lands at the strike point with a 1 s pickup delay, carrying one of the 12 bundled welcome messages (12 books, 19 total variants — Brennan Intro, The Game, Blank Pages, A Paper Algorithm, etc., generated from the original itch.io game's `unused/Welcome Messages/` drafts). The strike defers 10 ticks after `PlayerLoggedInEvent` so [Login spawn placement](Feature-Login-Spawn)'s placement-retry loop finishes teleporting the player first. Closing the book screen sends a client `ScreenEvent.Closing` → server `StartingBookClosedPacket` (PROTOCOL 20 → 21); the server drops the book forward via `Player.drop(stack, false, false)` and registers it for the burn lifecycle. A single `EntityJoinLevelEvent` handler catches every starting-book `ItemEntity` (close-flow drop, Q-throw, death-drop), tags it for 60 ticks of `FLAME` + `SMOKE` particles, sets a vanilla fire block on the supporting surface, then extinguishes + discards on burn-out with a `FIRE_EXTINGUISH` poof. The lightning-spawned welcome book itself is exempt from burn via a per-entity `dt_starting_spawn_book` persistent-data flag set before `addFreshEntity`. Pagination uses a stricter rule than the other narrative loaders — every `\n\n` in the source is exactly one page break (no greedy collapse), so authors can place blank pages between sections via doubled-up breaks; locked down by 11 unit tests in `StartingBookPaginationTest`. Per-player first-login receipt persists in `NarrativeProgressData` (`starting_book_received` root-level UUID list) alongside the world-scoped story + random-book maps. Commands under `/dungeontrain narrative startingbook …`: `list`, `give`, `reload`, `reset`.

→ [Read more](Feature-Starting-Books)

## [Auto-generating stone-brick tunnels](Feature-Tunnel-Generation)

**Since:** [v0.23.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.23.0) · latest: [v0.85.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.85.0)

Wherever the train's corridor runs through thick enough rock or dirt, a stone-brick tunnel auto-generates: walls, arched roof, floor extension, and sea-lantern wall lamps every 10 blocks (baked into the section NBT). v0.29.0 turns the tunnel geometry into **editable NBT templates** — `/dungeontrain editor enter tunnel_section|tunnel_portal` lets you redesign the section + portal models in-world the same way you already customise carriages; the v0.23.0 procedural paint remains as the fallback. v0.46.0 brings tunnels into the [unified track-side variant system](Feature-Track-Variants) — both section and portal support multiple named NBTs with weighted random pick per tunnel. v0.85.0 moves placement entirely to **chunk-gen time** (no more runtime drain) and adds an extended qualifier that tolerates soft spots inside an active tunnel run; portals + 20-block approach trench are pulled out for now and will return when worldgen-time portal stamping lands.

→ [Read more](Feature-Tunnel-Generation)

## [Main menu Dungeon Train wordmark](Feature-Main-Menu-Logo)

**Since:** [v0.101.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.101.0) ([PR #139](https://github.com/bh679/dungeon-train-mc/pull/139))

The Minecraft main menu shows the **Dungeon Train** wordmark instead of the vanilla "MINECRAFT" logo, with the "Java Edition" subtitle removed. Rendered at 343×41 px (≈ 1.25× vanilla `LOGO_WIDTH`), centered, 5 px below vanilla's logo origin. A `LogoRenderer.renderLogo` mixin (`@Inject(HEAD, cancellable=true)`) bypasses vanilla's two-stacked-tile blit so the single-row 1440×173 source artwork renders cleanly at its natural aspect ratio. Panorama fade-in alpha is honored via `RenderSystem.setShaderColor`. Side effect: the Mojang vertical-flash intro (which reuses `LogoRenderer`) also shows the new wordmark.

→ [Read more](Feature-Main-Menu-Logo)

## [Main menu buttons — Discord + Train Editor](Feature-Main-Menu-Buttons)

**Since:** [v0.144.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.144.0) ([PR #185](https://github.com/bh679/dungeon-train-mc/pull/185))

Two new buttons on the vanilla title screen — **Discord** opens the project's community invite (`discord.gg/jdKAwb6rbW`) via vanilla `ConfirmLinkScreen`; **Train Editor** launches a fresh creative-mode world on the Dungeon Train default preset and automatically runs `/dungeontrain editor` once the player has spawned in (40-tick settle delay + visible chat heads-up). The Mods row is split 50/50 between the two new buttons; the existing Options + Quit Game row absorbs the displaced NeoForge Mods button at 33/33/33.

→ [Read more](Feature-Main-Menu-Buttons)

## [Pause menu buttons — Abandon This Run](Feature-Pause-Menu-Buttons)

**Since:** [v0.333.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.333.0) ([PR #492](https://github.com/bh679/dungeon-train-mc/pull/492))

The singleplayer pause menu's vanilla **"Save and Quit to Title"** slot becomes a single **red "Abandon This Run"** button — one click kills the player and opens the [narrative death screen](Feature-Death-Screen-Buttons), ending the run like any in-world death (via a serverbound `AbandonRunPacket` → `player.kill()`). Holding **Shift** swaps the red button in-place for the normal exits: **Exit to Title** (grey) and **Quit Game** (dark grey), both of which save the world and reuse the death screen's Sable-safe teardown (no [#679](https://github.com/ryanhcode/sable/issues/679) "Saving world…" hang). Singleplayer only — multiplayer keeps the vanilla Disconnect button.

→ [Read more](Feature-Pause-Menu-Buttons)

## [Narrative loader — lectern + book per Letter, with world-scoped progression](Feature-Narrative-Loader)

**Since:** [v0.178.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.178.0) ([PR #223](https://github.com/bh679/dungeon-train-mc/pull/223)) · world-scoped + lock-on-first-read: [v0.184.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.184.0) ([PR #230](https://github.com/bh679/dungeon-train-mc/pull/230))

Loads ~50 KB of long-form narrative content from the original itch.io game into Minecraft as signed-book + lectern primitives. Six stories (~120 Letters) ship as JSON in `data/dungeontrain/narratives/stories/` (loaded on `ServerStartingEvent` via the [`TrackVariantRegistry`](Feature-Track-Variants) pattern). A `narrative_lectern` block always renders with a book on top; its first right-click resolves the world's next-unread Letter via `BookFactory.buildOrRandomForLectern` and **locks that book to the BE** for the lectern's lifetime. Subsequent clicks bypass resolution. Atomic resolve + read-mark on the first click ensures a one-and-done viewer advances the world cursor. **World-scoped progression** persists per-overworld via `SavedData` (`<world>/data/dungeontrain_narratives.dat`) — one cursor for all players, fresh per save. Read detection fires on both lectern right-clicks (`PlayerInteractEvent.RightClickBlock`) and inventory book opens (`PlayerInteractEvent.RightClickItem`), with each book stamped with its `(story, letter)` identity in `DataComponents.CUSTOM_DATA`. Books are paginated to vanilla limits (≤ 256 chars/page, ≤ 100 pages) on the paragraph → sentence → word boundary chain. New `narrative` content cell + `narrative.group.json` ship two themed sub-variants (`dungeonnarrative`, `seatingnarrative`) whitelisted in `black`/`cracked`/`pen`/`windowed` carriage templates. A one-shot Python converter at `scripts/narrative/txt_to_json.py` parses the original Unity-era `.txt` format (`Letter X-` chapters, `Alt`/`Alt 2-` variant blocks, `[bracketed editor notes]` stripped + captured with `(variant, offset, text)` records). First registered block in the codebase — uses `BlockEntityTypeAddBlocksEvent` to reuse vanilla `LecternBlockEntity` instead of registering a custom BE type. Commands: `/dungeontrain narrative {list,book,lectern,spawnlectern,progress,reset,reload}`.

→ [Read more](Feature-Narrative-Loader)

## [Death screen — the narrative recap](Feature-Death-Screen-Buttons)

**Since:** [v0.315.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.315.0) ([PR #427](https://github.com/bh679/dungeon-train-mc/pull/427)) · **current layout** [v0.323.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.323.0)

Replaces the vanilla `DeathScreen` on singleplayer Dungeon Train worlds with **`NarrativeDeathScreen`** — a slow, single-button retrospective the train tells *about you*, one page at a time over a cinematic cross-fade: **the fall** (this-life headline + cause of death) → **the deeds** (combat grid + an animated portrait of the passenger you befriended or killed) → **the cargo** (most-used weapon, worn armour, loot, books) → **all your lives** (cross-world lifetime totals) → **the creator's ledger** (one [feedback-survey](Feature-Feedback-Survey) question per page) → **the platform** (**Board anew** / **Leave the line**). Pages sit over [ride-photo backgrounds](Feature-Death-Screen-Buttons#backgrounds--transitions) captured during the run. Death ends the run — there is no in-world respawn. Stats are tracked server-side per life by [`RunStatsEvents`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/event/RunStatsEvents.java) / [`PlayerRunState`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/player/PlayerRunState.java), rolled into a `DeathStatsPacket` (with the server-chosen `DeathNarrative` lore) and pushed to the client on death. **Board anew** launches a fresh random-seed save inheriting the dying world's settings + DT options; the transition mirrors vanilla "Save and Quit to Title" and pre-drains Sable train sub-levels before disconnect to avoid the Sable issue #679 `chunkMap.hasWork()` shutdown spin. Supersedes the pre-#427 two-panel run-recap layout.

→ [Read more](Feature-Death-Screen-Buttons)

## [Title screen panorama](Feature-Title-Screen-Panorama)

**Since:** [v0.102.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.102.0) ([PR #140](https://github.com/bh679/dungeon-train-mc/pull/140))

Replaces the vanilla rotating cubemap behind the main menu with six in-world captures from a Dungeon Train world. Pure resource override at `assets/minecraft/textures/gui/title/background/panorama_<0..5>.png` — Forge resource precedence beats vanilla at the same path, so the existing `PanoramaRenderer` keeps spinning and blurring the new images with no mixin or code path. Pairs with the [main menu logo replacement](Feature-Main-Menu-Logo) shipped in v0.101.0.

→ [Read more](Feature-Title-Screen-Panorama)

## [Version + branch overlay](Feature-Version-Overlay)

**Since:** [v0.7.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.7.0) · latest: [v0.73.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.73.0)

Displays `Dungeon Train v<version> (<branch>)` in the top-left corner of both the in-game HUD and the main-menu title screen. **Hidden on release builds** (jars built on `main`) — the overlay only shows on feature branches and dev builds ([PR #99](https://github.com/bh679/dungeon-train-mc/pull/99)). Values are baked at build time from `mod_version` and the current git branch, so spot-checking which build is installed never requires loading a world. v0.34.0 extends the in-game line with a live ` — Carriage: +N` suffix showing the player's closest train carriage index (0 = origin, positive forward, negative back) — pushed by the server on change, cleared when the player drifts > 128 blocks from any train.

→ [Read more](Feature-Version-Overlay)

## [Difficulty scaling — mob gear & villager trades](Feature-Difficulty-Scaling)

**Since:** [v0.227.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.227.0) ([PR #279](https://github.com/bh679/dungeon-train-mc/pull/279)) · latest: [v0.290.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.290.0) ([PR #358](https://github.com/bh679/dungeon-train-mc/pull/358))

The train gets harder the further you ride. Every hostile mob spawned in a carriage rolls armor / weapon / enchantments / potion effects scaled to a **difficulty tier** = `abs(travelledCarriageIndex) / carriagesPerTier` (default 20 carriages per tier), derived from the furthest-progressed boarded player this life — reset on death, lowered by walking the train backward. v0.230.0 replaced the original 5-rung `tiers.json` ladder with a smooth **procedural curve** ([`ProceduralTiers`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/difficulty/ProceduralTiers.java)): overlapping leather→chainmail→iron→diamond→netherite material bands, ramping slot/weapon/enchant chances, and potion effects that layer in (Speed @ tier 15, Strength @25, … Slow Fall @90) with `+1` amplifier every 25 tiers. Rolled gear is [AIN](Feature-Item-Naming)-named + AIS-statted, hostile-only, and [PlayerMobs](Feature-Carriage-Contents) scale on the same tier (no effects). **v0.290.0** caps carriage **villager** trade level to the dominant mob weapon stage — none→1, wood→2, stone→3, iron→4, diamond→5 — so merchant strength tracks the mobs you're fighting. Both default-on; `difficultyEnabled` / `carriagesPerTier` live in `dungeontrain-server.toml`.

→ [Read more](Feature-Difficulty-Scaling)

## [Advancements](Feature-Advancements)

**Since:** [v0.236.0](https://github.com/bh679/dungeon-train-mc/releases/tag/v0.236.0) ([PR #292](https://github.com/bh679/dungeon-train-mc/pull/292)) · latest: v0.310.0 ([PR #413](https://github.com/bh679/dungeon-train-mc/pull/413))

Two custom advancement tabs — **Dungeon Train** (player milestones: riding, exploring, chest streaks, narrative reading) and **Editor** (content-authoring milestones). Both persist across worlds via a per-UUID JSON sidecar at `<minecraft>/config/dungeontrain-achievements/`, so earning a milestone in one save unlocks it on every subsequent save — and since v0.310.0 ([PR #413](https://github.com/bh679/dungeon-train-mc/pull/413)) this extends to **every** advancement, vanilla Minecraft and other mods included (the hidden `recipes/*` tree stays per-world). The carts-per-life tier (Dungeon Train Explorer / Long-Haul Passenger / Nearing Infinity) is driven by [`PlayerRunState`](https://github.com/bh679/dungeon-train-mc/blob/main/src/main/java/games/brennan/dungeontrain/player/PlayerRunState.java) counters reset on death; time-on-train milestones (Settling In / Enjoying the Ride / I Live Here Now) accumulate cross-world via `GlobalPlayerStats`. v0.245.0 redefines the carts counter as **absolute** (forward + backward both count) and adds **The Long Way Back** — a side-branch goal off Dungeon Train Explorer that requires ≥100 carriages forward AND ≥100 carriages back in a single life, gated by a new `dungeontrain:carts_both_directions` trigger. v0.268.0 adds four **dimension** advancements chained into one lineage — **Inter-Dimensional Passenger** / **Void Passenger** (board the train in the Nether / End) and **Nether Return Again** / **End of the Line** (read every Nether / End starting book) — and promotes **Inter-Reality Passenger** to the grand slam over every book including those dimensions. Book-set membership is now folder-driven (`StartingBookContext.achievementSetId()`), so new sets need no engine code. v0.271.0 adds **Come Along With Me** — the first multiplayer advancement, granted on login when you join a multiplayer world or someone joins yours (every online player is awarded, via the new `dungeontrain:multiplayer_join` trigger). v0.301.0 adds a first-run **keybind hint** — earn a Dungeon Train advancement before you have ever opened the advancements screen and a grey chat line reminds you of your (rebindable) advancements key; it picks from twelve train-flavoured lines and stops for good once you open the tab.

→ [Read more](Feature-Advancements)

## [Feedback survey — death-screen NPS + difficulty poll](Feature-Feedback-Survey)

**Since:** v0.309.0 ([PR #411](https://github.com/bh679/dungeon-train-mc/pull/411)) — via [Discord Presence](Compatibility#discord-presence) 0.19.0

A **Give Feedback** button on the [death screen](Feature-Death-Screen-Buttons) opens a short survey that walks the player through one question at a time — a 0–10 rating + optional comment each — posting every answer to the community Discord. DT ships one question ("How would you rate the difficulty progression?") after Discord Presence's built-in NPS question; an answered question is never re-asked, and the button hides once everything's answered. The survey system lives in Discord Presence and loads questions from `data/<ns>/dp_surveys/*.json` data files, so any mod or datapack can add more with no code.

→ [Read more](Feature-Feedback-Survey)

## [World disintegration — repeating Void & End bands](Feature-World-Disintegration)

**Since:** v0.347.0 ([PR #533](https://github.com/bh679/dungeon-train-mc/pull/533))

As the train travels, the world periodically breaks apart into a repeating cycle — **Overworld → Void → End islands → Void → (repeat forever)** — anchored to a fixed block distance from spawn. The void stretches show the floating track suspended in empty space with the sky and fog turned to the End; the island stretches are **real End world-gen** (islands generated from the actual End noise router and trilinearly interpolated like the End's own `NoiseChunk`, with vanilla chorus plants clustered in `end_highlands` patches). Only endermen spawn in the broken bands, and the [train](Feature-Train) rides level through the whole thing on its preserved bed + rails. Phase lengths are config-driven (default 500 blocks each).

→ [Read more](Feature-World-Disintegration)

## Versioning

`mod_version` lives in `gradle.properties`. Follows [SemVer](https://semver.org):

| Change | Example |
|---|---|
| Dev commits on a branch | PATCH (`0.3.1`, `0.3.2`, …) |
| Feature merged via Gate 3 | MINOR, reset PATCH (`0.3.x` → `0.4.0`) |
| Breaking save format | MAJOR (`0.x.x` → `1.0.0`) |

Every MINOR/MAJOR bump gets a `v<version>` tag on the merge commit.
