# End-band decoration A/B

Headless same-seed comparison of a BetterEnd End-band strip across a toggle (`endBandFeatureSpill`
as the positional arg, `endBandBetterEndOnly` via `REMAP=on|off`), measured from the **region files**
rather than by eye. Built for #1785
("End-band chunks generate with no decorations") and reusable for #1746 (spill nondeterminism) or any
End-band worldgen change.

```bash
git worktree add ../dt-ab-A --detach <rev>; git worktree add ../dt-ab-B --detach <rev>   # once
bash scripts/endband/ab-arm.sh A_off off ../dt-ab-A 25571 424242
bash scripts/endband/ab-arm.sh B_on  on  ../dt-ab-B 25572 424242     # concurrently or after
read X0 X1 Z0 Z1 < scripts/endband/out/A_off.strip
python3 scripts/endband/count_decorations.py --compare \
    scripts/endband/out/A_off.world/region scripts/endband/out/B_on.world/region --x $X0 $X1 --z $Z0 $Z1

# the #1785 remap, on the zone the bare chunks were found in (straddles the corridor):
REMAP=off STRIP_X0=36272 STRIP_LEN=448 Z0=-16 Z1=15 bash scripts/endband/ab-arm.sh A_cur   off ../dt-ab-A 25571 424242
REMAP=on  STRIP_X0=36272 STRIP_LEN=448 Z0=-16 Z1=15 bash scripts/endband/ab-arm.sh B_remap off ../dt-ab-B 25572 424242
```

Environment knobs: `REMAP=on|off` (writes `endBandBetterEndOnly`; unset = the mod's default), `STRIP_IN`
(default 1500), `STRIP_X0` (absolute start, overrides `STRIP_IN`), `STRIP_LEN` (1024), `Z0`/`Z1` (32/95),
`SETTLE_SECS` (90).

## What an arm does

1. Fresh world at the pinned seed on the `dungeontrain:dungeon_train` preset; a partial
   `run/config/dungeontrain-common.toml` sets `[worldgen] endBandFeatureSpill` and `endBandTerrain = WORLDGEN`
   (NeoForge fills the rest with defaults).
2. Waits for `Done (`, runs `/dungeontrain debug cycle-layout 1` and reads the first `end better` slot's
   X range from the log.
3. `forceload add` a strip `STRIP_LEN` (default 1024) blocks long starting `STRIP_IN` (default 1500) blocks
   into the slot, **Z 32..95** — four chunk rows off the track corridor, inside the band core where the
   erosion ramp is 1.
4. Polls `save-all flush` + the on-disk `Status=minecraft:full` count until every strip chunk is FULL (or
   the count stops moving), then **settles 90 s** so feature spill still in flight is delivered before
   `stop` — spill is written on later ticks and is not persisted, so stopping early loses it (the likely
   source of #1746's end-of-strip differences).
5. Copies the world to `scripts/endband/out/<arm>.world` and prints the census summary.

## Census (`count_decorations.py`)

Per chunk: status, non-air blocks, End ground (`end_stone`/chorus), `betterend:*` blocks, anything else,
`vanilla_end_quarts` (section biome-palette entries naming a vanilla End biome — `the_end`, `end_highlands`,
`end_midlands`, `end_barrens`, `small_end_islands`), and the attachments `dungeontrain:end_band_pending`
(sample still owed) and `dungeontrain:end_band_sampled_cells` (marker left on a chunk saved before
promotion). Classes:

| class | meaning |
|---|---|
| `decorated` | End ground plus BetterEnd vegetation / other blocks — a normal sampled chunk |
| `ore-only` | End ground plus only BetterEnd's injected ores (flavolite, thallasium, ender ore) — a **vanilla-biome** chunk that caught a vein |
| `bare` | End ground and **nothing else** — a vanilla `end_barrens`/`small_end_islands` chunk (#1785), or what the void erosion left when a sample's decoration was lost (#1774, pre-0.1117) |
| `empty` | no blocks at all (void, or a pending chunk whose sample never landed) |
| `other` | blocks but no End ground (overworld / another band — the range clipped a seam) |

**#1785 turned out to be the vanilla patches.** BetterEnd's End map keeps vanilla's End biomes beside its
own, and vanilla `end_barrens`/`small_end_islands` place nothing: in a fresh 0.1126.2 world 20 of 21 bare
band chunks sat on vanilla-biome labels. `endBandBetterEndOnly` (default on) remaps them to BetterEnd
biomes in the band's samples and labels; a band generated with it should census `bare` (≥100 ground) = 0,
`ore-only` = 0 and `vanilla_end_quarts` = 0.

`--compare A B` adds per-chunk class/count differences and the per-X-slab non-air diff (#1746's metric).
Pure logic is tested: `python3 -m pytest scripts/endband/test_count_decorations.py`.

## Gotchas

- **One worktree per arm** if the arms run at once; two servers cannot share `run/`.
- **Compare interior chunks.** The strip's outer neighbours only reach FEATURES (never FULL), so they
  receive no held spill and the strip's edge rows miss some of theirs; differences confined to the strip
  edge are the protocol, not the toggle.
- **Pre-#1777 saves stay bare.** A world generated before 0.1117 in `WORLDGEN` mode had its sampled
  decoration eroded at chunk load; nothing regenerates it. Use fresh worlds.
- **The remap only touches new chunks.** Flipping `endBandBetterEndOnly` on an existing world leaves its
  old vanilla patches as they were, next to remapped new chunks.
- **No player ⇒ no sweep/prefetch.** The background `sweepPending`/`prefetch` paths need a player in
  range; a forceloaded strip exercises the inline worldgen path only, which is what #1785 is about. A
  chunk left `pending` therefore stays pending — visible in the census, which is the point.
- The Claude Code harness reaps long-running background process trees; run arms from your own terminal
  (same as `scripts/perf/`).
