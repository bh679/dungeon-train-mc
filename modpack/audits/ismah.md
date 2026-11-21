# Audit: I See My Armored Hand 6.1.1

Audited 2026-11-21 from the 1.21.1 NeoForge jar, using [`../newmod.md`](../newmod.md). **Not added to the modpack
yet:** this audit is the report for that decision.

| | |
|---|---|
| Mod id | `ismah` |
| Candidate pins | CurseForge 1174111 / file 8988346; Modrinth `o0hnW3gN` / `ibAgWcaO` (same SHA-1 on both: `d6cee42e…31c1e5`) |
| Licence | MIT in the jar, on CurseForge and on Modrinth |
| Sides | **Client only, and it crashes a dedicated server.** The toml says `BOTH` and its mixins sit in the common list, so NeoForge loads them on a server too; `LivingEntityMixin` adds a field of the client-only type `PartPose`, and the server dies at boot (`ClassMetadataNotFoundException: net.minecraft.client.model.geom.PartPose`). A client with it joined a dedicated server without it |
| NeoForge floor | `[1,)`, cleared by DT's 21.1.230 |
| Dependencies | None |
| Size | 48 KB |
| Fair play | **Approved** (2026-11-21, `visual` group in `approved_mods.curated.json`, all 55 Modrinth files hash-baked). Players who install it themselves keep normal runs; this is separate from adding it to the pack |

## What it does

Draws your worn armour (trims, dye and enchantment glint included) on your first-person arm, and a leash held in first
person. Visual only.

## Counts

| Category | Count | Action |
|---|---:|---|
| Main items (armour, weapons, tools) | 0 | None |
| Unfair or balance-breaking items | 0 | None |
| Mobs | 0 | None |
| Structures | 0 | None |
| Loot tables | 0 | None |
| Enchantments and effects | 0 | None |
| Workbenches | 0 | None |
| Advancements | 0 | None |
| New blocks | 0 | None |
| Creative tabs | 0 | None |

The jar has no `data/` content of its own (one empty `data/modtemplate/` folder), registers nothing and sends no
packets.

<details><summary>Mixins (11, all render-side)</summary>

| Mixin | Target | What it does |
|---|---|---|
| `PlayerRendererMixin`, `HumanoidArmorLayerMixin`, `HumanoidModelMixin` | Player and armour rendering | Draws the armour pieces on the first-person arm |
| `StuckInBodyLayerMixin` | Arrow/bee-stinger layer | Keeps those off the first-person arm |
| `LivingEntityMixin`, `PlayerLeashStateMixin` | `LivingEntity`, player | Adds fields only (arm pose, leash state). No logic |
| `LeashAttachMixin` | `ClientPacketListener` entity-link packet | Tracks which leash the local player holds, for drawing |
| `SkinCustomizationScreenMixin` | Skin options screen | Its settings |
| 3 accessors | Renderer and model fields | Read access |

</details>

## Conflicts

| Check | Result |
|---|---|
| Existing mods | No id clash with anything in the pack. First-person Model (`firstperson`) is approved for fair play but not in the pack; it also draws the first-person body, so a player who adds both may see the two overlap. Not tested |
| Render stack | Booted and rendered with Sodium 0.8.12 + Iris 1.8.14 (the pack's pins) with no mixin errors. **No shader pack was active**: first-person arms under an Iris shader pack are untested |
| World gen, loot, vanilla data, economy | Untouched |

## DT systems it can reach

| System | Result |
|---|---|
| Sable | Tested riding a moving train: the armoured arm draws normally (screenshot 02) |
| Commands, HUD, network | No commands, keybinds or payloads |
| Text | Ships `en_us` and `es_es` only, for its settings screen. 18 of DT's 20 locales would see English there |
| Saves | Nothing saved. Removing it changes nothing in a world |

## Performance cost

| Cost | Result |
|---|---|
| Download size | +48 KB |
| Boot time | No measurable change (measured with BEB and xali's Potions together; see `beautiful-enchanted-books.md`) |
| World load time | Not measured: it adds no world data, registries or chunk work |
| Runtime | Client render only: a few extra model draws for the first-person arm. No tick handlers, no server code. Tick A/B not run (nothing on the server tick) |

## Pack and release checks

| Check | Result |
|---|---|
| Licence | MIT everywhere |
| Same build on both platforms | Yes |
| NeoForge floor | Clear |
| Sides | **Fails.** It must never reach a server. Modrinth marks it `server_side: unsupported`, but anyone who builds a server from the CurseForge pack, or a host's one-click install of it, gets a server that will not start |
| Enabled or opt-in | Not until the server crash is handled |
| Config defaults | None shipped |

## Verdict

**Don't add it yet.** It works well on the client, but it crashes any dedicated server it lands on, and the pack is
installed on servers (Kinetic Hosting is bundled). Options, in order of effort:

1. Ask its author to move the mixins to the `client` list (a one-line change on their side), then re-audit.
2. Ship it opt-in and confirm both packs' server installs leave it out. Not verified for CurseForge.

DT's client-setup crash (see `beautiful-enchanted-books.md`) should be fixed first in any case.

Wiring, once it is safe: `modpack.config.json` entry (`slug: i-see-my-armored-hand`,
`project_id: 1174111`, `file_id: 8988346`, `modrinth_project: o0hnW3gN`, `modrinth_version: ibAgWcaO`,
`required: true`, `mod_ids: ["ismah"]`), `i-see-my-armored-hand(optional)` in `release.yml`
`curseforge-dependencies`, roster row in `modpack/README.md`.
