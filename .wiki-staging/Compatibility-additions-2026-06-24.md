<!-- WIKI STAGING: Append/merge these sections into Compatibility.md on the wiki -->

## Nether Band Compatibility Notes (June 2026)

### Basalt-Delta Spillover *(v0.360.0)*

Basalt from basalt-delta Nether biomes could land on the rails or bed row through cross-chunk spillover. The corridor cleanup now re-sweeps neighbours on chunk load and re-stamps the authored track template, clearing any late-arriving basalt. Server-side only; no client changes.

### Waterlogged Stairs in the Nether Tunnel *(v0.359.0)*

The train tunnel and its pillar staircases incorrectly inherited terrain waterlogging when stamping through the Nether band. Fixed by setting `LiquidSettings.IGNORE_WATERLOGGING` on both the worldgen and runtime stamps for the tunnel and pillar staircase templates. Adds `StructureTemplateWaterloggingTest`.

### Bed Explosion Scope *(v0.356.0)*

Beds previously exploded in the overworld Nether-band biome as well as the real Nether core. Explosion is now correctly scoped to the real Nether core only, matching vanilla behaviour. Sleeping elsewhere shows a message that your respawn point rides with the train.

### Nether Core Length *(v0.357.0)*

The real-Nether core now spans 5,000 blocks (previously 400), matching the End-island stretch length. Any Sable physics or mob-behaviour compatibility testing should account for the extended Nether crossing duration.
