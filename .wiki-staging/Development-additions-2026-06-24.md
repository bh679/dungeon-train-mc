<!-- WIKI STAGING: Append/merge these sections into Development.md on the wiki -->

## Dependency Changes (June 2026)

### AIS Bumped to 0.6.0 *(v0.358.0)*

Adventure Items Sable (AIS) was pinned to `0.6.0` to wire in its new `applyStats(stack, rng, primaryStatBonus)` overload, which enables per-difficulty flat stat bonuses on item rolls. See `ItemStatLevelScaling` and `ItemStatLevelScalingTest` for the policy helper.

## Current Stack

| Dependency | Version |
|---|---|
| NeoForge | 21.1.228 |
| Sable | 2.0.2+mc1.21.1 |
| Mod | 0.360.0 |

## New Tests (June 2026)

- `ItemStatLevelScalingTest` — unit tests for the per-difficulty primary-stat bonus policy
- `StructureTemplateWaterloggingTest` — verifies tunnel and pillar stamps honour `IGNORE_WATERLOGGING`
