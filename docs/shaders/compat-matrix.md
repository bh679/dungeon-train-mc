# Shader compatibility matrix

How Dungeon Train's five atmosphere systems behave under each supported Iris shader pack.

**Status: not yet measured.** The instrument (F3+5, see below) and the test stack are in place;
the cells are empty until the sweep is run. Do not cite an empty cell as "works".

---

## Why a matrix and not a fix

All five systems reach the screen through hooks a shader pack is free to honour or discard:

| System | Hook | What a pack can do with it |
|---|---|---|
| Skyboxes in bands | `LevelRenderer#renderSky` TAIL → `SkyDomeDraw` (`position_color` / `position_tex_color`) | Routed into `gbuffers_skybasic`/`skytextured`; a pack that rebuilds sky in composite from depth can overwrite it entirely |
| Skybox Blocks | colour-masked depth write + stencil at `AFTER_SKY` | Reaches Iris' composite with cleared albedo/normal → shades black. **Already disabled** (`ShaderCompat.allows`) |
| Dimensional carriage fog | `ViewportEvent.RenderFog` near/far planes | Surfaces as `fogStart`/`fogEnd`; packs that compute their own atmospheric fog ignore both |
| Carriage skybox & lighting | `LightTexture#updateLightTexture` lift + `ViewportEvent.ComputeFogColor` | The lightmap is bound as a sampler, but packs deriving light from raw `lmcoord` never read it |
| Carriage transition | `LightTexture` hold (`LightTexturePortalCrossingMixin`) | Same as above |

Which of those each pack actually does is a property of the pack. It cannot be read off this
codebase, so it is measured here, and the fixes in later phases are aimed at what the measurement
finds rather than at what the source suggests.

---

## The instrument: F3 + 5

`ShaderDiagnosticsHud` draws a panel **top-right** stating what DT asked the frame for. The rest of
the screenshot is what the pack did with that request. **Both halves are needed** — "DT never asked"
and "the pack discarded the ask" look identical in a bare screenshot, and the fix for one is wrong
for the other.

Open with **F3+5** (F3+4 is the train panel; both can be up at once). Open by default in a dev
build; gated on a debug grant otherwise.

| Panel line | Reads |
|---|---|
| `Pack:` | family id + the exact zip Iris loaded |
| `Stencil: / Gfx: / DH:` | stencil attachment present, vanilla graphics tier, Distant Horizons |
| `Band t:` | void / nether / upside-down intensity **at the camera** — the ask |
| `Band sky drawn:` | the alpha each band overlay actually submitted — 0 means it did not draw |
| `Fog colour:` | which band tinted the fog, and the colour in → out |
| `Fog dist:` | vanilla far → DT far, near, and whether the event was cancelled (uncancelled = ignored by design) |
| `Skybox blocks:` | cubes indexed near the camera, variants, stencil, whether the pass drew — or the off-reason |
| `Room sky:` | dimensional carriage sky kind, intensity, and the lightmap lift applied |
| `Transition:` | corridor ramp and the lift applied |

A line is **green** when that system asked for something this frame, **grey** when idle, **red**
when the pack has it switched off. Grey means look at the ask, not at the pack.

---

## Test stack

Everything below lives in the gitignored `run/` of this worktree; nothing here ships.

**`run/mods/`** — the exact pair `modpack.config.json` pins, which is what players run:
- `sodium-neoforge-0.8.12+mc1.21.1.jar` (Modrinth `S3DUMfBo`)
- `iris-neoforge-1.8.14-beta.1+mc1.21.1.jar` (Modrinth `KduFYu4t`)

> **The version triangle is narrow.** Sable declares Sodium incompatible below `0.8.12-alpha.2` and
> hard-crashes FML pre-load otherwise; stable Iris 1.8.12 pins Sodium 0.6.13, exactly the version
> Sable rejects. Beta Iris 1.8.14 + stable Sodium 0.8.12 is the pair that boots. Re-check this if
> `sable_version` moves.

**`run/shaderpacks/`** — eleven packs, all from Modrinth at their latest 1.21.1 version.

**Presets.** Iris reads per-pack settings from `run/shaderpacks/<exact-zip-name>.txt`, so a preset
is applied by dropping the file in — no GUI. Three are in place from the ones supplied:
`BSL_v10.1.3.zip.txt`, `ComplementaryReimagined_r5.8.1.zip.txt`,
`Sildur's Enhanced Default v1.19 Fancy.zip.txt`. Boot confirms them:
`[Iris/]: Profile: Custom (+5 options changed by user)`.

> ⚠ **These three presets are partial** — five keys each, as supplied, not the whole file. Every
> key not listed falls back to the pack's own default, which is safe but is *not* the same
> configuration. `FOG_HEIGHT`, `FOG_DENSITY_END` and `LIGHT_SHAFT_WEATHER_FALLOFF` bear directly on
> three of the five systems, so a fog result measured against a partial preset should be re-checked
> against the complete one before it is treated as final.

> ⚠ **Version drift from the supplied builds.** Modrinth's latest differs from what was sent:
> BSL v10.1 → v10.1.3, Complementary Reimagined r5.6.1 → r5.8.1, Sildur's Enhanced Default v1.18
> Fast → v1.19 Fancy. The Complementary build sent was also bundled with the `Clrwl_1.0.3` add-on,
> which the plain Modrinth pack does not include.

**Switching packs** — one line, then relaunch:

```bash
printf 'enableShaders=true\nshaderPack=%s\nmaxShadowRenderDistance=32\n' "BSL_v10.1.3.zip" > run/config/iris.properties
```

**Launching** — from the worktree root, with JDK 17 on `JAVA_HOME`:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew runClient --no-daemon
```

Confirm the stack took, in `run/logs/latest.log`:
- `[Iris/]: Using shaderpack: <zip>`
- `[Iris/]: Creating pipeline for dimension minecraft:overworld`
- `[AbstractSableMixinPlugin/]: Using Sodium renderer mixins`

---

## The five sites

Open **F3+5** first; it is both the record and the navigation aid.

1. **Band skybox — End/void.** `/dtp <x>`, stepping forward until `Band t: void` rises above 0.
   Capture at `void ≈ 1.0`. Look up and at the horizon.
2. **Band skybox — Nether.** Same sweep, `Band t: nether`.
3. **Band skybox — upside-down.** Same sweep, `Band t: flip`. Check the cloud plane too.
4. **Skybox Blocks.** Place `skybox_end` / `skybox_surface` / `skybox_night` blocks in a wall
   (creative), both in the world and on a carriage. `Skybox blocks:` should count them.
5. **Carriage fog + sky/lighting + transition.** `/dungeontrain portal test` for a dimensional
   carriage. `Fog dist:` engages inside, `Room sky:` rises with the lift, and `Transition:` ramps
   while walking the corridor. Capture mid-corridor and inside the room.

Screenshots (F2) land in `run/screenshots/`; file them as
`test-results/gate2-shader-compat-2026-09/<pack-id>-<site>.png`.

---

## Results

Cells: **works** / **degraded** (visible but wrong) / **absent** (DT asked, nothing appeared) /
**wrong** (actively worse than no effect) / **off** (DT declined to draw).

| Pack | Band skybox | Skybox Blocks | Carriage fog | Carriage sky & lighting | Transition |
|---|---|---|---|---|---|
| complementary_reimagined | | off | | | |
| complementary_unbound | | off | | | |
| bsl | | off | | | |
| bliss | | off | | | |
| solas | | off | | | |
| makeup_ultra_fast | | off | | | |
| spooklementary | | off | | | |
| footage | | off | | | |
| insanity | | off | | | |
| hysteria | | off | | | |
| sildurs | | off | | | |

### Per-system conclusion

To be written once the rows are filled. Each system needs one of:

- **honoured** — leave the vanilla path alone;
- **per-pack override** — the mechanism works on some packs, so gate it in `ShaderCompat.allows`;
- **post-composite** — no pack honours it, so DT must draw its own atmosphere after Iris' composite
  (a fullscreen depth-aware pass and DT's first shipped GLSL — the expensive option, and the reason
  this matrix exists rather than being assumed).
